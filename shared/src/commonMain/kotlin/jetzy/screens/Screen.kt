package jetzy.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.EntryProviderScope
import jetzy.screens.main.MainScreenUI
import jetzy.screens.receive.ReceiveScreenUI
import jetzy.screens.send.SendScreenUI
import jetzy.screens.sendscreens.InitiateSendingScreenUI
import jetzy.screens.sendscreens.SendFilesScreenUI
import jetzy.screens.sendscreens.SendPhotosScreenUI
import jetzy.screens.sendscreens.SendTextScreenUI
import jetzy.screens.sendscreens.SendVideosScreenUI

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

    data object SendScreen : Screen {
        @Composable
        override fun UI() = SendScreenUI()
    }

    data object ReceiveScreen : Screen {
        @Composable
        override fun UI() = ReceiveScreenUI()
    }

    data object InitiateSendingScreen : Screen {
        @Composable
        override fun UI() = InitiateSendingScreenUI()
    }

    data object SendFilesScreen : Screen {
        @Composable
        override fun UI() = SendFilesScreenUI()
        override val label = "SendFiles"
        override val icon: ImageVector = Icons.Outlined.FileCopy
    }

    data object SendPhotosScreen : Screen {
        @Composable
        override fun UI() = SendPhotosScreenUI()
        override val label = "SendPhotos"
        override val icon: ImageVector = Icons.Outlined.Collections
    }

    data object SendVideosScreen : Screen {
        @Composable
        override fun UI() = SendVideosScreenUI()
        override val label = "SendVideos"
        override val icon: ImageVector = Icons.Outlined.Movie
    }

    data object SendTextScreen : Screen {
        @Composable
        override fun UI() = SendTextScreenUI()
        override val label = "SendText"
        override val icon: ImageVector = Icons.Outlined.FormatSize
    }


}