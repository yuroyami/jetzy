package jetzy.ui.sendscreens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jetzy.utils.ComposeUtils.JetzyText
import jetzy.utils.ComposeUtils.font
import jetzy.ui.adam.LocalViewmodel
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.notosans
import jetzy.theme.sdp
import jetzy.theme.ssp

@Composable
fun InitiateSendingScreenUI() {
    val viewmodel = LocalViewmodel.current

    LaunchedEffect(null) {

    }

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        JetzyText(
            text = "Select your Peer",
            size = 14.ssp,
            modifier = Modifier.padding(6.dp)
        )

        Text("Make sure your peer is receiving")

        Surface(
            modifier = Modifier.weight(1f).padding(16.dp),
            tonalElevation = 28.dp,
            shadowElevation = 3.dp,
            shape = RoundedCornerShape(6.dp)
        ) {
            val platform by viewmodel.currentPeerPlatform.collectAsState()
            if (platform != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    repeat(3) {
                        item {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {

                                }
                            ) {
                                Icon(
                                    imageVector = platform!!.icon,
                                    contentDescription = null,
                                    tint = platform!!.brandColor,
                                    modifier = Modifier.size(18.sdp).padding(horizontal = 4.dp)
                                )

                                Text(text = "Peer $it", modifier = Modifier.weight(1f), fontFamily = Res.font.notosans.font)
                            }

                            HorizontalDivider()
                        }
                    }

                    items(viewmodel.p2pPeers) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {

                            }
                        ) {
                            Icon(
                                imageVector = platform!!.icon,
                                contentDescription = null,
                                tint = platform!!.brandColor,
                                modifier = Modifier.size(18.sdp).padding(horizontal = 4.dp)
                            )

                            Text(text = "Peer $it", modifier = Modifier.weight(1f), fontFamily = Res.font.notosans.font)
                        }
                    }
                }
            }
        }
    }
}
