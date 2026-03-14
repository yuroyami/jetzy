package jetzy.models

data class QRData(
    val hotspotSSID: String,
    val hotspotPassword: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String
) {
    override fun toString(): String = "${hotspotSSID}:${hotspotPassword}:${ipAddress}:${port}:${deviceName}"

    companion object {
        fun String.toQRData(): QRData {
            val data = split(":")
            return QRData(
                hotspotSSID = data[0],
                hotspotPassword = data[1],
                ipAddress = data[2],
                port = data[3].toIntOrNull() ?: 80, //TODO
                deviceName = data[4]
            )
        }

    }
}