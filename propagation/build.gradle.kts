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
    // Pure computation: no network, no Android.
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
