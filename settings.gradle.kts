pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://api.xposed.info/") // ✅ Kotlin стиль
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") // ✅ Kotlin стиль
    }
}

rootProject.name = "VirtualCam"
include(":app")
