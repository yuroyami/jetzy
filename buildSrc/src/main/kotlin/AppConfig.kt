import org.gradle.api.Project
import java.io.File

object AppConfig {
    const val javaVersion = 21

    const val compileSdk = 36
    const val minSdk = 26

    const val versionName = "0.1.1"
    val versionCode = ("1" + versionName.split(".").joinToString("") { it.padStart(3, '0') }).toInt()

    fun Project.updateIOSVersion() {
        val pbxprojFile = File("${rootDir}/iosApp/iosApp.xcodeproj/project.pbxproj")
        if (!pbxprojFile.exists()) {
            logger.warn("project.pbxproj not found at: ${pbxprojFile.absolutePath}")
            return
        }

        val original = pbxprojFile.readText()
        val updated = original
            .replace(Regex("""MARKETING_VERSION = [^;]+;"""), "MARKETING_VERSION = $versionName;")
            .replace(Regex("""CURRENT_PROJECT_VERSION = [^;]+;"""), "CURRENT_PROJECT_VERSION = $versionCode;")

        if (updated != original) {
            pbxprojFile.writeText(updated)
            logger.lifecycle("✅ Xcode version updated to $versionName ($versionCode)")
        } else {
            logger.warn("⚠️ Version fields not found in project.pbxproj")
        }
    }

}

