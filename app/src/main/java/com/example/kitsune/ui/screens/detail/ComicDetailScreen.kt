package com.example.kitsune.ui.screens.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.ui.components.KitsunePrimaryButton
import com.example.kitsune.ui.components.KitsuneSectionHeading
import com.example.kitsune.ui.screens.library.CoverUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

sealed interface CoverDetailUiState {
    object Loading : CoverDetailUiState
    data class Loaded(val imageBitmap: ImageBitmap) : CoverDetailUiState
    object Unavailable : CoverDetailUiState
    object Error : CoverDetailUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    viewModel: ComicDetailViewModel,
    onBackClick: () -> Unit,
    onChapterClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCollectionPicker by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ComicDetailEvent.NavigateToReader -> {
                    onChapterClick(event.chapterId)
                }
                is ComicDetailEvent.ShowFeedback -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showCollectionPicker && uiState is ComicDetailUiState.Success) {
        val collections by viewModel.observeAllBookmarkCollections().collectAsStateWithLifecycle(initialValue = emptyList())
        val comicCollections by viewModel.observeCollectionsForComic().collectAsStateWithLifecycle(initialValue = emptyList())

        BookmarkCollectionPickerDialog(
            allCollections = collections,
            currentComicCollections = comicCollections,
            onConfirm = { selectedIds ->
                viewModel.updateComicCollections(selectedIds)
                showCollectionPicker = false
            },
            onDismiss = { showCollectionPicker = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Comic Detail") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val state = uiState
                    if (state is ComicDetailUiState.Success) {
                        val isBookmarked = state.comic.isBookmarked
                        IconButton(onClick = { showCollectionPicker = true }) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isBookmarked) "Manage Bookmark Collections" else "Add to Bookmark Collection",
                                tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (val state = uiState) {
                is ComicDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ComicDetailUiState.ComicNotFound -> {
                    ComicNotFoundView(
                        onBackClick = onBackClick,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ComicDetailUiState.EmptyChapters -> {
                    EmptyChaptersView(
                        onBackClick = onBackClick,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ComicDetailUiState.Success -> {
                    ComicDetailSuccessView(
                        comic = state.comic,
                        chapters = state.chapters,
                        progress = state.progress,
                        viewModel = viewModel,
                        onChapterSelect = { chapterId ->
                            viewModel.onChapterSelected(chapterId)
                        },
                        onManageCollections = {
                            showCollectionPicker = true
                        }
                    )
                }
                is ComicDetailUiState.Error -> {
                    ComicDetailErrorView(
                        message = state.message,
                        onBackClick = onBackClick,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicDetailSuccessView(
    comic: ComicEntity,
    chapters: List<ChapterEntity>,
    progress: ReadingProgressEntity?,
    viewModel: ComicDetailViewModel,
    onChapterSelect: (Long) -> Unit,
    onManageCollections: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ComicHeaderCard(
                comic = comic,
                chapters = chapters,
                progress = progress,
                viewModel = viewModel,
                onChapterSelect = onChapterSelect,
                onManageCollections = onManageCollections
            )
        }

        item {
            KitsuneSectionHeading("Chapters (${chapters.size})")
        }

        items(
            items = chapters,
            key = { it.id }
        ) { chapter ->
            ChapterItemCard(
                chapter = chapter,
                onClick = { onChapterSelect(chapter.id) }
            )
        }
    }
}

@Composable
private fun ComicHeaderCard(
    comic: ComicEntity,
    chapters: List<ChapterEntity>,
    progress: ReadingProgressEntity?,
    viewModel: ComicDetailViewModel,
    onChapterSelect: (Long) -> Unit,
    onManageCollections: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(0.70f)
                ) {
                    ComicCoverDetailImage(
                        comic = comic,
                        viewModel = viewModel
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = comic.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val hasMetadata = !comic.type.isNullOrBlank() || !comic.language.isNullOrBlank() || !comic.author.isNullOrBlank()
                    if (hasMetadata) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!comic.type.isNullOrBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Text(
                                        text = comic.type ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (!comic.language.isNullOrBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Text(
                                        text = comic.language ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (!comic.author.isNullOrBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ) {
                                    Text(
                                        text = comic.author ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "${chapters.size} Chapters · Local",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = onManageCollections,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Bookmark Collections")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Reading Action CTA Button
            val lastChapterId = progress?.lastReadChapterId
            if (progress != null && lastChapterId != null && lastChapterId > 0L) {
                KitsunePrimaryButton(
                    text = "Continue · Chapter ${progress.lastReadChapterNumber}",
                    onClick = { onChapterSelect(lastChapterId) },
                    leadingIcon = Icons.Default.PlayArrow
                )
            } else {
                KitsunePrimaryButton(
                    text = "Start Reading",
                    onClick = {
                        chapters.firstOrNull()?.let { onChapterSelect(it.id) }
                    },
                    enabled = chapters.isNotEmpty(),
                    leadingIcon = Icons.Default.PlayArrow
                )
            }
        }
    }
}

@Composable
private fun ChapterItemCard(
    chapter: ChapterEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.displayName.removeSuffixCaseInsensitive(".cbz"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapter.pageCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${chapter.pageCount} pages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open Chapter",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ComicCoverDetailImage(
    comic: ComicEntity,
    viewModel: ComicDetailViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverState by produceState<CoverDetailUiState>(
        initialValue = CoverDetailUiState.Loading,
        key1 = comic.id,
        key2 = comic.coverEntryPath,
        key3 = comic.lastScannedAt
    ) {
        var acquiredFile: File? = null
        try {
            val cacheResult = viewModel.getCoverFile(comic.id)
            val file = when (cacheResult) {
                is CoverCacheResult.Hit -> cacheResult.file
                is CoverCacheResult.Success -> cacheResult.file
                else -> null
            }

            if (file == null || !file.isFile || !file.exists()) {
                value = CoverDetailUiState.Unavailable
                return@produceState
            }

            acquiredFile = file

            val request = ImageRequest.Builder(context)
                .data(file)
                .size(300, 420)
                .build()

            val imageLoader = context.imageLoader
            val result = imageLoader.execute(request)

            if (result is SuccessResult) {
                val drawable = result.drawable
                val bitmap = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap
                    else -> {
                        val width = drawable.intrinsicWidth.coerceAtLeast(1)
                        val height = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                value = CoverDetailUiState.Loaded(bitmap.asImageBitmap())
            } else {
                value = CoverDetailUiState.Error
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            value = CoverDetailUiState.Error
        } finally {
            acquiredFile?.let { file ->
                withContext(NonCancellable) {
                    viewModel.releaseCoverFile(file)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        when (val state = coverState) {
            is CoverDetailUiState.Loaded -> {
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Cover for ${comic.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicNotFoundView(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Comic Not Found",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The selected comic was not found or was removed from the library directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBackClick) {
                Text("Back to Library")
            }
        }
    }
}

@Composable
private fun EmptyChaptersView(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "No Valid Chapters Available",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This comic folder does not contain readable 'Chapter <number>.cbz' files yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onBackClick) {
                Text("Back to Library")
            }
        }
    }
}

@Composable
private fun ComicDetailErrorView(
    message: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Failed to Load Comic Detail",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBackClick) {
                Text("Back to Library")
            }
        }
    }
}

fun String.removeSuffixCaseInsensitive(suffix: String): String {
    return if (this.endsWith(suffix, ignoreCase = true)) {
        this.substring(0, this.length - suffix.length)
    } else {
        this
    }
}

@Composable
private fun BookmarkCollectionPickerDialog(
    allCollections: List<com.example.kitsune.data.entity.BookmarkEntity>,
    currentComicCollections: List<com.example.kitsune.data.entity.BookmarkEntity>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember(currentComicCollections) {
        mutableStateOf(currentComicCollections.map { it.id }.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Bookmark Collections") },
        text = {
            if (allCollections.isEmpty()) {
                Text(
                    text = "No Bookmark collections created yet. Create a collection on the Bookmarks page first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allCollections.forEach { collection ->
                        val isSelected = selectedIds.contains(collection.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (isSelected) {
                                        selectedIds - collection.id
                                    } else {
                                        selectedIds + collection.id
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        selectedIds + collection.id
                                    } else {
                                        selectedIds - collection.id
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = collection.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
