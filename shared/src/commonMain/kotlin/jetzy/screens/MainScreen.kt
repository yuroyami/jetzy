package jetzy.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Absolute.Center
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Android
import compose.icons.fontawesomeicons.brands.Apple
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.brands.Linux
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType

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

    Scaffold { pv ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pv),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            key(1) {
                Text(
                    text = "Welcome to Jetzy!",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )

                Text(
                    text = "Send and receive across different platforms with ease.",
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                )
            }

            Spacer(Modifier.height(8.dp))

            key(2) {
                Text("Choose an operation:")

                Row {
                    OutlinedCard(
                        modifier = Modifier.width(148.dp).height(96.dp).padding(12.dp),
                        //border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.SendToMobile, null)
                            Text("Send")
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.width(148.dp).height(96.dp).padding(12.dp),
                        //border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.CallReceived, null)
                            Text("Receive")
                        }
                    }
                }
            }

            key(3) {
                Text("Your friend has:")

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedCard(
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        //border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Column(modifier = Modifier.size(64.dp), verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = FontAwesomeIcons.Brands.Apple, null)
                            Text("iOS")
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        //border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Column(modifier = Modifier.size(64.dp), verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = FontAwesomeIcons.Brands.Android, null)
                            Text("Android")
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        //border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Column(modifier = Modifier.size(64.dp), verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = FontAwesomeIcons.Brands.Linux, null)
                            Text("PC")
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        //border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary),
                        //shape = RoundedCornerShape(16.dp),
                        onClick = {

                        }
                    ) {
                        Column(modifier = Modifier.size(64.dp), verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = FontAwesomeIcons.Brands.Chrome, null)
                            Text("Browser (Web)")
                        }
                    }
                }
            }
        }
    }


    /*P2pInitialPopup(visibilityState = remember { viewmodel.p2pInitialPopup })
    P2pQR(visibilityState = remember { viewmodel.p2pQRpopup })
    P2pChoosePeerPopup(visibilityState = remember { viewmodel.p2pChoosePeerPopup })
    P2pTransfer(visibilityState = remember { viewmodel.p2pTransferPopup })*/
}