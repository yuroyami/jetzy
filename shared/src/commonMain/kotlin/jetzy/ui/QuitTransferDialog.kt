package jetzy.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import jetzy.shared.generated.resources.Res
import jetzy.shared.generated.resources.keep_transferring
import jetzy.shared.generated.resources.quit_anyway
import jetzy.shared.generated.resources.quit_transfer_body
import jetzy.shared.generated.resources.quit_transfer_title
import org.jetbrains.compose.resources.stringResource

/**
 * "Quit during an active transfer?" confirmation. Lives in shared (not the desktop shell)
 * because the generated string resources are module-internal — and any shell that can close
 * a window mid-transfer needs the same dialog.
 */
@Composable
fun QuitTransferDialog(onQuit: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(Res.string.quit_transfer_title)) },
        text = { Text(stringResource(Res.string.quit_transfer_body)) },
        confirmButton = {
            TextButton(onClick = onQuit) { Text(stringResource(Res.string.quit_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(Res.string.keep_transferring)) }
        },
    )
}
