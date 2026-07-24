package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kitsune.app.ui.library.SearchTopAppBar

/**
 * Komponen Scaffold Terpadu untuk layar Library.
 * Mengelola struktur dasar UI termasuk TopAppBar (Normal & Search), Snackbar,
 * dan slot untuk konten Loading, Empty, serta Grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScaffold(
    title: String,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onBackClick: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    selectionTopBar: (@Composable () -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { onSearchActiveChange(true) }) {
            Icon(Icons.Default.Search, contentDescription = "Search")
        }
    },
    content: @Composable BoxScope.() -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (selectionTopBar != null) {
                selectionTopBar()
            } else if (isSearchActive) {
                SearchTopAppBar(
                    query = searchQuery,
                    onQueryChange = onQueryChange,
                    onCloseClick = {
                        onSearchActiveChange(false)
                        onQueryChange("")
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (onBackClick != null) {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = topBarActions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            content()
        }
    }
}
