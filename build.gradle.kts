plugins {
    id("io.github.yuroyami.kmpssot") version "1.3.1"
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.cocoapods).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.plugin).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.buildConfig).apply(false)
    alias(libs.plugins.kSerialization).apply(false)
    alias(libs.plugins.ksp).apply(false)
}

kmpSsot {
    appName         = "Jetzy"
    versionName     = "0.5.0"
    bundleIdBase    = "com.yuroyami.jetzy"
    iosBundleSuffix = ".ios"        // explicit
    javaVersion     = 21

    sharedModule     = "shared"
    androidAppModule = "androidApp"

    appLogoPngForeground   = file("shared/src/commonMain/composeResources/drawable/jetzy_raster.png")
    appLogoBackgroundColor = "#55555B"
    appLogoAndroidSafeZoneRatio = 0.6

    ios {
        usesNonExemptEncryption = false   // silences App Store "Missing Compliance"
        proMotion120Hz          = true    // unlock 120 Hz on ProMotion iPhones
    }

    // locales auto-detected from shared/src/commonMain/composeResources/values-*
    // (Jetzy has none yet, so the list stays empty.)
}
