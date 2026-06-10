package jetzy.ui.main

import androidx.compose.ui.Modifier
import jetzy.models.JetzyElement

/** No external drag-and-drop on this platform — the share sheet / pickers are the intake. */
actual fun Modifier.jetzyDropTarget(onDropped: (List<JetzyElement>) -> Unit): Modifier = this
