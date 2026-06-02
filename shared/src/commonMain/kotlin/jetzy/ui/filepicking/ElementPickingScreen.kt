package jetzy.ui.filepicking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import jetzy.ui.Screen
import kotlinx.coroutines.launch

private val sendScreens = listOf(
    Screen.PickFilesSubscreen,
    Screen.PickPhotosSubscreen,
    Screen.PickVideosSubscreen,
    Screen.PickTextSubscreen,
)

@Composable
fun ElementPickingScreen() {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val pagerState = rememberPagerState { sendScreens.size }

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                NavigationBar {
                    sendScreens.forEachIndexed { i, screen ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == i,
                            icon = {
                                Icon(screen.icon, null)

                            },
                            label = { Text(screen.label) },
                            onClick = {
                                scope.launch { pagerState.scrollToPage(i) }
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        )
                    }
                }
            }
        },
    ) { pv ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(bottom = pv.calculateBottomPadding()),
            userScrollEnabled = false
        ) { pageIndex ->
            sendScreens[pageIndex].UI()
        }
    }
}