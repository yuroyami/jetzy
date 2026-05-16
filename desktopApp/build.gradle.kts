import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import io.github.yuroyami.kmpssot.kmpSsot

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.plugin)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        // Kotlin 2.3.20 is a pre-release; shared's classes carry the pre-release
        // marker, so consumers (us) must opt in to use them.
        freeCompilerArgs.addAll(
            "-Xskip-prerelease-check",
            "-Xexpect-actual-classes",
        )
    }
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)

    // Re-exported types from shared (JetzyViewmodel : ViewModel) — the shared
    // module declares these as `implementation` so they don't propagate to
    // non-KMP consumers; we pull the type-bearing artifact in explicitly here.
    implementation(libs.compose.viewmodel)
}

compose.desktop {
    application {
        mainClass = "jetzy.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Jetzy"
            packageVersion = kmpSsot.versionName.get()
            description = "Peer-to-peer file transfer for desktop"
            copyright = "© 2026 Yuroyami"

            val logoIco = rootProject.file("shared/src/commonMain/composeResources/drawable/jetzy_raster_withbg.png")
            macOS {
                bundleID = "${kmpSsot.bundleIdBase.get()}.desktop"
                iconFile.set(logoIco)
            }
            windows {
                iconFile.set(logoIco)
                menuGroup = "Jetzy"
                upgradeUuid = "8b3f0d6e-3f3a-4d63-9b15-2c4f5a1f3a8c"
            }
            linux {
                iconFile.set(logoIco)
            }
        }
    }
}
