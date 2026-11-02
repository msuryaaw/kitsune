package com.example.kitsune.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.core.RootFolderState
import com.example.kitsune.core.SafStorageHelper
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    rootFolderPreferences: RootFolderPreferences,
    settingsRepository: SettingsRepository? = null,
    libraryRepository: LibraryRepository? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folderState by remember { mutableStateOf<RootFolderState>(RootFolderState.NotConfigured) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var showReleaseRootDialog by remember { mutableStateOf(false) }

    val settingsState by (settingsRepository?.observeSettings() ?: flowOf(com.example.kitsune.data.entity.SettingsEntity()))
        .collectAsStateWithLifecycle(initialValue = com.example.kitsune.data.entity.SettingsEntity())

    fun refreshStatus() {
        val uriStr = rootFolderPreferences.getRootUriString()
        if (uriStr == null) {
            folderState = RootFolderState.NotConfigured
        } else {
            val parsedUri = try {
                Uri.parse(uriStr)
            } catch (_: Exception) {
                null
            }

            if (parsedUri == null) {
                folderState = RootFolderState.Inaccessible(uriStr, "Format URI tidak valid.")
            } else {
                folderState = SafStorageHelper.checkRootAccess(context, parsedUri)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    // Custom contract to capture both Uri and Intent flags from ActivityResult
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        operationError = null
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val pickedUri = intent?.data

            if (pickedUri == null) {
                operationError = "Gagal membaca folder yang dipilih: URI tidak tersedia."
                return@rememberLauncherForActivityResult
            }

            // Attempt to persist permissions BEFORE overwriting existing root
            val takeResult = SafStorageHelper.takePersistablePermissions(
                resolver = context.contentResolver,
                uri = pickedUri,
                resultIntent = intent
            )

            takeResult.onSuccess {
                // Verify that permission is actually held
                val verifiedAccess = SafStorageHelper.checkRootAccess(context, pickedUri)
                if (verifiedAccess is RootFolderState.Ready) {
                    rootFolderPreferences.setRootUriString(pickedUri.toString())
                    folderState = verifiedAccess
                    // Ensure "Comics" directory exists and trigger initial scan
                    scope.launch {
                        try {
                            libraryRepository?.ensureComicsDirectory()
                            libraryRepository?.syncLibrary(pickedUri.toString())
                        } catch (_: Exception) {}
                    }
                } else {
                    operationError = "Izin berhasil diminta tetapi folder tidak dapat diakses."
                    folderState = verifiedAccess
                }
            }.onFailure { err ->
                operationError = "Gagal memperoleh izin akses folder: ${err.localizedMessage ?: "Akses ditolak"}. Folder aktif tidak diubah."
                // Do not update rootFolderPreferences; existing root remains intact
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Pengaturan",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Root Folder Penyimpanan",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                when (val state = folderState) {
                    is RootFolderState.NotConfigured -> {
                        Text(
                            text = "Status: Belum memilih folder root.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Pilih folder 'Kitsune' yang berisi folder 'Comics'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is RootFolderState.PermissionLost -> {
                        Text(
                            text = "Status: Izin baca dicabut atau hilang.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Keterangan: ${state.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "URI: ${state.uriString}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Silakan pilih kembali folder untuk memperbarui izin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is RootFolderState.Inaccessible -> {
                        Text(
                            text = "Status: Akses folder bermasalah.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Keterangan: ${state.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "URI: ${state.uriString}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is RootFolderState.Ready -> {
                        Text(
                            text = "Status: Siap (Akses aktif terverifikasi)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "URI: ${state.uriString}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (operationError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = operationError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val openDocTreeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                val currentStr = rootFolderPreferences.getRootUriString()
                                if (!currentStr.isNullOrEmpty()) {
                                    try {
                                        val currentUri = Uri.parse(currentStr)
                                        putExtra("android.provider.extra.INITIAL_URI", currentUri)
                                    } catch (_: Exception) {}
                                }
                            }
                            launcher.launch(openDocTreeIntent)
                        }
                    ) {
                        Text(
                            text = if (folderState is RootFolderState.Ready) {
                                "Ubah Folder Root"
                            } else {
                                "Pilih Folder Root"
                            }
                        )
                    }

                    if (folderState !is RootFolderState.NotConfigured) {
                        OutlinedButton(
                            onClick = {
                                operationError = null
                                isVerifying = true
                                refreshStatus()
                                isVerifying = false
                            },
                            enabled = !isVerifying
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Periksa Ulang Akses")
                        }
                    }
                }

                if (showReleaseRootDialog) {
                    ReleaseRootConfirmationDialog(
                        onConfirm = {
                            showReleaseRootDialog = false
                            val currentStr = rootFolderPreferences.getRootUriString()
                            var releaseFailed = false
                            if (!currentStr.isNullOrEmpty()) {
                                try {
                                    val uri = Uri.parse(currentStr)
                                    val releaseResult = SafStorageHelper.releasePersistablePermissions(
                                        resolver = context.contentResolver,
                                        uri = uri
                                    )
                                    releaseResult.onFailure { err ->
                                        releaseFailed = true
                                        operationError = "Peringatan: Gagal melepas izin sistem: ${err.localizedMessage ?: "Unknown error"}"
                                    }
                                } catch (_: Exception) {}
                            }

                            if (!releaseFailed) {
                                rootFolderPreferences.setRootUriString(null)
                                refreshStatus()
                            }
                        },
                        onDismiss = { showReleaseRootDialog = false }
                    )
                }

                if (folderState !is RootFolderState.NotConfigured) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showReleaseRootDialog = true }
                    ) {
                        Text("Lepas Folder Root")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reader Preferences Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Preferensi Reader",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Mode Reader
                Text(
                    text = "Mode Tampilan Reader",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.readerMode == "single_page",
                        onClick = {
                            scope.launch { settingsRepository?.setReaderMode("single_page") }
                        },
                        label = { Text("Single Page") }
                    )
                    FilterChip(
                        selected = settingsState.readerMode == "vertical_continuous",
                        onClick = {
                            scope.launch { settingsRepository?.setReaderMode("vertical_continuous") }
                        },
                        label = { Text("Vertical Continuous") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Arah Baca
                Text(
                    text = "Arah Membaca Halaman",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.readingDirection == "ltr",
                        onClick = {
                            scope.launch { settingsRepository?.setReadingDirection("ltr") }
                        },
                        label = { Text("LTR (Kiri ke Kanan)") }
                    )
                    FilterChip(
                        selected = settingsState.readingDirection == "rtl",
                        onClick = {
                            scope.launch { settingsRepository?.setReadingDirection("rtl") }
                        },
                        label = { Text("RTL (Kanan ke Kiri)") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Page Fit
                Text(
                    text = "Ukuran Halaman (Page Fit)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.pageFit == "fit_screen",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_screen") }
                        },
                        label = { Text("Pas Layar") }
                    )
                    FilterChip(
                        selected = settingsState.pageFit == "fit_width",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_width") }
                        },
                        label = { Text("Pas Lebar") }
                    )
                    FilterChip(
                        selected = settingsState.pageFit == "fit_height",
                        onClick = {
                            scope.launch { settingsRepository?.setPageFit("fit_height") }
                        },
                        label = { Text("Pas Tinggi") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance & Grid Preferences Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tampilan & Grid",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tema Aplikasi
                Text(
                    text = "Tema Tampilan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settingsState.theme == "system",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("system") }
                        },
                        label = { Text("Sistem") }
                    )
                    FilterChip(
                        selected = settingsState.theme == "light",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("light") }
                        },
                        label = { Text("Terang") }
                    )
                    FilterChip(
                        selected = settingsState.theme == "dark",
                        onClick = {
                            scope.launch { settingsRepository?.setTheme("dark") }
                        },
                        label = { Text("Gelap") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Jumlah Kolom Grid Library
                Text(
                    text = "Jumlah Kolom Grid Library",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4).forEach { cols ->
                        FilterChip(
                            selected = settingsState.gridColumns == cols,
                            onClick = {
                                scope.launch { settingsRepository?.setGridColumns(cols) }
                            },
                            label = { Text("${cols}x") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseRootConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lepas Folder Root") },
        text = {
            Text("Apakah Anda yakin ingin melepas folder root aktif? Aplikasi tidak dapat memuat komik sampai folder root dipilih kembali.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Lepas")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
