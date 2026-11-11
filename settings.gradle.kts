// TEMP PROBE — REMOVE AFTER DIAGNOSIS.
println("::warning title=ProbeSettings::settings.gradle.kts evaluated")
gradle.beforeProject { println("::warning title=ProbeProject::beforeProject ${name}") }

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
    }
}
rootProject.name = "StudioVoice"
include(":app")
