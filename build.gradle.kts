plugins {
    id("io.github.yuroyami.kmpssot") version "1.0.4"
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
    versionName     = "0.4.0"
    bundleIdBase    = "com.yuroyami.jetzy"
    iosBundleSuffix = ".ios"        // explicit
    javaVersion     = 21

    sharedModule     = "shared"
    androidAppModule = "androidApp"

    appLogoXml = file("shared/src/commonMain/composeResources/drawable/jetzy_vector.xml")
    appLogoPng = file("shared/src/commonMain/composeResources/drawable/jetzy_raster_withbg.png")

    // locales auto-detected from shared/src/commonMain/composeResources/values-*
    // (Jetzy has none yet, so the list stays empty.)
}
