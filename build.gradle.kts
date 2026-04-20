plugins {
    id("com.yuroyami.kmpssot") version "0.6.0"
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
    versionName     = "0.3.0"
    bundleIdBase    = "com.yuroyami.jetzy"
    iosBundleSuffix = ".ios"        // explicit; defaults are null in 0.5.0+
    javaVersion     = 21

    sharedModule     = "shared"
    androidAppModule = "androidApp"

    appLogoXml = file("art/jetzy_vector.xml")
    appLogoPng = file("art/jetzy_raster.png")

    // locales auto-detected from shared/src/commonMain/composeResources/values-*
    // (Jetzy has none yet, so the list stays empty.)
}
