plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.beam.feature.nfc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    // Debug and release install side by side, and an AID may only be routed to one service.
    // Sharing one meant every tap raised the platform's "which app?" chooser - which asks you
    // to tap a second time, long after the sender has given up. Separate AIDs keep the two
    // builds out of each other's way; debug talks to debug, release to release, by design.
    buildTypes {
        debug {
            buildConfigField("int", "AID_VARIANT", "0x81")
            resValue("string", "beam_aid", "F04265616D81")
            resValue("string", "beam_hce_description", "Beam (debug) — receive by tapping phones")
        }
        release {
            buildConfigField("int", "AID_VARIANT", "0x01")
            resValue("string", "beam_aid", "F04265616D01")
            resValue("string", "beam_hce_description", "Beam — receive by tapping phones")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:session"))
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
