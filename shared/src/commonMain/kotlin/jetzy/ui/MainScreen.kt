package jetzy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import jetzy.p2p.P2pUI.P2pChoosePeerPopup
import jetzy.p2p.P2pUI.P2pInitialPopup
import jetzy.p2p.P2pUI.P2pQR
import jetzy.p2p.P2pUI.P2pTransfer

@Composable
fun MainScreenUI() {
    val viewmodel = LocalViewmodel.current

    val filePicker = rememberFilePickerLauncher(
        type = PickerType.File(), mode = PickerMode.Multiple(),
    ) { files ->
        files?.forEach {
            viewmodel.files.add(it)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().systemBarsPadding(),
        horizontalAlignment = CenterHorizontally
    ) {
        Button(onClick = {
            filePicker.launch()
        }) {
            Text("Send")
        }

        LazyColumn {
            itemsIndexed(viewmodel.files) { i, f ->
                f.path?.let { text -> Text(text) }
            }
        }
    }

    P2pInitialPopup(visibilityState = remember { viewmodel.p2pInitialPopup })
    P2pQR(visibilityState = remember { viewmodel.p2pQRpopup })
    P2pChoosePeerPopup(visibilityState = remember { viewmodel.p2pChoosePeerPopup })
    P2pTransfer(visibilityState = remember { viewmodel.p2pTransferPopup })
}