package jetzy.ui

import androidx.compose.runtime.Composable

sealed interface Screen {
    val label: String

    @Composable
    fun UI()

    data object MainScreen : Screen {
        @Composable
        override fun UI() = MainScreenUI()
        override val label = "MainScreen"
    }

}