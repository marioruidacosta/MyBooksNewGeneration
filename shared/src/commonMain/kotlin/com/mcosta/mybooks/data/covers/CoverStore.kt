package com.mcosta.mybooks.data.covers

import okio.FileSystem
import okio.Path
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Cover photos taken in the app, stored as JPEG files in [dir]. Books refer to them as
 * `cover:<file name>` so the reference survives reinstalls and backup restores.
 */
class CoverStore(private val fs: FileSystem, val dir: Path) {

    fun isLocal(ref: String?): Boolean = ref != null && ref.startsWith(PREFIX)

    /** The file name of a `cover:` reference, or null for web URLs and malformed references. */
    fun nameOf(ref: String?): String? {
        if (!isLocal(ref)) return null
        return ref!!.removePrefix(PREFIX).takeIf(::isSafeName)
    }

    /** The file for a `cover:` reference, or null for web URLs and malformed references. */
    fun fileFor(ref: String?): Path? = nameOf(ref)?.let { dir / it }

    /** The photo's bytes, or null if [ref] isn't a stored photo. */
    fun read(ref: String?): ByteArray? {
        val path = fileFor(ref)?.takeIf { fs.exists(it) } ?: return null
        return fs.read(path) { readByteArray() }
    }

    /** What to give the image loader: a file URL for a local cover, the URL otherwise. */
    fun imageModel(ref: String?): String? = fileFor(ref)?.let { "file://$it" } ?: ref?.takeUnless { isLocal(it) }

    /** Stores a new photo and returns its `cover:` reference. */
    @OptIn(ExperimentalUuidApi::class)
    fun save(jpeg: ByteArray): String = put("${Uuid.random()}.jpg", jpeg)

    /** Stores [jpeg] under [name] (used when restoring backups). */
    fun put(name: String, jpeg: ByteArray): String {
        require(isSafeName(name)) { "bad cover name: $name" }
        fs.createDirectories(dir)
        fs.write(dir / name) { write(jpeg) }
        return PREFIX + name
    }

    /** Whether the photo [ref] points at is stored. */
    fun exists(ref: String?): Boolean = fileFor(ref)?.let { fs.exists(it) } == true

    /** Deletes every file in the folder (sign-out with Remove), whether or not a book still points at it. */
    fun deleteAll() {
        fs.listOrNull(dir)?.forEach { fs.delete(it, mustExist = false) }
    }

    fun delete(ref: String?) {
        fileFor(ref)?.let { fs.delete(it, mustExist = false) }
    }

    companion object {
        const val PREFIX = "cover:"

        fun isSafeName(name: String) = name.isNotEmpty() && name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != ".."
    }
}

/** Turns a camera photo into a small, upright JPEG for use as a cover. Call off the main thread. */
interface PhotoShrinker {
    fun shrink(photo: ByteArray, maxSide: Int = 1000): ByteArray
}
