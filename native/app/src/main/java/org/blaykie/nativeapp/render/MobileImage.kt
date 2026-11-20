package org.blaykie.nativeapp.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val imageDecoders = Semaphore(2)

@Composable
internal fun MobileImage(source: File, image: RenderImage) {
    val entry = image.entryName
    if (entry == null) {
        imagePlaceholderText(ImageDisplay.Failed)?.let { ImagePlaceholder(it) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val target = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 2048)
        key(source, image, target) {
            val result by produceState<ImageDecode>(ImageDecode.Loading) {
                val bitmap = withContext(Dispatchers.IO) {
                    imageDecoders.withPermit {
                        (listOf(entry) + image.fallbackEntries).firstNotNullOfOrNull { decodeImage(source, it, target) }
                    }
                }
                value = if (bitmap == null) ImageDecode.Failed else ImageDecode.Ready(bitmap)
            }
            val decoded = result
            if (decoded is ImageDecode.Ready) {
                val bitmap = decoded.bitmap
                Image(bitmap.asImageBitmap(), image.description ?: "Document image",
                    Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height))
            }
            imagePlaceholderText(decoded.display)?.let { ImagePlaceholder(it) }
        }
    }
}

private sealed class ImageDecode(val display: ImageDisplay) {
    data object Loading : ImageDecode(ImageDisplay.Loading)
    data object Failed : ImageDecode(ImageDisplay.Failed)
    data class Ready(val bitmap: Bitmap) : ImageDecode(ImageDisplay.Drawn)
}

internal enum class ImageDisplay { Loading, Failed, Drawn }
internal fun imagePlaceholderText(display: ImageDisplay): String? = if (display == ImageDisplay.Failed) "[image]" else null

@Composable
private fun ImagePlaceholder(text: String) = Text(text, Modifier.padding(vertical = 4.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

private fun decodeImage(source: File, name: String, targetWidth: Int): Bitmap? = try {
    readRenderPackage(source) { zip ->
        val entry = zip.getEntry(name) ?: return@readRenderPackage null
        if (entry.isDirectory || entry.size !in 1..MAX_IMAGE_BYTES) return@readRenderPackage null
        // Read no more than the declared, bounded length even for a malformed compressed entry.
        val bytes = ByteArray(entry.size.toInt())
        zip.getInputStream(entry).use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                val count = stream.read(bytes, offset, bytes.size - offset)
                if (count <= 0) return@readRenderPackage null
                offset += count
            }
            if (stream.read() != -1) return@readRenderPackage null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 100_000_000L ||
            maxOf(width, height).toLong() > minOf(width, height) * 64L) return@readRenderPackage null
        var sample = 1
        while (width / sample > targetWidth * 2 || height / sample > 2048 ||
            ((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) > 1_500_000L) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
