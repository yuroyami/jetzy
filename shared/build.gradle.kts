@file:OptIn(ExperimentalDistributionDsl::class)
@file:Suppress("WrongGradleMethod")
import io.github.yuroyami.kmpssot.kmpSsot
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.cocoapods)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.android.library)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.kSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xexpect-actual-classes",
            "-Xcontext-parameters",
        )
    }

    android {
        namespace = "jetzy"
        compileSdk { version = release(providers.gradleProperty("android.compileSdk").get().toInt()) }
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        androidResources { enable = true }
    }
//
//    js(IR) {
//        outputModuleName = "jetzyApp"
//        generateTypeScriptDefinitions()
//        browser {
//            commonWebpackConfig {
//                outputFileName = "jetzyApp.js"
//                sourceMaps = false // Disables source maps
//            }
//
//            distribution {
//                outputDirectory.set(rootDir.resolve("WebAppOutput"))
//            }
//        }
//        binaries.executable()
//        useEsModules()
//    }

    //iosX64()
    iosArm64()
    //iosSimulatorArm64()

    // Native macOS target (Apple Silicon). Compiles the shared module's Apple
    // surface — MPC, NSSharingService/AirDrop, Bonjour discovery — for macOS,
    // so a future `:macosApp` module (Kotlin/Native Compose) can consume it.
    // We don't emit a standalone executable here yet because Compose
    // Multiplatform's macOS-native window API (`application { Window {...} }`)
    // hasn't shipped a stable entry point as of Compose 1.11. The shared module
    // still compiles as a klib usable from a future AppKit-hosted Compose
    // wrapper. Intel Mac target (macosX64) is deprecated in Kotlin/Native's
    // tier list and not included.
    macosArm64()

    // Apple-only shared source set (parent of iosMain + macosMain). MPC, AirDrop
    // and any other Apple framework code lives here; the per-OS source sets keep
    // the OS-specific entry points + delegates.
    applyDefaultHierarchyTemplate()

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "14.0"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            isStatic = false
        }
    }

    jvm("desktop")

    sourceSets {
        all {
            languageSettings {
                optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("androidx.compose.ui.ExperimentalComposeUiApi")
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.io.encoding.ExperimentalEncodingApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi") //for iOS
                optIn("kotlinx.cinterop.BetaInteropApi") //for iOS
                optIn("kotlin.time.ExperimentalTime")
            }
        }

        commonMain.dependencies {
            /* Bundle: Kotlin STDLib + DateTime + Coroutines */
            implementation(libs.bundles.kotlin.essentials)

            /* Logging */
            implementation(libs.kermit)

            /* IO extensions */
            implementation(libs.kotlinx.io)

            /* Networking */
            implementation(libs.ktor.network)

            /* QRCode generation */
            implementation(libs.qrose)

            /* Bundle: Compose multiplatform dependencies (Foundation + UI + Material3 + Viewmodel + Navigation3) */
            implementation(libs.bundles.compose.multiplatform)

            implementation(libs.font.awesome)

            /* Image viewing library */
            implementation(libs.coil)

            /* FileKit to save/open files */
            implementation(libs.filekit)
            implementation(libs.filekit.coil) //Extension for coil support with filekit

            /* Koin Dependency Injection */
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.bundles.koin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            /* Bundle: Jetpack Backward compatibility APIs + Android Coroutine support */
            implementation(libs.bundles.android.essentials)

            //api("com.google.android.gms:play-services-nearby:19.3.0")
        }

        val desktopMain by getting {
            dependencies {
                /* Compose Desktop runtime (Skia + Swing host for the current OS) */
                implementation(compose.desktop.currentOs)

                /* Coroutines on Swing-EDT for any UI-thread dispatching */
                implementation(libs.koroutines.swing)

                /* ZXing — decode QR codes pasted as text isn't enough; we also let the
                 * user load a QR screenshot from disk since the desktop has no camera path. */
                implementation(libs.zxing.core)
                implementation(libs.zxing.javase)

                /* jmdns — mDNS / Bonjour service advertising + discovery on the LAN.
                 * Matches what Android's NsdManager and iOS's NWBrowser/NWListener speak. */
                implementation(libs.jmdns)
            }
        }

        iosMain.dependencies {

        }
    }
}
//
//compose.desktop {
//    application {
//        mainClass = "MainKt"
//
//        nativeDistributions {
//            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
//            packageName = "com.yuroyami.jetz.desktop"
//            packageVersion = "1.0.0"
//        }
//    }
//}

buildConfig {
    buildConfigField("APP_VERSION", kmpSsot.versionName.get())
    buildConfigField("DEBUG", true)
}