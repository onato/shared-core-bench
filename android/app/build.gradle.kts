plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// The bundled SQLite's Android artifact carries device natives only; unit tests on the host load
// the same library from its JVM artifact.
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
    namespace = "bench.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "bench.android"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk = compileSdk
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.useJUnitPlatform()
            val contractCases = rootDir.resolve("../contract/cases").canonicalFile
            test.inputs.dir(contractCases).withPropertyName("contractCases")
            test.systemProperty("contract.cases", contractCases.path)
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
    implementation(project(":fixtures"))
    kover(project(":benchmark"))
    kover(project(":fixtures"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.okhttp)
    implementation(libs.sqlite.bundled)
    hostSqlite(libs.sqlite.bundled.jvm)
    implementation(libs.paging.common)
    implementation(libs.paging.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation("bench:contract-runner")
    testImplementation(libs.sqldelight.jvm.driver)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.paging.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}

kover {
    currentProject {
        createVariant("app") {
            add("debug")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    file("coverage-exclusions")
                        .readLines()
                        .map { it.substringBefore("#").trim() }
                        .filter { it.isNotEmpty() },
                )
            }
        }
        verify {
            rule {
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    minValue = 100
                }
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 95
                }
            }
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
