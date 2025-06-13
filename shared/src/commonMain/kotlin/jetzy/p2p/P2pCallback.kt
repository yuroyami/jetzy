package jetzy.p2p

interface P2pCallback {

    /* These are the callbacks related to P2P operations only */
    fun p2pInitialize()
    fun p2pStartNativePlatform()
    fun p2pStartCrossPlatform()
    fun p2pRequestBluetooth() {}

}