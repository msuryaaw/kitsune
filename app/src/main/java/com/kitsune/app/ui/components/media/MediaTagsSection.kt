package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable layout for displaying and managing media tags.
 * Uses FlowRow to wrap chips to the next line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaTagsSection(
    tags: List<String>,
    isEditMode: Boolean,
    onAddClick: () -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { tag ->
            MediaTagChip(
                label = tag,
                isEditMode = isEditMode,
                onClick = { /* Could navigate to tag search in future */ },
                onRemoveClick = { onRemoveTag(tag) }
            )
        }
        
        MediaTagChip(
            label = "Add",
            isAddChip = true,
            onClick = onAddClick
        )
    }
}
