package io.github.aoguai.sesameag.storage

import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter

/** A storage resource handle. It never exposes a host-readable private filesystem path. */
class StoreFile private constructor(val key: String) : File("/sesame-store" + if (key.isEmpty()) "" else "/$key") {
    constructor(parent: File, child: String) : this(
        (parent as? StoreFile ?: error("Storage parent is not a resource")).key.let {
            require(child.isNotEmpty() && child.none { c -> c == '/' || c == '\\' || c == '\u0000' } && child != "." && child != "..")
            if (it.isEmpty()) child else "$it/$child"
        }
    )
    private val parts get() = key.split('/').filter { it.isNotEmpty() }
    internal val collection: Int get() = if (parts.firstOrNull() != "config") 0 else if (parts.size >= 3) 2 else 1
    internal val owner: String get() = if (collection == 2) parts[1] else ""
    internal val resourceName: String get() = when (collection) {
        0 -> key
        1 -> parts.drop(1).singleOrNull().orEmpty()
        else -> parts.drop(2).singleOrNull().orEmpty()
    }
    internal fun localFile(): File = StoreAccess.resolve(collection, owner, resourceName)
    override fun exists() = StoreAccess.stat(this).getBoolean("exists")
    override fun isFile() = exists() && !isDirectory
    override fun isDirectory() = StoreAccess.stat(this).getBoolean("directory")
    override fun length() = StoreAccess.stat(this).getLong("length")
    override fun lastModified() = StoreAccess.stat(this).getLong("modified")
    override fun canRead() = exists()
    override fun canWrite() = StoreAccess.isReady
    override fun mkdirs() = StoreAccess.ensureDirectory(this)
    override fun mkdir() = mkdirs()
    override fun createNewFile(): Boolean = StoreAccess.transaction {
        if (exists()) false else { StoreAccess.write(this, ""); true }
    }
    override fun delete() = StoreAccess.remove(this)
    override fun listFiles(): Array<File> = StoreAccess.list(this).map { it as File }.toTypedArray()
    override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()
    override fun listFiles(filter: FileFilter?): Array<File> = listFiles().filter { filter?.accept(it) != false }.toTypedArray()
    override fun listFiles(filter: FilenameFilter?): Array<File> = listFiles().filter { filter?.accept(this, it.name) != false }.toTypedArray()
    override fun getParentFile(): File? = if (key.isEmpty()) null else StoreFile(key.substringBeforeLast('/', ""))
    override fun getCanonicalFile(): File = this
    override fun getAbsoluteFile(): File = this
    override fun getCanonicalPath(): String = path
    override fun setReadable(readable: Boolean, ownerOnly: Boolean) = ownerOnly && readable
    override fun setWritable(writable: Boolean, ownerOnly: Boolean) = ownerOnly && writable
    override fun setExecutable(executable: Boolean, ownerOnly: Boolean) = ownerOnly && executable
    companion object { val root = StoreFile("") }
}
