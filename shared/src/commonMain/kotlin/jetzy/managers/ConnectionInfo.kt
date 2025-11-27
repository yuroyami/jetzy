package jetzy.managers

sealed class ConnectionInfo {
    data class NetworkAddress(
        val host: String,
        val port: Int
    ) : ConnectionInfo()
    
    data class QRCodeData(
        val data: String
    ) : ConnectionInfo()
    
    data class WebRTCOffer(
        val sdp: String,
        val signalingServer: String
    ) : ConnectionInfo()
}