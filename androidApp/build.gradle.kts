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
        create("jetzy") {
            storeFile = file("${rootDir}/keystore/jetzy.jks")
            keyAlias = "jetzy"
            keyPassword = "az90az09"
            storePassword = "az90az09"
        }
    }

    defaultConfig {
        minSdk = AppConfig.minSdk
        targetSdk = AppConfig.compileSdk
        ""
        applicationId = "com.yuroyami.jetzy"
        versionCode = AppConfig.versionCode
        versionName = AppConfig.versionName

        signingConfig = signingConfigs.getByName("jetzy")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
    implementation(projects.shared)
}