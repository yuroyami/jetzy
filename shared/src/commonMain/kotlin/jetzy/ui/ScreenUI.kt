package jetz.common.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import jetz.common.p2p.P2pCallback
import jetz.common.p2p.P2pHandler
import jetz.common.p2p.P2pUI.P2pChoosePeerPopup
import jetz.common.p2p.P2pUI.P2pInitialPopup
import jetz.common.p2p.P2pUI.P2pQR
import jetz.common.p2p.P2pUI.P2pTransfer
import jetz.common.picking.MultipleFilePicker
import jetz.common.utils.ScreenSizeInfo
import jetz.common.utils.getScreenSizeInfo

lateinit var viewmodel: ComposeViewmodel

lateinit var p2pCallback: P2pCallback
var p2pHandler: P2pHandler? = null

val LocalScreenSize = compositionLocalOf<ScreenSizeInfo> { error("No Screen Size Info provided") }

@Composable
fun ScreenUI() {
    val scope = rememberCoroutineScope()

    viewmodel = viewModel(
        key = "main",
        modelClass = ComposeViewmodel::class,
        factory = viewModelFactory { initializer { ComposeViewmodel() } }
    )

    val snackbarHostState = remember { SnackbarHostState() }

    var showVideoPicker by remember { mutableStateOf(false) }
    MultipleFilePicker(show = showVideoPicker) { file ->
        showVideoPicker = false
        file?.forEach {
            viewmodel.files.add(it)
        }
    }

    CompositionLocalProvider(LocalScreenSize provides getScreenSizeInfo()) {
        Scaffold(
            snackbarHost = {
                viewmodel.snack = snackbarHostState
                SnackbarHost(snackbarHostState)
            },
            topBar = {

            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = CenterHorizontally
                ) {
                    Button(onClick = {
                        showVideoPicker = true
                    }) {
                        Text("Send")
                    }

                    LazyColumn {
                        itemsIndexed(viewmodel.files) { i, f ->
                            Text(f.path)
                        }
                    }
                }
            }
        )

        P2pInitialPopup(visibilityState = remember { viewmodel.p2pInitialPopup })
        P2pQR(visibilityState = remember { viewmodel.p2pQRpopup })
        P2pChoosePeerPopup(visibilityState = remember { viewmodel.p2pChoosePeerPopup })
        P2pTransfer(visibilityState = remember { viewmodel.p2pTransferPopup })
    }
}