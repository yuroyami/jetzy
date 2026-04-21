package jetzy.utils

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import io.github.vinceglb.filekit.coil.addPlatformFileSupport

/*********************************************************************************************************
 * These utilities are used in common code using common libraries and do not need to use expect/actuals  *
 *********************************************************************************************************/

annotation class LoggingApi

annotation class NavigationDsl

annotation class P2pIoApi

/** Logs the [s] message to the platform's corresponding log output */
@LoggingApi
fun loggy(s: Any?) = Logger.e(tag = "Jetzy") { s.toString() }


@Composable
fun InitializeCoilSupportForFileKit() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                addPlatformFileSupport()
            }
            .build()
    }
}