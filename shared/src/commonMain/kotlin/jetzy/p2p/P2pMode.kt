package jetzy.p2p

enum class P2pMode(val usesQR: Boolean) {

    ANDROID_TO_ANDROID(false),
    IOS_TO_IOS(false),
    CROSS_PLATFORM(true)

}