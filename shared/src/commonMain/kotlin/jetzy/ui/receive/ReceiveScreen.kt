package jetzy.ui.receive

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import jetzy.ui.Screen
import jetzy.ui.adam.LocalViewmodel


@Composable
fun ReceiveScreenUI() {
    val haptic = LocalHapticFeedback.current
    val viewmodel = LocalViewmodel.current

    val sendScreens by derivedStateOf {
        buildList {
            add(Screen.PickFilesSubscreen)
            add(Screen.PickPhotosSubscreen)
            add(Screen.PickVideosSubscreen)
            add(Screen.PickTextSubscreen)
        }
    }

    val pagerState = rememberPagerState { sendScreens.size }

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                NavigationBar {
                    sendScreens.forEach { screen ->
                        NavigationBarItem(
                            selected = true,
                            icon = {
                                Icon(screen.icon, null)

                            },
                            label = { Text(screen.label) },
                            onClick = {
                                //navigator.navigate(screen.label)
                            }
                        )
                    }
                }
            }
        }
    ) { pv ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pv),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(pv),
                userScrollEnabled = false
            ) { pageIndex ->
            }
        }
    }
}