package com.kitsune.app.ui.components.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Data model for unified collection dialog items.
 */
data class CollectionDialogItem(
    val id: Long,
    val name: String,
    val isSelected: Boolean = false
)

/**
 * REVISION 7.9.1: Unified Collection Dialog Foundation.
 * Provides a common layout for collection-related dialogs.
 */
@Composable
fun MediaCollectionDialogBase(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    emptyMessage: String = "No entries available.",
    isEmpty: Boolean = false,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                content()
            }
        },
        confirmButton = confirmButton ?: {},
        dismissButton = dismissButton
    )
}

/**
 * Unified Multi-Select Dialog (Membership style).
 * Used for Bookmarks management.
 */
@Composable
fun MediaMultiSelectDialog(
    title: String,
    items: List<CollectionDialogItem>,
    onToggle: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Done"
) {
    MediaCollectionDialogBase(
        title = title,
        onDismiss = onDismiss,
        isEmpty = items.isEmpty(),
        emptyMessage = "No collections available. Create one first.",
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmLabel) }
        }
    ) {
        LazyColumn {
            items(items, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    trailingContent = {
                        Checkbox(
                            checked = item.isSelected,
                            onCheckedChange = null // Handled by ListItem click
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(item.id, item.isSelected) }
                )
            }
        }
    }
}

/**
 * Unified Single-Select Dialog (Selection style).
 * Used for adding to Playlists.
 */
@Composable
fun MediaSingleSelectDialog(
    title: String,
    items: List<CollectionDialogItem>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel"
) {
    MediaCollectionDialogBase(
        title = title,
        onDismiss = onDismiss,
        isEmpty = items.isEmpty(),
        emptyMessage = "No entries available. Create one first.",
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    ) {
        LazyColumn {
            items(items, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.id) }
                )
            }
        }
    }
}
