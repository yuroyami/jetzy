@file:OptIn(ExperimentalDistributionDsl::class)
@file:Suppress("WrongGradleMethod")
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

    android {
        namespace = "jetzy"
        compileSdk = AppConfig.compileSdk
        minSdk = AppConfig.minSdk
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

    //jvm()

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
                enableLanguageFeature("ExplicitBackingFields") //same as -Xexplicit-baxcking-fields compiler flag
                enableLanguageFeature("NestedTypeAliases") //-Xnested-type-aliases
                enableLanguageFeature("ExpectActualClasses") //-Xexpect-actual-classes
                enableLanguageFeature("ContextParameters") //Xcontext-parameters
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

            implementation("br.com.devsrsouza.compose.icons:font-awesome:1.1.1")

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

//        jvmMain.dependencies {
//            implementation(libs.compose.desktop)
//            implementation(compose.desktop.currentOs)
//        }
//
//        jsMain.dependencies {
//
//        }

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
    buildConfigField("APP_VERSION", AppConfig.versionName)
    buildConfigField("DEBUG", false)
    buildConfigField("ALLOW_LOGGING", false)
}