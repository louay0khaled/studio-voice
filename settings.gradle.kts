// TEMP PROBE B — REMOVE AFTER DIAGNOSIS.
gradle.projectsEvaluated {
    println("::warning title=ProbeEvaluated::all project scripts evaluated OK")
}
gradle.buildFinished { result ->
    println("::notice title=BuildFinishedFired::hook fired")
    var t: Throwable? = result.failure
    var depth = 0
    while (t != null && depth < 8) {
        val msg = (t.message ?: "").replace("\n", " | ").take(450)
        println("::error title=BuildFailureChain::[${t.javaClass.simpleName}] $msg")
        t = t.cause
        depth++
    }
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
