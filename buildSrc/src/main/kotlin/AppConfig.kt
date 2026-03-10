object AppConfig {
    const val javaVersion = 21

    const val compileSdk = 36
    const val minSdk = 26

    const val versionName = "0.1.0"
    val versionCode = ("1" + versionName.split(".").joinToString("") { it.padStart(3, '0') }).toInt()
}
