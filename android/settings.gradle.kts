rootProject.name = "android"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("../core-kmp") {
    dependencySubstitution {
        substitute(module("bench:core-kmp")).using(project(":"))
        substitute(module("bench:contract-runner")).using(project(":contract-runner"))
    }
}

include(":app", ":benchmark", ":fixtures")
