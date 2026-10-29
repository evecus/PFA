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
        // libsu（topjohnwu）仅发布在 JitPack，不在 Google/Maven Central
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Pfa"
include(":app")
