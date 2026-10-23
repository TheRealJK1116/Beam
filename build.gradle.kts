plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Nearby Connections is one interchangeable backend, not the architecture. Only
 * `:transport:nearby` is allowed to name its types; everything else talks to the `Transport`
 * interface in `:core:transport`. The dependency graph already enforces this (no other module
 * declares play-services-nearby), but a stray dependency added later would silently reopen the
 * door, so we also fail the build on the import itself.
 */
val checkTransportBoundary by tasks.registering {
    group = "verification"
    description = "Fails if Nearby Connections types are referenced outside :transport:nearby."

    // Everything the action needs is captured as plain data here, at configuration time, so
    // the task stays configuration-cache friendly.
    val owners = setOf(":transport:nearby")
    val forbidden = listOf("com.google.android.gms.nearby")

    val offendingSources = subprojects
        .filter { it.path !in owners }
        .map { it.path to it.layout.projectDirectory.dir("src").asFile }

    // Snapshot the inputs so the task stays configuration-cache friendly.
    inputs.files(
        offendingSources.map { (_, dir) ->
            project.fileTree(dir) { include("**/*.kt", "**/*.java") }
        }
    )

    doLast {
        val violations = buildList {
            for ((path, srcDir) in offendingSources) {
                if (!srcDir.exists()) continue
                srcDir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        file.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                val hit = forbidden.firstOrNull { it in line }
                                if (hit != null) {
                                    add("$path: ${file.relativeTo(srcDir)}:${index + 1} references $hit")
                                }
                            }
                        }
                    }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Transport boundary violated. Only ${owners.joinToString()} may reference Nearby Connections:")
                    violations.forEach { appendLine("  $it") }
                    appendLine("Route the call through the Transport interface in :core:transport instead.")
                }
            )
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkTransportBoundary)

        // Compile the release variant too. `check` runs the debug unit tests and lints the
        // debug variant, so code that only release compiles - the stubs standing in for the
        // debug-only simulator, above all - was never built until someone cut a release. A
        // call added to shared UI against a debug-only function left check perfectly green
        // and the release build broken, which is the worst moment to find out.
        // Resolved lazily: at the point `check` is configured the Android plugin has not
        // necessarily registered its per-variant compile tasks yet.
        dependsOn(provider { tasks.filter { it.name == "compileReleaseKotlin" } })
    }

    // Robolectric reaches into FileDescriptor and ClassLoader internals to emulate the
    // framework, which the module system blocks from JDK 17 onwards.
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
        )
    }
}
