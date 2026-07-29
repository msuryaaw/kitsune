package com.kitsune.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.repository.ReaderRepository
import com.kitsune.app.reader.CbzImageFetcher
import com.kitsune.app.reader.CbzParser

/**
 * Custom Application class for Kitsune.
 * Handles dependency initialization and optimized ImageLoader configuration.
 * 
 * REVISION 10.4.2: Optimized Coil configuration and implemented Memory management.
 */
class KitsuneApplication : Application(), ImageLoaderFactory {

    lateinit var readerRepository: ReaderRepository
        private set
    
    lateinit var storageHelper: StorageHelper
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Core SAF Helper
        storageHelper = StorageHelper(this)
        
        // Initialize Reader components
        val cbzParser = CbzParser(this)
        readerRepository = ReaderRepository(cbzParser)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(CbzImageFetcher.Factory(this@KitsuneApplication, readerRepository))
            }
            // OPTIMIZATION: Fine-tuned memory and disk cache for offline performance
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .respectCacheHeaders(false) // Better for offline local files
            .crossfade(true)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // REVISION 10.4.3: Proactive memory management
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            storageHelper.clearCache()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        storageHelper.clearCache()
    }
}
