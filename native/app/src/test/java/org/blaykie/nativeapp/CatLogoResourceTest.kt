package org.blaykie.nativeapp

import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class CatLogoResourceTest {
    private fun root() = listOf(File("../.."), File("."), File("../../.."))
        .first { File(it, "native/app/build.gradle.kts").isFile }
    private fun res(path: String) = File(root(), "native/app/src/main/res/$path")
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(res(path))

    @Test fun adaptiveForegroundAndThemedIconUseTheSameSafeZoneArtwork() {
        val foreground = xml("drawable/launcher_foreground.xml")
        val mono = xml("drawable/launcher_monochrome.xml")
        for (doc in listOf(foreground, mono)) {
            val group = doc.getElementsByTagName("group").item(0).attributes
            assertEquals("24", group.getNamedItemNS(android, "translateX").nodeValue)
            assertEquals("24", group.getNamedItemNS(android, "translateY").nodeValue)
            assertEquals("0.9375", group.getNamedItemNS(android, "scaleX").nodeValue)
            assertEquals("0.9375", group.getNamedItemNS(android, "scaleY").nodeValue)
        }
        val colorPaths = foreground.getElementsByTagName("path")
        val monoPaths = mono.getElementsByTagName("path")
        assertEquals(5, colorPaths.length)
        assertEquals(colorPaths.length, monoPaths.length)
        for (i in 0 until colorPaths.length) {
            assertEquals(colorPaths.item(i).attributes.getNamedItemNS(android, "pathData").nodeValue,
                monoPaths.item(i).attributes.getNamedItemNS(android, "pathData").nodeValue)
        }
        assertEquals("#F4C430", colorPaths.item(1).attributes.getNamedItemNS(android, "fillColor").nodeValue)
        assertEquals("#FFFFFFFF", monoPaths.item(1).attributes.getNamedItemNS(android, "fillColor").nodeValue)
        assertEquals("#FFFFFFFF", monoPaths.item(2).attributes.getNamedItemNS(android, "fillColor").nodeValue)
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            assertEquals(0, xml("mipmap-anydpi-v26/$name.xml").getElementsByTagName("monochrome").length)
            assertEquals(1, xml("mipmap-anydpi-v33/$name.xml").getElementsByTagName("monochrome").length)
        }
    }

    @Test fun legacyLaunchersExistAtEveryDensityWithOffWhiteBackground() {
        for ((density, size) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val image = ImageIO.read(res("mipmap-$density/$name.png"))
                assertEquals(size, image.width)
                assertEquals(size, image.height)
                assertEquals(0xFFFBFAF8.toInt(), image.getRGB(0, 0))
            }
        }
    }
}
