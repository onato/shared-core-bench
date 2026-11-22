plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            val contractCases = rootDir.resolve("../contract/cases").canonicalFile
            inputs.dir(contractCases).withPropertyName("contractCases")
            systemProperty("contract.cases", contractCases.path)
        }
    }
    android {
        namespace = "bench.core"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTest {}
    }
    compilerOptions {
        allWarningsAsErrors = true
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":contract-runner"))
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
        }
    }
}

sqldelight {
    databases {
        create("ProductDatabase") {
            packageName.set("bench.core.db")
        }
    }
}

kover {
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
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src")
}
