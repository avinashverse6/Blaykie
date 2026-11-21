package org.blaykie.nativeapp.render

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element

private const val VML = "urn:schemas-microsoft-com:vml"
private const val OFFICE = "urn:schemas-microsoft-com:office:office"
private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val STRICT_DRAWING = "http://purl.oclc.org/ooxml/drawingml/main"

/** Recognize explicit horizontal-rule shapes, not arbitrary unsupported drawings. */
internal fun horizontalRule(container: Element): RenderHorizontalRule? {
    val vml = container.getElementsByTagNameNS(VML, "*")
    for (i in 0 until vml.length) {
        val shape = vml.item(i) as Element
        if (shape.getAttributeNS(OFFICE, "hr") !in setOf("t", "true", "1")) continue
        val height = shape.getAttribute("style").split(';').firstNotNullOfOrNull {
            val (key, value) = it.split(':', limit = 2).let { pair -> pair[0] to pair.getOrNull(1) }
            value?.takeIf { key.trim().equals("height", ignoreCase = true) }?.let(::points)
        }
        return RenderHorizontalRule(rgb(shape.getAttribute("fillcolor")) ?: rgb(shape.getAttribute("strokecolor")),
            (height ?: points(shape.getAttribute("strokeweight")) ?: 1f).coerceIn(.125f, 24f))
    }
    val geometries = container.getElementsByTagNameNS("*", "prstGeom")
    for (i in 0 until geometries.length) {
        val geometry = geometries.item(i) as Element
        if (geometry.namespaceURI !in setOf(DRAWING, STRICT_DRAWING) || geometry.getAttribute("prst") != "line") continue
        val props = geometry.parentNode as? Element ?: continue
        fun Element.drawingChild(name: String) = children().firstOrNull { it.localName == name && it.namespaceURI == geometry.namespaceURI }
        val extent = props.drawingChild("xfrm")?.drawingChild("ext") ?: continue
        if (extent.getAttribute("cy").toLongOrNull() != 0L || (extent.getAttribute("cx").toLongOrNull() ?: 0) <= 0) continue
        val rotation = props.drawingChild("xfrm")?.getAttribute("rot")?.toLongOrNull() ?: 0L
        if (rotation % 10_800_000 != 0L) continue
        val line = props.drawingChild("ln")
        if (line?.drawingChild("noFill") != null) continue
        val width = line?.getAttribute("w")?.toLongOrNull()?.div(12_700f) ?: 1f
        val fill = line?.drawingChild("solidFill")
        val color = fill?.drawingChild("srgbClr")?.getAttribute("val")
            ?: fill?.drawingChild("sysClr")?.getAttribute("lastClr")
        return RenderHorizontalRule(rgb(color.orEmpty()), width.coerceIn(.125f, 24f))
    }
    return null
}

private fun rgb(value: String) = value.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
private fun points(value: String): Float? = value.trim().let {
    when {
        it.endsWith("pt") -> it.dropLast(2).toFloatOrNull()
        it.endsWith("px") -> it.dropLast(2).toFloatOrNull()?.times(.75f)
        else -> it.toFloatOrNull()
    }
}?.takeIf { it.isFinite() && it > 0f }

@Composable
internal fun MobileHorizontalRule(rule: RenderHorizontalRule) {
    val paper = LocalDarkPaper.current
    val color = if (paper != null) printColor(rule.rgb, paper.onSurface, paper.background) else rule.rgb?.let { Color(it or 0xff000000.toInt()) } ?: MaterialTheme.colorScheme.onSurface
    HorizontalDivider(Modifier.fillMaxWidth().padding(vertical = 4.dp), thickness = rule.thicknessPoints.dp, color = color)
}
