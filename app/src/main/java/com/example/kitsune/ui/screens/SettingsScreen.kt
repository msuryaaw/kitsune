package com.example.kitsune.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.core.RootFolderState
import com.example.kitsune.core.SafStorageHelper

@Composable
fun SettingsScreen(
    rootFolderPreferences: RootFolderPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var folderState by remember { mutableStateOf<RootFolderState>(RootFolderState.NotConfigured) }
    var operationError by remember { mutableStateOf<String?>(null) }

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
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
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
                        }
                    ) {
                        Text("Lepas Folder Root")
                    }
                }
            }
        }
    }
}
