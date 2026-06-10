import java.util.Properties

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "jetzyApp.android"
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()
    defaultConfig {
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
    }
    // applicationId, versionCode/Name, compileOptions (java version),
    // manifestPlaceholders[appName], resourceConfigurations — handled by kmpSsot.

    signingConfigs {
        file("${rootDir}/keystore/yuroyamikey.jks").takeIf { it.exists() }?.let { keystoreFile ->
            create("keystore") {
                storeFile = keystoreFile

                val localProperties = Properties().apply {
                    val file = rootProject.file("local.properties")
                    if (file.exists()) load(file.inputStream())
                }
                localProperties.apply {
                    keyAlias = getProperty("keystore.keyAlias")
                    keyPassword = getProperty("keystore.keyPassword")
                    storePassword = getProperty("keystore.storePassword")
                }
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            // Shrinking stays OFF until the rules in proguard-rules.pro get on-device
            // validation (ktor sockets + Koin reflection are the known breakage risks).
            // The rules file is staged so flipping this is config + testing, not research.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("keystore")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".dev"
            signingConfigs.findByName("keystore")?.let { signingConfig = it }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
    implementation(projects.shared)
}
