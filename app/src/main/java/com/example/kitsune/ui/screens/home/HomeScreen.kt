package com.example.kitsune.ui.screens.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.ui.components.KitsuneEmptyState
import com.example.kitsune.ui.components.KitsunePrimaryButton
import com.example.kitsune.ui.components.KitsuneSectionHeading
import com.example.kitsune.ui.screens.library.CoverUiState
import com.example.kitsune.ui.theme.ComponentShapes
import com.example.kitsune.ui.theme.Spacing
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

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenLibraryClick: () -> Unit,
    onOpenHistoryClick: () -> Unit,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is HomeUiState.Error -> {
                HomeErrorView(
                    message = state.message,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is HomeUiState.Success -> {
                HomeSuccessContent(
                    state = state,
                    viewModel = viewModel,
                    onOpenLibraryClick = onOpenLibraryClick,
                    onOpenHistoryClick = onOpenHistoryClick,
                    onContinueReadingClick = onContinueReadingClick
                )
            }
        }
    }
}

@Composable
private fun HomeSuccessContent(
    state: HomeUiState.Success,
    viewModel: HomeViewModel,
    onOpenLibraryClick: () -> Unit,
    onOpenHistoryClick: () -> Unit,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = Spacing.screenHorizontalPadding,
                vertical = Spacing.screenVerticalPadding
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge)
    ) {
        // App Header
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
            Text(
                text = "Kitsune",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Your offline manga library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Last Read Section
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            KitsuneSectionHeading(title = "Last Read")

            val lastRead = state.lastReadItem
            if (lastRead == null) {
                HomeLastReadEmptyCard(onOpenLibraryClick = onOpenLibraryClick)
            } else {
                HomeLastReadPopulatedCard(
                    lastRead = lastRead,
                    viewModel = viewModel,
                    onContinueReadingClick = onContinueReadingClick
                )
            }
        }

        // Shortcuts Section
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            KitsuneSectionHeading(title = "Shortcuts")

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                ShortcutRow(
                    title = "Library",
                    subtitle = "Your local comics",
                    trailingInfo = "${state.libraryCount} local manga",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    onClick = onOpenLibraryClick
                )

                val historyInfo = if (state.historyCount > 0) {
                    "${state.historyCount} recently read manga"
                } else {
                    "No chapters read yet"
                }

                ShortcutRow(
                    title = "History",
                    subtitle = "Your reading history",
                    trailingInfo = historyInfo,
                    icon = Icons.Default.History,
                    onClick = onOpenHistoryClick
                )
            }
        }
    }
}

@Composable
private fun HomeLastReadEmptyCard(
    onOpenLibraryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ComponentShapes.cardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        KitsuneEmptyState(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Your first chapter awaits",
            message = "Explore your Library to start reading your favorite manga.",
            actionText = "Open Library",
            onAction = onOpenLibraryClick,
            modifier = Modifier.padding(Spacing.large)
        )
    }
}

@Composable
private fun HomeLastReadPopulatedCard(
    lastRead: LastReadInfo,
    viewModel: HomeViewModel,
    onContinueReadingClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = lastRead.progress
    val pageDisplay = progress.pageIndex + 1
    val totalPages = lastRead.totalPages
    val chapterId = progress.lastReadChapterId ?: 0L

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ComponentShapes.cardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Cover Image
                Box(
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(0.70f)
                ) {
                    ComicCoverHomeImage(
                        comic = lastRead.comic,
                        viewModel = viewModel
                    )
                }

                // Metadata Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                ) {
                    Text(
                        text = "PICK UP YOUR STORY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = lastRead.comic.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!lastRead.comic.author.isNullOrBlank()) {
                        Text(
                            text = lastRead.comic.author ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val chapterName = lastRead.chapter?.displayName?.removeSuffix(".cbz")
                        ?: "Chapter ${progress.lastReadChapterNumber}"

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = chapterName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (totalPages > 0) {
                        val progressFloat = (pageDisplay.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progressFloat },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Page $pageDisplay / $totalPages (${(progressFloat * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            KitsunePrimaryButton(
                text = "Continue Reading",
                onClick = {
                    if (chapterId > 0L) {
                        onContinueReadingClick(chapterId)
                    }
                },
                leadingIcon = Icons.Default.PlayArrow
            )
        }
    }
}

@Composable
private fun ShortcutRow(
    title: String,
    subtitle: String,
    trailingInfo: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = ComponentShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = ComponentShapes.card,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = trailingInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ComicCoverHomeImage(
    comic: ComicEntity,
    viewModel: HomeViewModel,
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
            .clip(ComponentShapes.coverTile),
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
private fun HomeErrorView(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ComponentShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                text = "Failed to load Home data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}