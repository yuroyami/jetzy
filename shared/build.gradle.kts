import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("org.jetbrains.compose")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
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

//    js {
//        browser()
//        binaries.executable()
//    }

    sourceSets {
        all {
            languageSettings {
                optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
            }
        }
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

            api("co.touchlab:kermit:2.0.3")

            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.3")

            val ktor =  /* "2.3.9" */ "3.0.0-beta-1"
            implementation("io.ktor:ktor-network:$ktor")

            implementation("io.github.alexzhirkevich:qrose:1.0.0-beta02")

            /* Compose core dependencies */
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            //@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            api(compose.components.resources)
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0-beta01")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            api("androidx.core:core-ktx:1.13.0")
            api("androidx.appcompat:appcompat:1.7.0-alpha03")
            api("androidx.activity:activity-compose:1.9.0")
            api("androidx.core:core-splashscreen:1.1.0-rc01")

            api("com.google.android.gms:play-services-nearby:19.2.0")

            api("androidx.documentfile:documentfile:1.0.1")
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
            implementation(compose.desktop.currentOs)
        }

//        jsMain.dependencies {
//            implementation(compose.html.core)
//        }

        iosMain.dependencies {
        }

    }

}

android {
    namespace = "jetz.common.android"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
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

compose.experimental {
    web.application {}
}


tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}