// TEMP DIAGNOSTIC — REMOVE AFTER CI DIAGNOSIS. Lives in settings because the
// app script's own evaluation is part of what is being diagnosed. Prints the
// failure exception chain, how far the build got, and any JUnit XML failure
// messages as workflow-command annotations (job logs are auth-walled).
gradle.projectsEvaluated {
    println("::warning title=ProbeEvaluated::all project scripts evaluated OK")
}
gradle.buildFinished { result ->
    val app = gradle.rootProject.project(":app")
    val bd = app.layout.buildDirectory.get().asFile
    fun exists(p: String) = java.io.File(bd, p).exists()
    println("::notice title=BuildProgress::mainClasses=${exists("tmp/kotlin-classes/debug")} testClasses=${exists("tmp/kotlin-classes/debugUnitTest")} testResults=${exists("test-results/testDebugUnitTest")}")
    var t: Throwable? = result.failure
    var depth = 0
    while (t != null && depth < 8) {
        val msg = (t.message ?: "").replace("\n", " | ").take(450)
        println("::error title=BuildFailureChain::[${t.javaClass.simpleName}] $msg")
        t = t.cause
        depth++
    }
    val dir = java.io.File(bd, "test-results/testDebugUnitTest")
    if (dir.isDirectory) {
        dir.listFiles { f -> f.name.endsWith(".xml") }?.sorted()?.forEach { f ->
            val text = f.readText()
            fun dec(s: String) = s.replace("&quot;", "\"").replace("&gt;", ">")
                .replace("&lt;", "<").replace("&#10;", " ").replace("&amp;", "&")
            Regex("tests=\"(\\d+)\" skipped=\"(\\d+)\" failures=\"(\\d+)\" errors=\"(\\d+)\"")
                .find(text)?.let {
                    println("::notice title=UnitTestSummary::${f.name} tests=${it.groupValues[1]} failures=${it.groupValues[3]} errors=${it.groupValues[4]}")
                }
            Regex("<(?:failure|error)[^>]*?message=\"([^\"]*)\"").findAll(text).forEach { m ->
                println("::error title=UnitTestFailure::${dec(m.groupValues[1]).take(500)}")
            }
        }
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
