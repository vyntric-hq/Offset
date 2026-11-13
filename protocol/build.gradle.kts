plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: no Android dependencies allowed in this module.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Live network tests are opt-in; see HearhamLiveTest in :app.
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
