package jetzy.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.BrowseGallery
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

sealed interface Screen {
    val label: String
    val icon: ImageVector
        get() = Icons.Filled.ElectricBike

    @Composable
    fun UI()

    data object MainScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "main"
    }

    data object SendScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Send"
    }



    data object SendFilesScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Files"
        override val icon: ImageVector = Icons.Outlined.FileCopy
    }

    data object SendVideosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Videos"
        override val icon: ImageVector = Icons.Outlined.PermMedia
    }

    data object SendPhotosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Photos"
        override val icon: ImageVector = Icons.Outlined.BrowseGallery
    }

    data object SendTextScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Text"
        override val icon: ImageVector = Icons.Outlined.FormatColorText
    }


}