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
        ImagePlaceholder()
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val target = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 2048)
        val bitmap by produceState<Bitmap?>(null, source, entry, target) {
            value = withContext(Dispatchers.IO) { imageDecoders.withPermit { decodeImage(source, entry, target) } }
        }
        val decoded = bitmap
        if (decoded == null) ImagePlaceholder()
        else Image(decoded.asImageBitmap(), image.description ?: "Document image",
            Modifier.fillMaxWidth().aspectRatio(decoded.width.toFloat() / decoded.height))
    }
}

@Composable
private fun ImagePlaceholder() = Text("[image]", Modifier.padding(vertical = 4.dp),
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
