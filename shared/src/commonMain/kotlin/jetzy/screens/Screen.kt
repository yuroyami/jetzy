package jetzy.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import jetzy.screens.sendscreens.InitiateSendingScreenUI
import jetzy.screens.sendscreens.SendFilesScreenUI
import jetzy.screens.sendscreens.SendPhotosScreenUI
import jetzy.screens.sendscreens.SendTextScreenUI
import jetzy.screens.sendscreens.SendVideosScreenUI

sealed interface Screen {
    val label: String
    val icon: ImageVector
        get() = Icons.Filled.ElectricBike

    @Composable
    fun UI()

    companion object {
        fun NavController.navigateTo(scr: Screen, noReturn: Boolean = false) {
            navigate(scr.label) {
                if (noReturn) popUpTo(0,) { inclusive = true }
            }
        }

        fun NavBackStackEntry?.matches(scr: Screen): Boolean = this?.let { destination.route == scr.label } == true
//
//        inline val NavBackStackEntry?.screen: Screen?
//            get() = when (this?.destination?.route) {
//                MainScreen.label -> MainScreen
//
//            }
    }

    data object MainScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "main"
    }

    data object SendScreen : Screen {
        @Composable
        override fun UI() = SendScreenUI()
        override val label = "Send"
    }

    data object ReceiveScreen : Screen {
        @Composable
        override fun UI() = ReceiveScreenUI()
        override val label = "Receive"
    }

    data object InitiateSendingScreen : Screen {
        @Composable
        override fun UI() = InitiateSendingScreenUI()
        override val label = "InitiateSending"
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
        override val icon: ImageVector =  Icons.Outlined.Collections
    }

    data object SendVideosScreen : Screen {
        @Composable
        override fun UI() = SendVideosScreenUI()
        override val label = "SendVideos"
        override val icon: ImageVector =  Icons.Outlined.Movie
    }

    data object SendTextScreen : Screen {
        @Composable
        override fun UI() = SendTextScreenUI()
        override val label = "SendText"
        override val icon: ImageVector =  Icons.Outlined.FormatSize
    }





}