plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "bench.benchmark"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }
    // Benchmarks measure release code; androidx.benchmark refuses debuggable builds.
    testBuildType = "release"
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation("bench:core-kmp")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.junit)
}

// Measured and gated with the app: its `app` variant takes this module's release unit tests.
kover {
    currentProject {
        createVariant("app") {
            add("release")
        }
    }
}

ktlint {
    version = libs.versions.ktlint.get()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src")
}
