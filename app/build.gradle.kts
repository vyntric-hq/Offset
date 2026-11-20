plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/*
 * Version comes from the release tag (v1.2.3 -> 1.2.3) so a rebuild from the tag is identical.
 * CI passes -Poffset.versionName=<tag without v>. Local builds default to the CHANGELOG version.
 */
val offsetVersionName: String = (findProperty("offset.versionName") as String?)?.removePrefix("v") ?: "0.7.0"
val offsetVersionCode: Int = offsetVersionName.substringBefore('-').split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

/** Commit hash for the About screen. "unknown" outside a git checkout. Deterministic for a given tag. */
val offsetGitCommit: String = (findProperty("offset.gitCommit") as String?) ?: providers.exec {
    commandLine("git", "rev-parse", "--short=12", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

/** SHA-256 of the release signing certificate. Committed so rebuilds from a tag are byte identical. */
val offsetReleaseCert: String = rootProject.file("release-cert.sha256").takeIf { it.exists() }
    ?.readLines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()

android {
    namespace = "com.vyntric.offset"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.vyntric.offset"
        minSdk = 29
        targetSdk = 35
        versionCode = offsetVersionCode
        versionName = offsetVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$offsetGitCommit\"")
        buildConfigField("String", "GITHUB_REPO", "\"github.com/vyntric-hq/offset\"")
        // Published release key fingerprint (public, committed in release-cert.sha256), shown on About.
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$offsetReleaseCert\"")
    }

    // Release signing only when CI provides a keystore. Locally assembleRelease is unsigned.
    val keystorePath = System.getenv("OFFSET_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
            // No VCS metadata in the APK: it would make rebuilds outside a git checkout differ.
            vcsInfo.include = false
            // Phones only: MapLibre's x86 libraries exist for emulators and would double the APK.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    // No Google dependency metadata block (F-Droid requirement, and it is not reproducible).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Package MapLibre's prebuilt native libraries as published. AGP strips them only when an
        // NDK happens to be installed, which would make CI and local rebuilds differ.
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Dependency version nags are handled deliberately; see docs/REPRODUCIBLE.md.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // Release ships ARM only on purpose (see ndk.abiFilters); Timber arrives with MapLibre but
        // Offset logs through the platform Log and adds no logging library of its own.
        disable += setOf("ChromeOsAbiSupport", "LogNotTimber")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Baseline profile generation is a known source of non-reproducible bytes; skip it.
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":propagation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.usb.serial)
    implementation(libs.maplibre)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso older than 3.6 calls an InputManager API removed in Android 16.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}
