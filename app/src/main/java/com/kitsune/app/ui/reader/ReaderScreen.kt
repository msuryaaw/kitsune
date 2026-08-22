package com.kitsune.app.ui.reader

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.kitsune.app.reader.CbzPageModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Layar utama Reader untuk membaca komik.
 * Dioptimasi untuk meminimalkan recomposition (Phase 6.6.4.3) dan transisi mulus (Phase 6.7.4).
 * REVISION 8.3.7: Theme Compliance - Removed hardcoded colors.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // OPTIMIZATION: showControls is isolated to control overlays
    var showControls by remember { mutableStateOf(false) }

    // Stabilize back click
    val currentOnBackClick by rememberUpdatedState(onBackClick)

    // OPTIMIZATION: Reading Progress Force Save on Lifecycle events
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.forceSaveAsync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.forceSaveAsync()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // CONTENT AREA: Isolated from showControls and root currentPage recomposition
        ReaderContent(
            uiState = uiState,
            viewModel = viewModel,
            onBackClick = currentOnBackClick
        )

        // CONTROLS AREA: Isolated overlays
        ReaderControlsOverlay(
            visible = showControls,
            uiState = uiState,
            viewModel = viewModel,
            onBackClick = currentOnBackClick
        )
    }
}

/**
 * Komponen pembungkus konten reader untuk isolasi recomposition.
 */
@Composable
private fun ReaderContent(
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit
) {
    val onNextChapter = remember(viewModel) { { viewModel.navigateToNextChapter() } }
    val onPrevChapter = remember(viewModel) { { viewModel.navigateToPreviousChapter() } }
    
    val onPageChange = remember(viewModel, uiState) {
        { page: Int ->
            val total = (uiState as? ReaderUiState.Success)?.pages?.size ?: 0
            if (total > 0) viewModel.saveProgress(page, total)
        }
    }

    when (val state = uiState) {
        is ReaderUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is ReaderUiState.Empty -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "No pages found in this chapter", color = MaterialTheme.colorScheme.onBackground)
            }
        }
        is ReaderUiState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onBackClick) { Text("Go Back") }
            }
        }
        is ReaderUiState.Success -> {
            // OPTIMIZATION (Phase 6.7.4): Remove key(chapterUri) to allow smooth data updates 
            // without destroying LazyList/Pager state.
            when (state.readingMode) {
                "LTR" -> HorizontalReader(viewModel, state, state.chapterUri, false, onPageChange, onNextChapter, onPrevChapter)
                "RTL" -> HorizontalReader(viewModel, state, state.chapterUri, true, onPageChange, onNextChapter, onPrevChapter)
                else -> VerticalReader(viewModel, state, state.chapterUri, onPageChange, onNextChapter)
            }
        }
    }
}

/**
 * Komponen pembungkus overlay kontrol (Top & Bottom Bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderControlsOverlay(
    visible: Boolean,
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit
) {
    val successState = uiState as? ReaderUiState.Success
    val chapterName = successState?.chapterName ?: ""
    val totalPages = successState?.pages?.size ?: 1

    // Top Bar
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            TopAppBar(
                title = { Text(text = chapterName, style = MaterialTheme.typography.titleMedium, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.statusBarsPadding()
            )
        }
    }

    // Bottom Bar
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        ReaderBottomBar(
            viewModel = viewModel,
            totalPages = totalPages,
            hasNext = viewModel.hasNextChapter(),
            hasPrev = viewModel.hasPreviousChapter(),
            onPageJump = remember(viewModel) { { viewModel.jumpToPage(it) } },
            onNextChapter = remember(viewModel) { { viewModel.navigateToNextChapter() } },
            onPrevChapter = remember(viewModel) { { viewModel.navigateToPreviousChapter() } }
        )
    }
}

@Composable
fun VerticalReader(
    viewModel: ReaderViewModel,
    state: ReaderUiState.Success,
    chapterUri: Uri,
    onPageChange: (Int) -> Unit,
    onNextChapter: () -> Unit
) {
    // OPTIMIZATION: Observe initial page once to initialize state
    val initialPage = remember(chapterUri) { viewModel.currentPage.value }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (initialPage - 1).coerceAtLeast(0)
    )

    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentOnNextChapter by rememberUpdatedState(onNextChapter)

    // Prefetching logic for Vertical Reader (N+1, N+2)
    val context = LocalContext.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                listOf(firstVisible + 1, firstVisible + 2).forEach { targetIndex ->
                    if (targetIndex in state.pages.indices) {
                        val request = ImageRequest.Builder(context)
                            .data(CbzPageModel(chapterUri, state.pages[targetIndex].entryPath))
                            .precision(coil.size.Precision.INEXACT)
                            .build()
                        context.imageLoader.enqueue(request)
                    }
                }
            }
    }

    // OPTIMIZATION: Handle Jumps via Flow instead of root-level state recomposition
    LaunchedEffect(viewModel, chapterUri) {
        viewModel.currentPage
            .collect { page ->
                val targetIndex = (page - 1).coerceAtLeast(0)
                // Detect jump: if the gap is significant, scroll to target
                if (abs(listState.firstVisibleItemIndex - targetIndex) > 1) {
                    listState.scrollToItem(targetIndex)
                }
            }
    }

    // Report scroll position
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { it + 1 }
            .distinctUntilChanged()
            .collect { 
                if (it <= state.pages.size) {
                    currentOnPageChange(it)
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = state.pages,
            key = { _, page -> page.entryPath }
        ) { _, page ->
            ReaderPage(chapterUri = chapterUri, entryPath = page.entryPath, isVertical = true)
        }
        
        if (viewModel.hasNextChapter()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clickable { currentOnNextChapter() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Tap to load Next Chapter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun HorizontalReader(
    viewModel: ReaderViewModel,
    state: ReaderUiState.Success,
    chapterUri: Uri,
    isRtl: Boolean,
    onPageChange: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit
) {
    val hasNext = viewModel.hasNextChapter()
    val hasPrev = viewModel.hasPreviousChapter()
    val actualPageCount = state.pages.size
    val totalCount = actualPageCount + (if (hasPrev) 1 else 0) + (if (hasNext) 1 else 0)
    
    // OPTIMIZATION: Initial page initialization
    val initialPageValue = remember(chapterUri) { viewModel.currentPage.value }
    val startIndex = if (hasPrev) initialPageValue else initialPageValue - 1

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, totalCount - 1),
        pageCount = { totalCount }
    )

    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentOnNextChapter by rememberUpdatedState(onNextChapter)
    val currentOnPrevChapter by rememberUpdatedState(onPrevChapter)

    // Prefetching logic for Horizontal Reader (N+1, N+2, N-1)
    val context = LocalContext.current
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { currentPage ->
                listOf(currentPage + 1, currentPage + 2, currentPage - 1).forEach { targetIndex ->
                    val pageIdx = if (hasPrev) targetIndex - 1 else targetIndex
                    if (pageIdx in state.pages.indices) {
                        val request = ImageRequest.Builder(context)
                            .data(CbzPageModel(chapterUri, state.pages[pageIdx].entryPath))
                            .precision(coil.size.Precision.INEXACT)
                            .build()
                        context.imageLoader.enqueue(request)
                    }
                }
            }
    }

    // OPTIMIZATION: Handle Jumps via Flow
    LaunchedEffect(viewModel, chapterUri) {
        viewModel.currentPage
            .collect { page ->
                val targetIndex = if (hasPrev) page else page - 1
                if (pagerState.currentPage != targetIndex) {
                    pagerState.scrollToPage(targetIndex.coerceIn(0, totalCount - 1))
                }
            }
    }

    LaunchedEffect(pagerState.currentPage) {
        val currentIdx = pagerState.currentPage
        when {
            hasPrev && currentIdx == 0 -> currentOnPrevChapter()
            hasNext && currentIdx == totalCount - 1 -> currentOnNextChapter()
            else -> {
                val realPage = if (hasPrev) currentIdx else currentIdx + 1
                if (realPage in 1..actualPageCount) {
                    currentOnPageChange(realPage)
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 2
        ) { index ->
            when {
                hasPrev && index == 0 -> Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                hasNext && index == totalCount - 1 -> Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                else -> {
                    val pageIdx = if (hasPrev) index - 1 else index
                    if (pageIdx in state.pages.indices) {
                        ReaderPage(
                            chapterUri = chapterUri,
                            entryPath = state.pages[pageIdx].entryPath,
                            isVertical = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderPage(
    chapterUri: Uri,
    entryPath: String,
    isVertical: Boolean
) {
    val context = LocalContext.current
    val imageRequest = remember(chapterUri, entryPath) {
        ImageRequest.Builder(context)
            .data(CbzPageModel(chapterUri, entryPath))
            .crossfade(true)
            .precision(coil.size.Precision.INEXACT)
            .allowHardware(false) // FIX: Disable Hardware Bitmap for mutation safety (Poin 1.2)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = if (isVertical) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.fillMaxSize(),
        contentScale = if (isVertical) ContentScale.FillWidth else ContentScale.Fit
    )
}

@Composable
fun ReaderBottomBar(
    viewModel: ReaderViewModel,
    totalPages: Int,
    hasNext: Boolean,
    hasPrev: Boolean,
    onPageJump: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val currentPage by viewModel.currentPage.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val currentMode = (uiState as? ReaderUiState.Success)?.readingMode ?: "Vertical"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            // REVISION 11.4.2: Reading Mode Selector
            ReadingModeSelector(
                currentMode = currentMode,
                onModeChange = { viewModel.updateReadingMode(it) }
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Isolated Page Position UI
            PagePositionControls(currentPage, totalPages, onPageJump)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev Chapter")
                }
                Text(
                    text = "Chapter Navigation", 
                    style = MaterialTheme.typography.labelMedium, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onNextChapter, enabled = hasNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Chapter")
                }
            }
        }
    }
}

/**
 * Komponen terisolasi untuk pemilihan Mode Membaca.
 * REVISION 11.4.3: Added Radio Chips for Reading Mode selection.
 */
@Composable
private fun ReadingModeSelector(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    val modes = listOf("Vertical", "LTR", "RTL")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                val isSelected = mode == currentMode
                FilterChip(
                    selected = isSelected,
                    onClick = { onModeChange(mode) },
                    label = { Text(text = mode, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}

/**
 * Komponen terisolasi untuk Slider dan Text posisi halaman.
 * Hanya komponen ini yang recompose saat halaman berubah.
 */
@Composable
private fun PagePositionControls(
    currentPage: Int,
    totalPages: Int,
    onPageJump: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$currentPage / $totalPages",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
        Slider(
            value = currentPage.toFloat(),
            onValueChange = { onPageJump(it.toInt()) },
            valueRange = 1f..totalPages.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
