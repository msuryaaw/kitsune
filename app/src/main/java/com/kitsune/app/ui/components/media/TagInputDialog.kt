package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog for entering a new tag.
 * Implements validation rules as per Phase 9.2 decisions.
 */
@Composable
fun TagInputDialog(
    existingTags: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tag") },
        text = {
            Column {
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        errorText = null // Clear error on change
                    },
                    label = { Text("Tag Name") },
                    isError = errorText != null,
                    supportingText = {
                        if (errorText != null) {
                            Text(text = errorText!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = text.trim()
                    when {
                        trimmed.isEmpty() -> {
                            errorText = "Tag cannot be empty"
                        }
                        existingTags.any { it.equals(trimmed, ignoreCase = true) } -> {
                            errorText = "Tag already exists"
                        }
                        trimmed.length > 20 -> {
                            errorText = "Tag is too long (max 20)"
                        }
                        else -> {
                            onAdd(trimmed)
                        }
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
