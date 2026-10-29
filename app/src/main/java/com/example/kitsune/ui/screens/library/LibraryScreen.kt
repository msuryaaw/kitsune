package com.example.kitsune.ui.screens.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.kitsune.data.entity.ComicEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe one-shot releaser ensuring an acquired cover [file] is released exactly once.
 */
class OneShotFileReleaser(
    private val file: File,
    private val onRelease: (File) -> Unit
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            onRelease(file)
        }
    }
}

/**
 * UI State for rendering comic cover images.
 */
sealed interface CoverUiState {
    object Loading : CoverUiState
    data class Loaded(val imageBitmap: ImageBitmap) : CoverUiState
    object Unavailable : CoverUiState
    object Error : CoverUiState
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onComicClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val state = uiState) {
            is LibraryUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.RootNotConfigured -> {
                RootNotConfiguredView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.PermissionDenied -> {
                PermissionDeniedView(
                    message = state.message,
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.ComicsDirectoryNotFound -> {
                ComicsDirectoryNotFoundView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.EmptyComicsDirectory -> {
                EmptyComicsDirectoryView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.NoValidChapters -> {
                NoValidChaptersView(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            is LibraryUiState.Success -> {
                LibrarySuccessView(
                    comics = state.comics,
                    gridColumns = state.gridColumns,
                    issuesCount = state.issuesCount,
                    viewModel = viewModel,
                    onComicClick = onComicClick,
                    onGridColumnsChange = { columns -> viewModel.setGridColumns(columns) },
                )
            }
            is LibraryUiState.Error -> {
                LibraryErrorView(
                    message = state.message,
                    onRetry = { viewModel.refreshLibrary() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun RootNotConfiguredView(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = "Root Folder Belum Dipilih",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pilih folder 'Kitsune' di penyimpanan perangkat yang berisi subfolder 'Comics' untuk memuat koleksi komik.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Buka Pengaturan")
            }
        }
    }
}

@Composable
private fun PermissionDeniedView(
    message: String,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = "Akses Folder Terputus",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Izin akses ke folder 'Kitsune' terputus atau dicabut. $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Pulihkan Akses Folder")
            }
        }
    }
}

@Composable
private fun ComicsDirectoryNotFoundView(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = "Folder 'Comics' Tidak Ditemukan",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Subfolder 'Comics' tidak ditemukan di dalam folder root terpilih. Buat direktori 'Comics' dan tempatkan folder komik di dalamnya.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onNavigateToSettings) {
                Text("Periksa Folder Root")
            }
        }
    }
}

@Composable
private fun EmptyComicsDirectoryView(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = "Koleksi Kosong",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Direktori 'Comics' ditemukan tetapi belum berisi folder komik. Tempatkan folder komik di dalam 'Comics/'.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onNavigateToSettings) {
            Text("Periksa Folder Root")
        }
    }
}

@Composable
private fun NoValidChaptersView(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = "Tidak Ada Chapter Valid",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Folder komik ditemukan, tetapi tidak ada chapter CBZ yang dapat dibaca. Pastikan file berformat 'Chapter <angka>.cbz'.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onNavigateToSettings) {
                Text("Periksa Koleksi")
            }
        }
    }
}

@Composable
private fun LibrarySuccessView(
    comics: List<ComicEntity>,
    gridColumns: Int,
    issuesCount: Int,
    viewModel: LibraryViewModel,
    onComicClick: (Long) -> Unit,
    onGridColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (issuesCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Diagnostik Scan",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        text = "$issuesCount masalah scan",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
            GridSelectorRow(
                currentColumns = gridColumns,
                onColumnsSelected = onGridColumnsChange,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns.coerceIn(2, 4)),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                items = comics,
                key = { it.id },
            ) { comic ->
                ComicGridItemCard(
                    comic = comic,
                    viewModel = viewModel,
                    onClick = { onComicClick(comic.id) },
                )
            }
        }
    }
}

@Composable
private fun GridSelectorRow(
    currentColumns: Int,
    onColumnsSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.GridView,
            contentDescription = "Pilihan Kolom Grid",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp),
        )
        listOf(2, 3, 4).forEach { columns ->
            FilterChip(
                selected = currentColumns == columns,
                onClick = { onColumnsSelected(columns) },
                label = { Text("${columns}x") },
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun ComicGridItemCard(
    comic: ComicEntity,
    viewModel: LibraryViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            ComicCoverImage(
                comic = comic,
                viewModel = viewModel,
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = comic.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ComicCoverImage(
    comic: ComicEntity,
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coverState by produceState<CoverUiState>(
        initialValue = CoverUiState.Loading,
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
                value = CoverUiState.Unavailable
                return@produceState
            }

            acquiredFile = file

            // Execute Coil request synchronously on IO thread within this coroutine using shared singleton ImageLoader
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
                value = CoverUiState.Loaded(bitmap.asImageBitmap())
            } else {
                value = CoverUiState.Error
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            value = CoverUiState.Error
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
            .fillMaxWidth()
            .aspectRatio(0.7f),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = coverState) {
            is CoverUiState.Loaded -> {
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Cover untuk ${comic.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                CoverPlaceholderSurface()
            }
        }

        if (comic.isBookmarked) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = "Bookmarked",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun CoverPlaceholderSurface(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Gagal Memuat Library",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Coba Lagi")
            }
        }
    }
}
