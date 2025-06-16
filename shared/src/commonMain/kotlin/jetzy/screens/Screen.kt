package jetzy.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import jetzy.screens.sendscreens.SendFilesScreenUI

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



    data object SendFilesScreen : Screen {
        @Composable
        override fun UI() = SendFilesScreenUI()
        override val label = "Files"
        override val icon: ImageVector = Icons.Outlined.FileCopy
    }

    data object SendVideosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Videos"
        override val icon: ImageVector =  Icons.Outlined.Movie
    }

    data object SendPhotosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Photos"
        override val icon: ImageVector =  Icons.Outlined.Collections
    }

    data object SendTextScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Text"
        override val icon: ImageVector =  Icons.Outlined.FormatSize
    }


}