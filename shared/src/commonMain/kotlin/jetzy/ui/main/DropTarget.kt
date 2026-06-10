package jetzy.ui.main

import androidx.compose.ui.Modifier
import jetzy.models.JetzyElement

/**
 * Makes a composable accept files dropped from outside the app. Only desktop has an external
 * drag-and-drop story (drop from Finder/Explorer onto the window — its native equivalent of the
 * Android share sheet); the other platforms return the modifier unchanged.
 */
expect fun Modifier.jetzyDropTarget(onDropped: (List<JetzyElement>) -> Unit): Modifier
