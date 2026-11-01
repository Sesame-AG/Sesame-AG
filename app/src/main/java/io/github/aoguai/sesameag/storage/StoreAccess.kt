package io.github.aoguai.sesameag.storage

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import io.github.aoguai.sesameag.IStoreListener
import io.github.aoguai.sesameag.IStoreTransaction
import io.github.aoguai.sesameag.util.CommandUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** The module owns persistence; hosts only exchange snapshots inside a storage transaction. */
object StoreAccess {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permit = Semaphore(1, true)
    private val localDepth = ThreadLocal.withInitial { 0 }
    private val remoteSession = ThreadLocal<IStoreTransaction?>()
    private val remoteClient = ThreadLocal<IBinder?>()
    private val afterActions = ThreadLocal<MutableList<() -> Unit>?>()
    data class Change(val collection: Int, val owner: String, val name: String)
    private val localChanges = ThreadLocal.withInitial { linkedSetOf<Change>() }
    private val callbacks = CopyOnWriteArraySet<(Change) -> Unit>()
    private val listeners = RemoteCallbackList<IStoreListener>()
    private val listener = object : IStoreListener.Stub() {
        override fun onChanged(collection: Int, owner: String, name: String) = dispatchLocal(Change(collection, owner, name))
    }
    @Volatile private var remote = false

    fun useRemote() { remote = true }
    val isRemote: Boolean get() = remote
    val isReady: Boolean get() = if (remote) CommandUtil.hasStorageConnection() else PrivateStorage.isReady

    fun observe(callback: (Change) -> Unit): () -> Unit {
        callbacks.add(callback)
        return { callbacks.remove(callback) }
    }

    fun attachRemote() { CommandUtil.storageService().registerStoreListener(listener) }
    fun remoteListener(): IStoreListener = listener
    fun register(listener: IStoreListener) { listeners.register(listener) }
    fun unregister(listener: IStoreListener) { listeners.unregister(listener) }

    private fun dispatchLocal(change: Change) {
        callbacks.forEach { callback -> scope.launch { runCatching { callback(change) }.onFailure {
            android.util.Log.e("PrivateStorage", "Storage notification failed", it)
        } } }
    }

    private fun publish(changes: Set<Change>) {
        changes.forEach(::dispatchLocal)
        val targets = synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try { List(count) { index -> listeners.getBroadcastItem(index) } }
            finally { listeners.finishBroadcast() }
        }
        targets.forEach { target -> changes.forEach { change ->
            runCatching { target.onChanged(change.collection, change.owner, change.name) }
        } }
    }

    fun <T> transaction(block: () -> T): T {
        if (remoteSession.get() != null || (localDepth.get() ?: 0) > 0) return block()
        val actions = mutableListOf<() -> Unit>()
        afterActions.set(actions)
        try {
            if (remote) {
                val client = Binder()
                remoteClient.set(client)
                try {
                    val session = CommandUtil.storageService().beginStoreTransaction(client)
                        ?: throw IOException("模块存储尚未就绪，请在模块中完成数据迁移")
                    remoteSession.set(session)
                    try { return block() } finally {
                        remoteSession.remove()
                        session.close()
                    }
                } finally { remoteClient.remove() }
            }
            PrivateStorage.requireReady()
            // 本地存储事务互斥以避免交叉写入；最多等待五秒争用许可，获得后立即执行。
            if (!permit.tryAcquire(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                throw IOException("模块存储正忙，请稍后重试")
            }
            localDepth.set(1)
            checkNotNull(localChanges.get()).clear()
            try { return block() } finally {
                val changes = checkNotNull(localChanges.get()).toSet()
                localDepth.remove()
                localChanges.remove()
                permit.release()
                if (changes.isNotEmpty()) publish(changes)
            }
        } finally {
            afterActions.remove()
            actions.forEach { action -> runCatching(action).onFailure {
                android.util.Log.e("PrivateStorage", "Post-transaction action failed", it)
            } }
        }
    }

    /** 调度和通知在最外层事务结束后执行，避免回调重新申请正在占用的事务。 */
    internal fun afterTransaction(action: () -> Unit) {
        val actions = afterActions.get()
        if (actions == null) action() else actions.add(action)
    }

    internal fun stat(file: StoreFile): Bundle = transaction {
        if (remote) remoteSession.get()!!.stat(file.collection, file.owner, file.resourceName)
        else metadata(file.localFile())
    }

    internal fun ensureDirectory(file: StoreFile): Boolean = transaction {
        if (remote) remoteSession.get()!!.ensureDirectory(file.collection, file.owner, file.resourceName)
        else file.localFile().let { it.isDirectory || it.mkdirs() }
    }

    internal fun list(file: StoreFile): Array<StoreFile> = transaction {
        val names = if (remote) remoteSession.get()!!.list(file.collection, file.owner, file.resourceName)
        else file.localFile().list()?.sortedArray() ?: throw IOException("无法读取目录：${file.key}")
        names.map { StoreFile(file, it) }.toTypedArray()
    }

    internal fun read(file: StoreFile): String = transaction {
        if (!remote) file.localFile().readText(Charsets.UTF_8)
        else {
            val descriptor = remoteSession.get()!!.read(file.collection, file.owner, file.resourceName)
                ?: throw IOException("无法读取：${file.key}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    internal fun <T> input(file: StoreFile, block: (java.io.InputStream) -> T): T = transaction {
        val input = if (!remote) file.localFile().inputStream() else {
            val descriptor = remoteSession.get()!!.read(file.collection, file.owner, file.resourceName)
                ?: throw IOException("无法读取：${file.key}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
        input.use(block)
    }

    internal fun truncate(file: StoreFile, length: Long) = transaction {
        require(length >= 0)
        if (remote) remoteSession.get()!!.truncate(file.collection, file.owner, file.resourceName, length)
        else {
            java.io.RandomAccessFile(file.localFile(), "rw").use { it.setLength(length); it.fd.sync() }
            checkNotNull(localChanges.get()).add(Change(file.collection, file.owner, file.resourceName))
        }
    }

    internal fun write(file: StoreFile, text: String, append: Boolean = false) = transaction {
        if (!remote) {
            if (append) FileOutputStream(file.localFile(), true).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            else atomicWrite(file.localFile(), text.byteInputStream(Charsets.UTF_8))
            checkNotNull(localChanges.get()).add(Change(file.collection, file.owner, file.resourceName))
        } else {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            // 同步 Binder 调用会等待管道数据，写端不能排入调用方占用的 IO 线程池。
            val writer = thread(start = false, isDaemon = true, name = "SesameStoreWriter") {
                val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                try {
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.close()
                } catch (failure: Exception) {
                    runCatching { pipe[1].closeWithError(failure.message ?: "write failed") }
                } finally { runCatching { output.close() } }
            }
            try {
                writer.start()
                if (append) remoteSession.get()!!.append(file.collection, file.owner, file.resourceName, pipe[0])
                else remoteSession.get()!!.write(file.collection, file.owner, file.resourceName, pipe[0])
            }
            finally { pipe[0].close(); pipe[1].close(); writer.interrupt() }
        }
    }

    internal fun remove(file: StoreFile): Boolean = transaction {
        if (remote) remoteSession.get()!!.remove(file.collection, file.owner, file.resourceName)
        else file.localFile().let { target ->
            (!target.exists() || target.deleteRecursively()).also { if (it) checkNotNull(localChanges.get()).add(Change(file.collection, file.owner, file.resourceName)) }
        }
    }

    /** Collection and names are relative data identifiers, never caller supplied filesystem paths. */
    internal fun resolve(collection: Int, owner: String, name: String): File {
        fun valid(value: String) = value.isNotEmpty() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == '\u0000' }
        require(name.isEmpty() || valid(name)) { "Invalid storage resource" }
        val directory = when (collection) {
            0 -> { require(owner.isEmpty()); PrivateStorage.root }
            1 -> { require(owner.isEmpty()); File(PrivateStorage.root, "config") }
            2 -> { require(valid(owner)); File(PrivateStorage.root, "config/$owner") }
            else -> throw IllegalArgumentException("Invalid storage collection")
        }
        if (collection == 0) require(name != ".store-ready" && name != "config" && name != "log") { "Reserved storage resource" }
        val result = if (name.isEmpty()) directory else File(directory, name)
        require(result.canonicalFile.toPath().startsWith(PrivateStorage.root.canonicalFile.toPath()))
        return result
    }

    fun begin(client: IBinder, uid: Int): IStoreTransaction {
        PrivateStorage.requireReady()
        // Binder 同步调用不能随客户端协程取消，事务申请必须能够有界返回。
        if (!permit.tryAcquire(5L, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IOException("模块存储正忙，请稍后重试")
        }
        return try { Session(client, uid) } catch (failure: Throwable) { permit.release(); throw failure }
    }

    private class Session(private val client: IBinder, private val uid: Int) : IStoreTransaction.Stub(), IBinder.DeathRecipient {
        private var closed = false
        private val changes = linkedSetOf<Change>()
        init { client.linkToDeath(this, 0) }
        private fun target(collection: Int, owner: String, name: String): File {
            check(!closed) { "Storage transaction closed" }
            check(Binder.getCallingUid() == uid) { "Storage caller changed" }
            return resolve(collection, owner, name)
        }
        @Synchronized override fun stat(collection: Int, owner: String, name: String): Bundle =
            metadata(target(collection, owner, name))
        @Synchronized override fun list(collection: Int, owner: String, name: String): Array<String> =
            target(collection, owner, name).list()?.sortedArray() ?: throw IOException("Storage directory unavailable")
        @Synchronized override fun ensureDirectory(collection: Int, owner: String, name: String): Boolean =
            target(collection, owner, name).let { it.isDirectory || it.mkdirs() }
        @Synchronized override fun read(collection: Int, owner: String, name: String): ParcelFileDescriptor {
            val file = target(collection, owner, name)
            require(name.isNotEmpty() && file.isFile)
            // Read-only descriptor refers to this snapshot even if a later atomic replacement occurs.
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        @Synchronized override fun write(collection: Int, owner: String, name: String, source: ParcelFileDescriptor) {
            val file = target(collection, owner, name)
            require(name.isNotEmpty())
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                atomicWrite(file, input) { source.checkError() }
            }
            changes.add(Change(collection, owner, name))
        }
        @Synchronized override fun append(collection: Int, owner: String, name: String, source: ParcelFileDescriptor) {
            val file = target(collection, owner, name)
            require(name.isNotEmpty())
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                FileOutputStream(file, true).use { output -> input.copyTo(output); source.checkError(); output.fd.sync() }
            }
            changes.add(Change(collection, owner, name))
        }
        @Synchronized override fun truncate(collection: Int, owner: String, name: String, length: Long) {
            val file = target(collection, owner, name)
            require(name.isNotEmpty() && length >= 0)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(length); it.fd.sync() }
            changes.add(Change(collection, owner, name))
        }
        @Synchronized override fun remove(collection: Int, owner: String, name: String): Boolean {
            val file = target(collection, owner, name)
            require(collection == 2 || name.isNotEmpty()) { "Cannot remove storage root" }
            return (!file.exists() || file.deleteRecursively()).also { if (it) changes.add(Change(collection, owner, name)) }
        }
        override fun close() { check(Binder.getCallingUid() == uid); release() }
        override fun binderDied() = release()
        private fun release() {
            val pending = synchronized(this) {
                if (closed) return
                closed = true
                changes.toSet()
            }
            try { client.unlinkToDeath(this, 0) } finally { permit.release() }
            if (pending.isNotEmpty()) publish(pending)
        }
    }

    internal fun recoverSnapshot(context: android.content.Context): Boolean {
        val root = File(context.noBackupFilesDir, "store")
        val previous = File(context.noBackupFilesDir, "store-restore-previous")
        if (!root.exists() && previous.isDirectory) {
            java.nio.file.Files.move(previous.toPath(), root.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(context.noBackupFilesDir)
        }
        return !File(root, ".store-ready").isFile || !previous.exists() || previous.deleteRecursively()
    }

    internal fun installSnapshot(snapshot: File) {
        // 恢复快照需排除并发事务；五秒为获取独占许可的上限，不是固定恢复延迟。
        if (!permit.tryAcquire(5L, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IOException("模块存储正忙，未开始恢复，请稍后重试")
        }
        val root = PrivateStorage.root
        val previous = File(PrivateStorage.context.noBackupFilesDir, "store-restore-previous")
        var installed = false
        try {
            val install = {
                check(!previous.exists()) { "存在尚未处理的恢复副本，请重新打开模块" }
                atomicWrite(File(snapshot, ".store-ready"), "ready".byteInputStream())
                snapshot.walkBottomUp().filter { it.isDirectory }.forEach(::syncDirectory)
                if (root.exists()) {
                    java.nio.file.Files.move(root.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(root.parentFile!!)
                }
                try {
                    java.nio.file.Files.move(snapshot.toPath(), root.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(root.parentFile!!)
                } catch (failure: Throwable) {
                    if (!root.exists() && previous.exists()) {
                        java.nio.file.Files.move(previous.toPath(), root.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        syncDirectory(root.parentFile!!)
                    }
                    throw failure
                }
                installed = true
                val cleaned = !previous.exists() || previous.deleteRecursively()
                PrivateStorage.update(PrivateStorage.State(ready = true, busy = PrivateStorage.state.value.busy,
                    message = if (cleaned) "配置已保存到应用私有目录" else "配置已恢复，旧私有副本待下次启动清理",
                    cleanupPending = PrivateStorage.state.value.cleanupPending))
            }
            if (root.exists()) io.github.aoguai.sesameag.util.Logback.withFileLoggingPaused(install) else install()
        } finally {
            permit.release()
            if (installed) publish(setOf(Change(1, "", "")))
        }
    }

    internal fun metadata(file: File) = Bundle().apply {
        putBoolean("exists", file.exists())
        putBoolean("directory", file.isDirectory)
        putLong("length", file.length())
        putLong("modified", file.lastModified())
    }

    internal fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }

    internal fun atomicWrite(file: File, input: java.io.InputStream, checkInput: (() -> Unit)? = null) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create private directory" }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
            checkInput?.invoke()
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(parent)
        } finally { temporary.delete() }
    }
}
