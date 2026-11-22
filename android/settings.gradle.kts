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
    }
}

include(":app", ":benchmark")
