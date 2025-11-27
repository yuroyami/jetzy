package jetzy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.EntryProviderScope
import jetzy.ui.filepicking.ElementPickingScreen
import jetzy.ui.filepicking.PickFilesSubscreenUI
import jetzy.ui.filepicking.PickPhotosSubscreen
import jetzy.ui.filepicking.PickTextSubscreen
import jetzy.ui.filepicking.PickVideosSubscreen
import jetzy.ui.main.MainScreenUI
import jetzy.ui.receive.ReceiveScreenUI
import jetzy.ui.selectpeer.SelectPeerScreenUI

sealed interface Screen {
    val label: String
        get() = ""
    val icon: ImageVector
        get() = Icons.Filled.ElectricBike

    @Composable
    fun UI()

    companion object {
        inline fun <reified T : Screen> EntryProviderScope<out Screen>.nav3Entry() {
            @Suppress("UNCHECKED_CAST")
            (this as EntryProviderScope<T>).entry<T> { screen ->
                screen.UI()
            }
        }
    }

    data object MainScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
    }

    data object FilePickingScreen : Screen {
        @Composable
        override fun UI() = ElementPickingScreen()
    }

    data object ReceiveScreen : Screen {
        @Composable
        override fun UI() = ReceiveScreenUI()
    }

    data object SelectPeerScreen : Screen {
        @Composable
        override fun UI() = SelectPeerScreenUI()
    }

    /** Subscreens */
    data object PickFilesSubscreen : Screen {
        @Composable
        override fun UI() = PickFilesSubscreenUI()
        override val label = "Files"
        override val icon: ImageVector = Icons.Outlined.FileCopy
    }

    data object PickPhotosSubscreen : Screen {
        @Composable
        override fun UI() = PickPhotosSubscreen()
        override val label = "Photos"
        override val icon: ImageVector = Icons.Outlined.Collections
    }

    data object PickVideosSubscreen : Screen {
        @Composable
        override fun UI() = PickVideosSubscreen()
        override val label = "Videos"
        override val icon: ImageVector = Icons.Outlined.Movie
    }

    data object PickTextSubscreen : Screen {
        @Composable
        override fun UI() = PickTextSubscreen()
        override val label = "Text"
        override val icon: ImageVector = Icons.Outlined.FormatSize
    }

}