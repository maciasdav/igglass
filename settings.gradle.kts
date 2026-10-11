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
        // API clásica de Xposed (82), compatible con LSPosed.
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "ig-liquidglass"
include(":app")
