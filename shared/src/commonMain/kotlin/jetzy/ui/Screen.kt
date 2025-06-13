package jetzy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.outlined.BrowseGallery
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderZip
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
        override val label = "Send files"
        override val icon: ImageVector = Icons.Outlined.FileCopy
    }

    data object SendVideosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Send videos"
        override val icon: ImageVector = Icons.Outlined.PermMedia
    }

    data object SendPhotosScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Send photos"
        override val icon: ImageVector = Icons.Outlined.BrowseGallery
    }

    data object SendFoldersScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Send folders"
        override val icon: ImageVector = Icons.Outlined.FolderZip
    }

    data object SendTextScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "Send text"
        override val icon: ImageVector = Icons.Outlined.FormatColorText
    }


}