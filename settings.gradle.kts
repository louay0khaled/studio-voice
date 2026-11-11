// TEMP PROBE A — REMOVE AFTER DIAGNOSIS.
gradle.projectsEvaluated {
    println("::warning title=ProbeEvaluated::all project scripts evaluated OK")
}

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

println("::warning title=ProbeSettingsEnd::settings fully evaluated")
