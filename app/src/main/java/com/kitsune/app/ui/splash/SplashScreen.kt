package com.kitsune.app.ui.splash

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitsune.app.core.StorageHelper

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    storageHelper: StorageHelper,
    onNavigateToMain: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            storageHelper.persistUriPermission(it)
            viewModel.onRootFolderSelected(it.toString())
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is SplashUiState.Authenticated) {
            onNavigateToMain()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState) {
                is SplashUiState.Loading -> CircularProgressIndicator()
                is SplashUiState.NeedsSetup -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Welcome to Kitsune",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { launcher.launch(null) }) {
                            Text("Select Library Folder")
                        }
                    }
                }
                is SplashUiState.Error -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = (uiState as SplashUiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { launcher.launch(null) }) {
                            Text("Try Again")
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
