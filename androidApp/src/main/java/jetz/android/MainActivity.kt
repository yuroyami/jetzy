package jetz.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import jetz.common.p2p.P2pCallback
import jetz.common.ui.ScreenUI
import jetz.common.ui.p2pCallback

class MainActivity: ComponentActivity(), P2pCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        p2pCallback = this

        setContent {
            ScreenUI()
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