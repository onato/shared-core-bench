import co.touchlab.skie.configuration.SuppressSkieWarning
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.skie)
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
    val xcFramework = XCFramework("SharedCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "SharedCore"
            xcFramework.add(this)
            @OptIn(KotlinNativeCacheApi::class)
            disableNativeCache(
                DisableCacheInKotlinVersion.`2_4_21`,
                "Xcode 27's ld can't open the members of Kotlin/Native's prebuilt cache archives and warns once per member",
            )
        }
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
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
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

// Swift exposes these as `description_`, since `description` is NSObject's.
skie {
    features {
        listOf("bench.core.ProductDetail", "bench.core.db.Product", "bench.core.db.ById").forEach {
            group("$it.description") { SuppressSkieWarning.NameCollision(true) }
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
