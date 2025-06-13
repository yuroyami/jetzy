package jetzy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import jetzy.p2p.P2pCallback
import jetzy.ui.AdamScreen
import jetzy.ui.p2pCallback

class MainActivity: ComponentActivity(), P2pCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        p2pCallback = this

        setContent {
            AdamScreen()
        }
    }

    override fun p2pInitialize() {

    }

    override fun p2pStartNativePlatform() {

    }

    override fun p2pStartCrossPlatform() {

    }

    override fun p2pRequestBluetooth() {

    }

}