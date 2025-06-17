package jetzy

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import jetzy.p2p.P2pAndroidHandler
import jetzy.p2p.P2pHandler
import jetzy.viewmodel.jetzyModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module


@SuppressLint("ServiceCast")
val androidModule =  module {
    includes(jetzyModule)

    // Android-specific dependencies
    single<Context> { androidContext() }

    // Bind the abstract class to concrete implementation
    single<P2pHandler> {
        P2pAndroidHandler(
            viewModel = get()
        )
    }
}