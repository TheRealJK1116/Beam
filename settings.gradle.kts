pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Beam"

include(":app")

// Pure-Kotlin protocol core. No Android, no transport SDKs.
include(":core:model")
include(":core:session")

// Android-side transport abstraction plus its interchangeable backends.
include(":core:transport")
include(":transport:nearby")
include(":transport:wifidirect")
include(":transport:fake")

include(":feature:nfc")

include(":data:history")
include(":data:settings")
