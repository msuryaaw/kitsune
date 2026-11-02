package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult

/**
 * Default implementation of [ComicFolderScanner].
 *
 * Uses [ComicsDirectoryDetector] to find the valid `Comics` directory, then lists direct children
 * using [StorageGateway.listChildren], filtering only direct directories.
 * Derives default title from folder name without modifying the original filesystem name.
 */
class DefaultComicFolderScanner(
    private val directoryDetector: ComicsDirectoryDetector,
    private val storageGateway: StorageGateway
) : ComicFolderScanner {

    override fun discoverComicFolders(): ComicFolderDiscoveryResult {
        // 1. Deteksi direktori Comics
        val comicsEntry = when (val state = directoryDetector.detectComicsDirectory()) {
            is ComicsDirectoryState.RootNotConfigured -> return ComicFolderDiscoveryResult.RootNotConfigured
            is ComicsDirectoryState.PermissionDenied -> return ComicFolderDiscoveryResult.PermissionDenied(state.message)
            is ComicsDirectoryState.DirectoryNotFound -> return ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is ComicsDirectoryState.NotADirectory -> return ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is ComicsDirectoryState.Error -> return ComicFolderDiscoveryResult.Error(state.exception)
            is ComicsDirectoryState.Empty -> return ComicFolderDiscoveryResult.Success(emptyList())
            is ComicsDirectoryState.Ready -> state.entry
        }

        // 2. Ambil entitas anak langsung dari Comics
        return when (val childrenResult = storageGateway.listChildren(comicsEntry.relativePath)) {
            is StorageResult.Success -> {
                // Hanya ambil entitas yang berupa direktori langsung
                val directFolders = childrenResult.data
                    .filter { it.isDirectory }
                    .map { entry ->
                        val metadata = inspectAndResolveMetadata(entry.relativePath, entry.name)
                        DiscoveredComicFolder(
                            folderName = entry.name,
                            relativePath = entry.relativePath,
                            defaultTitle = metadata.title,
                            entry = entry,
                            type = metadata.type,
                            language = metadata.language
                        )
                    }
                ComicFolderDiscoveryResult.Success(directFolders)
            }
            is StorageResult.RootNotConfigured -> ComicFolderDiscoveryResult.RootNotConfigured
            is StorageResult.PermissionDenied -> ComicFolderDiscoveryResult.PermissionDenied(childrenResult.message)
            is StorageResult.NotFound -> ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is StorageResult.Error -> ComicFolderDiscoveryResult.Error(childrenResult.exception)
        }
    }

    private fun inspectAndResolveMetadata(
        folderRelativePath: String,
        folderName: String
    ): ParsedFolderMetadata {
        val parsedFolderName = FolderMetadataParser.parseFolderName(folderName)
        val metadataPath = com.example.kitsune.storage.StoragePathUtils.combineRelativePath(folderRelativePath, "metadata.json")

        // 1. Try reading existing metadata.json
        val readResult = storageGateway.openInputStream(metadataPath)
        if (readResult is StorageResult.Success) {
            try {
                readResult.data.use { stream ->
                    val jsonText = stream.bufferedReader().readText()
                    val (type, language) = parseMetadataJson(jsonText)
                    if (!type.isNullOrBlank() || !language.isNullOrBlank()) {
                        return ParsedFolderMetadata(
                            title = parsedFolderName.title,
                            type = type.takeIf { it?.isNotBlank() == true } ?: parsedFolderName.type,
                            language = language.takeIf { it?.isNotBlank() == true } ?: parsedFolderName.language
                        )
                    }
                }
            } catch (_: Exception) {
                // If reading/parsing existing metadata.json fails, do not overwrite!
            }
        }

        // 2. If metadata.json does NOT exist and folder name parsing extracted valid metadata, create metadata.json
        if (readResult is StorageResult.NotFound && (parsedFolderName.type != null || parsedFolderName.language != null)) {
            try {
                val createResult = storageGateway.createFile(folderRelativePath, "metadata.json", "application/json")
                if (createResult is StorageResult.Success) {
                    val outResult = storageGateway.openOutputStream(metadataPath)
                    if (outResult is StorageResult.Success) {
                        outResult.data.use { outStream ->
                            val jsonString = buildMetadataJson(parsedFolderName.type, parsedFolderName.language)
                            outStream.write(jsonString.toByteArray(Charsets.UTF_8))
                            outStream.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // If writing sidecar fails (e.g. read-only SAF root), safely ignore and use parsedFolderName
            }
        }

        return parsedFolderName
    }

    private fun parseMetadataJson(jsonText: String): Pair<String?, String?> {
        val typeRegex = Regex(""""type"\s*:\s*"([^"]+)"""")
        val langRegex = Regex(""""language"\s*:\s*"([^"]+)"""")

        val typeMatch = typeRegex.find(jsonText)?.groupValues?.get(1)?.trim()
        val langMatch = langRegex.find(jsonText)?.groupValues?.get(1)?.trim()

        return Pair(typeMatch, langMatch)
    }

    private fun buildMetadataJson(type: String?, language: String?): String {
        val sb = StringBuilder()
        sb.append("{\n")
        val parts = mutableListOf<String>()
        if (!type.isNullOrBlank()) parts.add("""  "type": "$type"""")
        if (!language.isNullOrBlank()) parts.add("""  "language": "$language"""")
        sb.append(parts.joinToString(",\n"))
        sb.append("\n}")
        return sb.toString()
    }

    /**
     * Derives default title from folder name.
     * Trims leading/trailing whitespace without modifying the original folder name representation.
     */
    private fun deriveDefaultTitle(folderName: String): String {
        val trimmed = folderName.trim()
        return if (trimmed.isNotEmpty()) trimmed else folderName
    }
}

