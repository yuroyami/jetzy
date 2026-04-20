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
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
    // applicationId, versionCode/Name, compileOptions (java version),
    // manifestPlaceholders[appName], resourceConfigurations — handled by kmpSsot.

    signingConfigs {
        file("${rootDir}/keystore/yuroyamikey.jks").takeIf { it.exists() }?.let { keystoreFile ->
            create("keystore") {
                storeFile = keystoreFile

                val localProperties = Properties().apply {
                    val file = File("local.properties")
                    if (file.exists()) load(file.inputStream())
                }
                localProperties.apply {
                    keyAlias = getProperty("yuroyami.keyAlias")
                    keyPassword = getProperty("yuroyami.keyPassword")
                    storePassword = getProperty("yuroyami.storePassword")
                }
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
