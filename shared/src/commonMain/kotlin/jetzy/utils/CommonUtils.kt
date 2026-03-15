package jetzy.utils

/*********************************************************************************************************
 * These utilities are used in common code using common libraries and do not need to use expect/actuals  *
 *********************************************************************************************************/

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import io.github.vinceglb.filekit.coil.addPlatformFileSupport

/** Logs the [s] message to the platform's corresponding log output */
fun loggy(s: Any?) = Logger.e(tag = "Jetzy") { s.toString() }

@DslMarker annotation class NavigationDsl

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