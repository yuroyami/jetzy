import androidx.compose.ui.window.ComposeUIViewController
import jetzy.ui.ScreenUI
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { ScreenUI() }
