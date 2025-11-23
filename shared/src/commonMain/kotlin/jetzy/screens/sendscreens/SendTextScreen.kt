package jetzy.screens.sendscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight.Companion.W700
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jetzy.p2p.ComposeUtils.scheme
import jetzy.screens.adam.LocalViewmodel

@Composable
fun SendTextScreenUI() {
    val viewmodel = LocalViewmodel.current
    val haptic = LocalHapticFeedback.current

    var textAddPopup by remember { mutableStateOf(false ) }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally) {
            Text(
                text = "Add Text strings to send to your peer.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp, top = 28.dp)
            )

            val listIsEmpty by derivedStateOf { viewmodel.texts.isEmpty() }
            Surface(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 92.dp),
                tonalElevation = 28.dp,
                shadowElevation = 3.dp,
                shape = RoundedCornerShape(6.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = if (listIsEmpty) Arrangement.Center else Arrangement.Top
                ) {
                    if (listIsEmpty) {
                        item {
                            Text(
                                text = "No text(s) added yet.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxSize(),
                                style = MaterialTheme.typography.labelMediumEmphasized
                            )
                        }
                    }
                    itemsIndexed(viewmodel.texts) { i, text ->
                        var expanded by remember { mutableStateOf(false ) }
                        TextButton(
                            shape = if (expanded) RectangleShape else ButtonDefaults.textShape,
                            colors = if (expanded) {
                                ButtonDefaults.textButtonColors(containerColor = Color.DarkGray, contentColor = scheme.outlineVariant)
                            } else ButtonDefaults.textButtonColors(),
                            onClick = {
                                expanded = !expanded
                            }
                        ) {
                            Text(
                                text = "${i+1}       $text",
                                maxLines = if (expanded) 25 else 1,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                overflow = TextOverflow.MiddleEllipsis,
                                softWrap = expanded,
                                fontWeight = W700
                            )

                            IconButton(
                                onClick = {
                                    viewmodel.texts.remove(text)
                                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                    viewmodel.snacky("Text was removed from the list.")
                                }
                            ) {
                                Icon(Icons.Filled.Delete, null)
                            }
                        }

                        HorizontalDivider()
                    }
                }
            }
        }

        SmallExtendedFloatingActionButton(
            icon = {
                Icon(Icons.Filled.TextFormat, null)
            },
            onClick = {
                textAddPopup = true
            },
            text = { Text("Add new text") },
            modifier = Modifier.align(BottomEnd).padding(8.dp).padding(bottom = 12.dp),
            expanded = true
        )
    }

    if (textAddPopup) {
        val imeKeyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        var enabled by remember { mutableStateOf(true) }
        var isError by remember { mutableStateOf(false) }
        var txt2add by remember { mutableStateOf("") }

        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.75f),
            onDismissRequest = {
                textAddPopup = false
            },
            dismissButton = {
                TextButton(onClick = { textAddPopup = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (txt2add.isNotBlank()) {
                            viewmodel.texts.add(txt2add)
                            textAddPopup = false
                        } else {
                            isError = true
                        }
                    }
                ) {
                    Text("Add text")
                }
            },
            text = {
                val clipboard = LocalClipboardManager.current
                TextField(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    value = txt2add,
                    trailingIcon = {
                        Column {
                            IconButton(onClick = {
                                clipboard.getText()?.let { clipboardData ->
                                    txt2add = clipboardData.toString()
                                }
                            }) {
                                Icon(imageVector = Icons.Filled.ContentPaste, "", tint = MaterialTheme.colorScheme.primary)
                            }

                            IconButton(onClick = {
                                enabled = !enabled
                                imeKeyboard?.hide()
                                focusManager.clearFocus(true)

                            }) {
                                Icon(
                                    imageVector = if (enabled) Icons.Filled.Done else Icons.Filled.Edit,
                                    "",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(true)
                    }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onValueChange = { txt2add = it },
                    label = {
                        Text("Text to send")
                    },
                    supportingText = {
                        if (isError) Text("Field is empty!")
                    },
                    isError = isError,
                    enabled = enabled
                )
            }
        )
    }
}