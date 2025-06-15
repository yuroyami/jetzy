import androidx.compose.ui.window.ComposeUIViewController
import jetzy.screens.ScreenUI
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { ScreenUI() }
