package com.kitsune.app.reader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.kitsune.app.data.repository.ReaderRepository
import okio.buffer
import okio.source
import java.io.BufferedInputStream

/**
 * Model data untuk Coil agar bisa memuat gambar langsung dari dalam file CBZ.
 */
data class CbzPageModel(
    val chapterUri: Uri,
    val entryPath: String
)

/**
 * Fetcher kustom untuk Coil yang melayani pemuatan gambar dari entri ZIP/CBZ.
 * REVISION 12.4.1: Added BufferedInputStream and GPU Texture protection (Webtoon Fix).
 */
class CbzImageFetcher(
    private val context: Context,
    private val model: CbzPageModel,
    private val readerRepository: ReaderRepository
) : Fetcher {

    companion object {
        private const val MAX_GPU_TEXTURE_SIZE = 8192
    }

    override suspend fun fetch(): FetchResult? {
        val rawStream = readerRepository.getPageStream(model.chapterUri, model.entryPath)
            ?: return null

        // Wrap with BufferedInputStream to support mark/reset
        val inputStream = BufferedInputStream(rawStream)

        return try {
            // OPTIMIZATION: Pre-flight check for image resolution
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream.mark(1024 * 1024) // Mark up to 1MB
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.reset()

            if (options.outWidth > MAX_GPU_TEXTURE_SIZE || options.outHeight > MAX_GPU_TEXTURE_SIZE) {
                val sampleSize = calculateInSampleSize(options, MAX_GPU_TEXTURE_SIZE, MAX_GPU_TEXTURE_SIZE)
                if (sampleSize > 1) {
                    Log.w("CbzImageFetcher", "High-res image detected (${options.outWidth}x${options.outHeight}). Applying downsampling with sampleSize=$sampleSize.")
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
                    if (bitmap != null) {
                        return DrawableResult(
                            drawable = bitmap.toDrawable(context.resources),
                            isSampled = true,
                            dataSource = DataSource.DISK
                        )
                    }
                    // Fallback to reset stream if decode failed
                    inputStream.reset()
                }
            }

            SourceResult(
                source = ImageSource(
                    source = inputStream.source().buffer(),
                    context = context
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            try { inputStream.close() } catch (ignored: Exception) {}
            throw e
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    class Factory(
        private val context: Context,
        private val readerRepository: ReaderRepository
    ) : Fetcher.Factory<CbzPageModel> {
        override fun create(data: CbzPageModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return CbzImageFetcher(context, data, readerRepository)
        }
    }
}
