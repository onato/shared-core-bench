plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// The bundled SQLite's Android artifact carries device natives only; unit tests on the host load
// the same library from its JVM artifact. The app's tests open this port too, so they load these.
val hostSqlite: Configuration = configurations.create("hostSqlite") { isTransitive = false }

val hostSqliteNatives =
    tasks.register<Sync>("hostSqliteNatives") {
        val os = System.getProperty("os.name").lowercase()
        val platform =
            when {
                "mac" in os -> "osx_arm64"
                System.getProperty("os.arch") == "aarch64" -> "linux_arm64"
                else -> "linux_x64"
            }
        from({ zipTree(hostSqlite.singleFile) }) {
            include("natives/$platform/*")
            eachFile { relativePath = RelativePath(true, name) }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("hostSqliteNatives"))
    }

android {
    namespace = "bench.storage"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
    testOptions {
        unitTests.all { test ->
            test.dependsOn(hostSqliteNatives)
            test.systemProperty("java.library.path", hostSqliteNatives.get().destinationDir.path)
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation("bench:core-kmp")
    implementation(libs.sqlite.bundled)
    hostSqlite(libs.sqlite.bundled.jvm)
    testImplementation(libs.junit)
}

// Measured and gated with the app: its `app` variant takes this module's debug unit tests.
kover {
    currentProject {
        createVariant("app") {
            add("debug")
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
