@file:OptIn(ExperimentalDistributionDsl::class)
@file:Suppress("WrongGradleMethod")
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.cocoapods)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.android.application)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.kSerialization)
    alias(libs.plugins.ksp)
}

val verString = "0.1.0"
val verCode = ("1" + verString.split(".").joinToString("") { it.padStart(2, '0') }).toIntOrNull() ?: 0

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(21)

    androidTarget {
    }

    js(IR) {
        outputModuleName = "jetzyApp"
        generateTypeScriptDefinitions()
        browser {
            commonWebpackConfig {
                outputFileName = "jetzyApp.js"
                sourceMaps = false // Disables source maps
            }

            distribution {
                outputDirectory.set(rootDir.resolve("WebAppOutput"))
            }
        }
        binaries.executable()
        useEsModules()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

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
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.io.encoding.ExperimentalEncodingApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi") //for iOS
                optIn("kotlinx.cinterop.BetaInteropApi") //for iOS
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.io.encoding.ExperimentalEncodingApi")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }

        commonMain.dependencies {
            /* Standard Kotlin lib to match the compiler */
            implementation(libs.kotlinstdlib)

            /* Explicitly specifying a newer coroutines version */
            implementation(libs.koroutines.core)

            /* Logging */
            implementation(libs.kermit)

            /* Multiplatform Kotlin equivalent of Java's datetime */
            implementation(libs.kotlinx.datetime)

            /* IO extensions */
            implementation(libs.kotlinx.io)

            /* Networking */
            implementation(libs.ktor.network)

            /* QRCode generation */
            implementation(libs.qrose)

            /* Compose multiplatform dependencies */
            implementation(libs.bundles.compose.multiplatform)

            implementation("br.com.devsrsouza.compose.icons:font-awesome:1.1.1")

            /* ViewModel support */
            implementation(libs.compose.viewmodel)

            /* Screen Navigation */
            implementation(libs.bundles.compose.navigation3)

            /* FileKit to save/open files */
            implementation(libs.filekit)
            implementation(libs.filekit.coil)
            implementation(libs.coil)

            /* Dependency Injection */
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.bundles.koin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            /* Android Backward compatibility APIs */
            implementation(libs.jetpack.core)
            implementation(libs.jetpack.appcompat)

            /* Splash Screen with backward compatibility */
            implementation(libs.jetpack.splashscreen) //1.2.0 bugs our navbar opacity

            /*  Activity's compose support with backward compatibility */
            implementation(libs.jetpack.activity.compose)

            /* Coroutine support for Android threading */
            implementation(libs.koroutines.android)

            /* Support for SAF storage */
            implementation(libs.jetpack.documentfile)

            //api("com.google.android.gms:play-services-nearby:19.3.0")
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
            implementation(compose.desktop.currentOs)
        }

        jsMain.dependencies {

        }

        iosMain.dependencies {

        }
    }
}

android {
    namespace = "jetzy"
    compileSdk = 36

    signingConfigs {
        create("jetzy") {
            storeFile = file("${rootDir}/keystore/jetzy.jks")
            keyAlias = "jetzy"
            keyPassword = "az90az09"
            storePassword = "az90az09"
        }
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        applicationId = "com.yuroyami.jetzy"
        versionCode = verCode
        versionName = verString

        signingConfig = signingConfigs.getByName("jetzy")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.yuroyami.jetz.desktop"
            packageVersion = "1.0.0"
        }
    }
}

buildConfig {
    buildConfigField("APP_VERSION", verString)
    buildConfigField("DEBUG", false)
    buildConfigField("ALLOW_LOGGING", false)
}