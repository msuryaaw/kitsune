package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable Tag Chip for Kitsune.
 * Supports two modes: Tag and Add.
 */
@Composable
fun MediaTagChip(
    label: String,
    isAddChip: Boolean = false,
    isEditMode: Boolean = false,
    onClick: () -> Unit,
    onRemoveClick: (() -> Unit)? = null
) {
    if (isAddChip) {
        AssistChip(
            onClick = onClick,
            label = { Text("+") },
            leadingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    } else {
        InputChip(
            selected = false,
            onClick = onClick,
            label = { Text(label) },
            trailingIcon = if (isEditMode) {
                {
                    IconButton(
                        onClick = { onRemoveClick?.invoke() },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove tag",
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            } else null,
            colors = InputChipDefaults.inputChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = null
        )
    }
}
