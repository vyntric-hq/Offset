pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is published only through JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "offset"
include(":protocol", ":propagation", ":app")
