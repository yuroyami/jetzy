import java.util.Properties

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(AppConfig.javaVersion)
}

android {
    namespace = "jetzyApp.android"
    compileSdk = AppConfig.compileSdk

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

    defaultConfig {
        minSdk = AppConfig.minSdk
        targetSdk = AppConfig.compileSdk
        applicationId = "com.yuroyami.jetzy"
        versionCode = AppConfig.versionCode
        versionName = AppConfig.versionName
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("keystore")
        }
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("keystore")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
    implementation(projects.shared)
}