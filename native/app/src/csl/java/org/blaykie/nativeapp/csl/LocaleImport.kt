package org.blaykie.nativeapp.csl

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal class LocaleImportFailure(message: String) : IllegalArgumentException(message)
internal data class LocaleMetadata(val tag: String, val asset: String)

/** Debug-only private locale files, identified by normalized language tags. */
internal class LocaleImportStore(private val directory: File, private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_LOCALES = 10
        private val tagPattern = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
        fun normalize(tag: String): String {
            require(tagPattern.matches(tag)) { "Invalid language tag" }
            return Locale.forLanguageTag(tag).toLanguageTag().also { require(it != "und" && it.equals(tag, ignoreCase = true)) }
        }
        fun nameFor(tag: String): String = MessageDigest.getInstance("SHA-256").digest(normalize(tag).lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) } + ".xml"
        fun read(asset: String, input: InputStream): LocaleMetadata {
            val root = try { CslSafeXml.parse(input).second.documentElement }
                catch (_: Exception) { throw LocaleImportFailure("Malformed or unsafe XML.") }
            if (root.namespaceURI != "http://purl.org/net/xbiblio/csl" || root.localName != "locale")
                throw LocaleImportFailure("Not a CSL locale file.")
            val tag = root.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang").trim()
            if (tag.isEmpty()) throw LocaleImportFailure("Missing language tag.")
            val normalized = try { normalize(tag) } catch (_: Exception) { throw LocaleImportFailure("Invalid language tag.") }
            return LocaleMetadata(normalized, asset)
        }
    }
    @Synchronized fun import(input: InputStream): LocaleMetadata {
        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
            if (bytes.size() + n > MAX_BYTES) throw LocaleImportFailure("Locale file is too large (maximum 512 KB).")
            bytes.write(buffer, 0, n)
        }
        val content = bytes.toByteArray()
        val metadata = read("import.xml", content.inputStream())
        if (metadata.tag.equals("en-US", true)) throw LocaleImportFailure("English (US) is bundled and cannot be replaced.")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create locale directory." }
        val root = directory.canonicalFile; val target = File(root, nameFor(metadata.tag))
        require(target.canonicalFile.parentFile == root) { "Invalid locale path." }
        val files = requireNotNull(root.listFiles()).filter { it.isFile && it.name.endsWith(".xml", true) }
        if (!target.exists() && files.size >= MAX_LOCALES) throw LocaleImportFailure("Maximum of 10 imported locales reached.")
        val tmp = File.createTempFile("locale-", ".tmp", root)
        try {
            tmp.outputStream().use { out -> out.write(content); out.fd.sync() }
            check(replace(tmp, target)) { "Could not save locale." }
        } finally { tmp.delete() }
        return metadata.copy(asset = target.name)
    }
    @Synchronized fun list(): List<LocaleMetadata> {
        if (!directory.exists()) return emptyList()
        val root = directory.canonicalFile
        return requireNotNull(root.listFiles()).filter { it.isFile && Regex("[0-9a-f]{64}\\.xml").matches(it.name) && it.canonicalFile.parentFile == root }
            .mapNotNull { file ->
                try {
                    file.inputStream().use { read(file.name, it) }.takeIf { it.asset == nameFor(it.tag) }
                } catch (_: Exception) { null }
            }.sortedBy { it.tag }
    }
    @Synchronized fun remove(asset: String): Boolean {
        require(Regex("[0-9a-f]{64}\\.xml").matches(asset)) { "Invalid locale file." }
        val root = directory.canonicalFile; val file = File(root, asset)
        require(file.canonicalFile.parentFile == root) { "Invalid locale path." }
        return file.isFile && file.delete()
    }
}

internal fun languageAfterRemoval(selected: String, removed: String): String =
    if (selected.equals(removed, ignoreCase = true)) "en-US" else selected
