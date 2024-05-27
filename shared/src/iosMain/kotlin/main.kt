import androidx.compose.ui.window.ComposeUIViewController
import jetz.common.ui.ScreenUI
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { ScreenUI() }
