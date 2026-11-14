package org.blaykie.nativeapp.csl

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal class StyleImportFailure(message: String) : IllegalArgumentException(message)

/** Private files are addressed by a digest, never by an external filename. */
internal class StyleImportStore(private val directory: File, private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_STYLES = 20
        fun nameFor(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) } + ".csl"
    }
    @Synchronized fun import(input: InputStream): StyleMetadata {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (bytes.size() + n > MAX_BYTES) throw StyleImportFailure("Style file is too large (maximum 512 KB).")
            bytes.write(buffer, 0, n)
        }
        val content = bytes.toByteArray()
        val metadata = try { StyleRegistry.read("import.csl", content.inputStream(), StyleSource.PRIVATE) }
        catch (e: Exception) {
            if (e.message == "Not a CSL style") throw StyleImportFailure("Not a CSL style file.")
            throw StyleImportFailure("Malformed or unsafe XML.")
        }
        if (metadata.dependent) throw StyleImportFailure("This style needs its parent style; import the parent first.")
        if (metadata.styleClass == "note" || metadata.citationFormat == "note" || metadata.citationFormat == "numeric")
            throw StyleImportFailure("Numeric and note styles are not supported yet.")
        if (metadata.styleClass != "in-text" || metadata.citationFormat != "author-date")
            throw StyleImportFailure("Only independent author-date styles are supported.")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create styles directory." }
        val root = directory.canonicalFile
        val target = File(root, nameFor(metadata.id))
        require(target.canonicalFile.parentFile == root) { "Invalid style path." }
        val files = requireNotNull(root.listFiles()).filter { it.isFile && it.name.endsWith(".csl", true) }
        if (!target.exists() && files.size >= MAX_STYLES) throw StyleImportFailure("Maximum of 20 imported styles reached.")
        val temp = File.createTempFile("style-", ".tmp", root)
        try {
            temp.outputStream().use { out -> out.write(content); out.fd.sync() }
            check(replace(temp, target)) { "Could not save style." }
        } finally { temp.delete() }
        return metadata.copy(asset = target.name)
    }
    @Synchronized fun remove(asset: String): Boolean {
        require(Regex("[0-9a-f]{64}\\.csl").matches(asset)) { "Invalid style file." }
        val root = directory.canonicalFile
        val file = File(root, asset)
        require(file.canonicalFile.parentFile == root) { "Invalid style path." }
        return file.isFile && file.delete()
    }
}

internal fun styleSelectionId(style: StyleMetadata): String =
    if (style.source == StyleSource.PRIVATE) "imported:${style.asset}" else style.id
internal fun selectedAfterRemoval(selected: String?, removed: String): String? = if (selected == removed) null else selected
