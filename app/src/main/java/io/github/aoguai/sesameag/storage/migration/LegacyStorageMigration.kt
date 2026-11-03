package io.github.aoguai.sesameag.storage.migration

import android.content.Context
import android.net.Uri
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.storage.PrivateStorage
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.util.CommandUtil
import kotlinx.coroutines.Dispatchers
import io.github.aoguai.sesameag.util.SerialTaskQueue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import io.github.aoguai.sesameag.storage.StorageBackup

/** Remove this package and the startup/foreground entry when legacy upgrades are retired. */
object LegacyStorageMigration {
    private val operations = SerialTaskQueue()
    private fun work(context: Context) = File(context.noBackupFilesDir, "migration")
    fun hasCleanup(context: Context) = File(work(context), "cleanup.json").isFile

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private class Reader(
        private val service: IMigrationReader? = null,
        private val connection: android.content.ServiceConnection? = null,
        private val args: Shizuku.UserServiceArgs? = null,
        private val startRoot: ((String) -> java.lang.Process)? = null,
    ) : AutoCloseable {
        fun <T> read(command: String, action: (java.io.InputStream) -> T): T {
            if (service != null) {
                val descriptor = service.execute(command) ?: throw IOException("迁移读取通道未建立")
                return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    action(input).also { descriptor.checkError() }
                }
            }
            val process = checkNotNull(startRoot) { "迁移 Root 通道未建立" }(command)
            try {
                // 读完输出后仍需等待命令退出并核对退出码，避免把失败或未完成的迁移读取当成成功。
                return process.inputStream.use(action).also { check(process.waitFor() == 0) { "旧目录读取或维护失败" } }
            } finally { process.destroy() }
        }
        fun text(command: String): String = read(command) { it.bufferedReader(Charsets.UTF_8).readText() }
        fun copy(source: String, target: File) = read("cat -- ${quote(source)}") { StoreAccess.atomicWrite(target, it) }
        fun digest(source: String): String {
            val value = text("sha256sum -- ${quote(source)}").substringBefore(' ').trim()
            check(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "无法校验：$source" }
            return value
        }
        override fun close() {
            val serviceArgs = args ?: return
            val serviceConnection = connection ?: return
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        }
        companion object {
            suspend fun connect(context: Context, shizuku: Boolean): Reader {
                if (!shizuku) return Reader(startRoot = io.github.aoguai.sesameag.service.patch.SafeRootShell().streamingCommandFactory())
                val result = kotlinx.coroutines.CompletableDeferred<IMigrationReader>()
                val args = Shizuku.UserServiceArgs(android.content.ComponentName(context, MigrationUserService::class.java))
                    .daemon(false).processNameSuffix("storage_migration")
                    .version(io.github.aoguai.sesameag.BuildConfig.VERSION_CODE)
                val connection = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                        val service = IMigrationReader.Stub.asInterface(binder)
                        if (service != null) result.complete(service) else result.completeExceptionally(IOException("迁移服务未连接"))
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        result.completeExceptionally(IOException("迁移服务已断开"))
                    }
                }
                try {
                    Shizuku.bindUserService(args, connection)
                    // 迁移只能在 Shizuku 服务连接回调后继续；十秒是连接上限，连接成功即返回。
                    return Reader(kotlinx.coroutines.withTimeout(10_000L) { result.await() }, connection, args)
                } catch (failure: Exception) {
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    throw failure
                }
            }
        }
    }

    private fun roots(context: Context): List<Pair<File, String>> {
        val external = android.os.Environment.getExternalStorageDirectory()
        val target = try { context.packageManager.getApplicationInfo(General.PACKAGE_NAME, 0) }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        return listOfNotNull(
            File(external, "Android/media/${General.PACKAGE_NAME}/sesame-AG") to "",
            File(external, "Android/data/${General.PACKAGE_NAME}/files/logs") to "log/legacy-target-external",
            File(external, "Android/data/${General.MODULE_PACKAGE_NAME}/files/logs") to "log/legacy-module-external",
            target?.let { File(it.dataDir, "files/logs") to "log/legacy-target-internal" },
            File(context.filesDir, "logs") to "log/legacy-module-internal",
        )
    }

    private fun exists(reader: Reader, source: File): Boolean {
        val probe = generateSequence(source) { it.parentFile }.toList().asReversed().joinToString("; ") {
            val path = quote(it.path)
            "if [ ! -e $path ]; then printf absent; exit 0; fi; test -d $path && test -r $path && test -x $path || exit 1"
        }
        return when (reader.text("$probe; printf directory")) {
            "absent" -> false
            "directory" -> true
            else -> throw IOException("无法读取旧目录：$source")
        }
    }

    private suspend fun connect(context: Context): Reader {
        val status = CommandUtil.awaitServiceStatus(context) as? CommandUtil.ServiceStatus.Active
            ?: throw IOException("检查旧数据需要已授权的 Root 或 Root 模式 Shizuku")
        val reader = Reader.connect(context, status.type == "Shizuku")
        try {
            check(reader.text("/system/bin/id -u").trim() == "0") { "迁移执行器需要 UID 0" }
            return reader
        } catch (failure: Throwable) { reader.close(); throw failure }
    }

    suspend fun inspect(context: Context) = withContext(Dispatchers.IO) {
        operations.submit {
            if (PrivateStorage.isReady || !PrivateStorage.state.value.needsInspection) return@submit
            PrivateStorage.update(PrivateStorage.State(busy = true, message = "正在检查原有数据"))
            try {
                connect(context).use { reader ->
                    val diagnostics = File(context.filesDir, "logs/diagnostics").path
                    val hasData = roots(context).any { (root, _) ->
                        exists(reader, root) && reader.text("find ${quote(root.path)} -path ${quote(diagnostics)} -prune -o -type f -print").isNotEmpty()
                    }
                    if (hasData) {
                        PrivateStorage.update(PrivateStorage.State(message = "发现原有数据，可先导出备份再迁移"))
                    } else {
                        val empty = File(context.noBackupFilesDir, "store-initializing")
                        check(!empty.exists() || empty.deleteRecursively())
                        check(empty.mkdirs())
                        StorageBackup.validateAndRecover(empty)
                        StoreAccess.installSnapshot(empty)
                        PrivateStorage.update(PrivateStorage.state.value.copy(busy = false))
                    }
                }
            } catch (failure: Exception) {
                PrivateStorage.update(PrivateStorage.State(message = failure.message ?: "无法检查原有数据，请授权后重试", needsInspection = true))
            }
        }
    }

    suspend fun run(context: Context) = transfer(context, null)
    suspend fun backup(context: Context, uri: Uri) = transfer(context, uri)

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        operations.submit {
            check(!PrivateStorage.isReady)
            val snapshot = StorageBackup.read(context, uri)
            try {
                connect(context).use { reader ->
                    reader.text("am force-stop --user 0 ${General.PACKAGE_NAME}")
                    val cleanup = copyLegacy(context, reader, null)
                    StoreAccess.atomicWrite(File(work(context), "cleanup.json"), cleanup.toString().byteInputStream())
                    PrivateStorage.update(PrivateStorage.state.value.copy(cleanupPending = true))
                    StoreAccess.installSnapshot(snapshot.directory)
                    cleanupLocked(context, reader)
                }
            } catch (failure: Throwable) {
                snapshot.directory.deleteRecursively()
                if (!PrivateStorage.isReady) throw failure
                PrivateStorage.update(PrivateStorage.state.value.copy(
                    message = "备份已恢复，旧数据清理未完成：${failure.message}", cleanupPending = hasCleanup(context)))
            }
        }
    }

    private suspend fun transfer(context: Context, backupUri: Uri?) = withContext(Dispatchers.IO) {
        operations.submit {
            if (PrivateStorage.isReady) return@submit
            PrivateStorage.update(PrivateStorage.State(busy = true, message = if (backupUri == null) "正在迁移配置与日志" else "正在导出原有数据备份"))
            val staging = File(work(context), "pending")
            try {
                connect(context).use { reader ->
                    reader.text("am force-stop --user 0 ${General.PACKAGE_NAME}")
                    check(!PrivateStorage.root.exists()) { "私有目录未完成提交，请保留现场并处理后重试" }
                    check(!staging.exists() || staging.deleteRecursively())
                    check(staging.mkdirs())
                    val manifest = copyLegacy(context, reader, staging)
                    if (backupUri != null) {
                        StorageBackup.exportDirectory(context, backupUri, staging)
                        check(staging.deleteRecursively()) { "备份已导出，无法清理迁移暂存文件" }
                        PrivateStorage.update(PrivateStorage.State(message = "备份已导出，可以继续迁移"))
                    } else {
                        StorageBackup.validateAndRecover(staging)
                        StoreAccess.atomicWrite(File(work(context), "cleanup.json"), manifest.toString().byteInputStream())
                        PrivateStorage.update(PrivateStorage.state.value.copy(cleanupPending = true))
                        StoreAccess.installSnapshot(staging)
                        cleanupLocked(context, reader)
                    }
                }
            } catch (failure: Exception) {
                PrivateStorage.update(PrivateStorage.State(ready = File(PrivateStorage.root, ".store-ready").isFile,
                    message = failure.message ?: "操作失败，旧数据已保留", cleanupPending = hasCleanup(context)))
                android.util.Log.e("StorageMigration", "Transfer failed", failure)
                if (backupUri != null) throw failure
            }
        }
    }

    private fun copyLegacy(context: Context, reader: Reader, staging: File?): JSONObject {
        val roots = roots(context)
        val entries = JSONArray()
        val retainedDiagnostics = File(context.filesDir, "logs/diagnostics").path
        roots.forEach { (source, prefix) ->
            if (!exists(reader, source)) return@forEach
            check(reader.text("test ! -L ${quote(source.path)} && find ${quote(source.path)} -type l -print").isBlank()) { "旧目录包含链接，请处理后重试：$source" }
            val directories = reader.text("find ${quote(source.path)} -path ${quote(retainedDiagnostics)} -prune -o -type d -print0")
                .split('\u0000').filter { it.isNotEmpty() }
            if (staging != null) directories.forEach { absolute ->
                val relative = absolute.removePrefix(source.path).removePrefix("/")
                val destination = File(staging, listOf(prefix, relative).filter { it.isNotEmpty() }.joinToString("/"))
                check(destination.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath()))
                check(destination.isDirectory || destination.mkdirs()) { "无法创建迁移目录：$relative" }
            }
            val filesCommand = "find ${quote(source.path)} -path ${quote(retainedDiagnostics)} -prune -o -type f -print0"
            val names = reader.text(filesCommand).split('\u0000').filter { it.isNotEmpty() }
            names.forEach { absolute ->
                val relative = absolute.removePrefix(source.path + "/")
                check(relative != absolute && relative.split('/').none { it == ".." || it.isEmpty() })
                val targetName = if (prefix.isEmpty()) relative else "$prefix/$relative"
                val properties = reader.text("stat -c '%s %Y' -- ${quote(absolute)}").trim().split(' ')
                val length = properties[0].toLong()
                val modified = properties[1].toLong() * 1000L
                val before = reader.digest(absolute)
                if (staging != null) {
                    val target = File(staging, targetName)
                    check(staging.usableSpace >= length + 1024L * 1024L) { "私有目录空间不足，旧数据已保留" }
                    reader.copy(absolute, target)
                    check(target.length() == length && StorageBackup.digest(target) == before) { "文件复制不完整：$relative" }
                    check(target.setLastModified(modified)) { "无法保留文件时间：$relative" }
                }
                check(reader.digest(absolute) == before) { "源文件在迁移中变化：$relative" }
                entries.put(JSONObject().put("source", absolute).put("target", targetName).put("sha256", before).put("length", length))
            }
            val after = reader.text(filesCommand).split('\u0000').filter { it.isNotEmpty() }
            check(names.toSet() == after.toSet()) { "旧目录在迁移中变化：$source" }
        }
        return JSONObject().put("entries", entries).put("roots", JSONArray(roots.map { it.first.path }))
            .put("retainedDiagnostics", retainedDiagnostics)
    }

    suspend fun cleanup(context: Context) = withContext(Dispatchers.IO) {
        operations.submit {
            if (!hasCleanup(context)) return@submit
            try {
                PrivateStorage.requireReady()
                val status = CommandUtil.awaitServiceStatus(context) as? CommandUtil.ServiceStatus.Active
                    ?: throw IOException("清理旧数据需要已授权的执行器")
                Reader.connect(context, status.type == "Shizuku").use { reader ->
                    check(reader.text("/system/bin/id -u").trim() == "0")
                    cleanupLocked(context, reader)
                }
            } catch (failure: Exception) {
                PrivateStorage.update(PrivateStorage.State(ready = true, message = failure.message ?: "旧副本清理失败", cleanupPending = true))
            }
        }
    }

    private fun cleanupLocked(context: Context, reader: Reader) {
        val record = File(work(context), "cleanup.json")
        if (!record.exists()) return
        val manifest = JSONObject(record.readText())
        val entries = manifest.getJSONArray("entries")
        val pending = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val source = entry.getString("source")
            val roots = manifest.getJSONArray("roots")
            require((0 until roots.length()).any { source.startsWith(roots.getString(it) + "/") })
            val removed = runCatching {
                val exists = reader.text("if [ -e ${quote(source)} ]; then printf present; else printf absent; fi")
                exists == "absent" || (reader.digest(source) == entry.getString("sha256") &&
                    reader.text("rm -- ${quote(source)}; test ! -e ${quote(source)}").isEmpty())
            }.getOrDefault(false)
            if (!removed) pending.put(entry)
        }
        manifest.put("entries", pending)
        val roots = manifest.getJSONArray("roots")
        var remaining = pending.length() > 0
        val retainedDiagnostics = manifest.getString("retainedDiagnostics")
        for (index in 0 until roots.length()) {
            val root = roots.getString(index)
            val result = runCatching {
                if (root == File(context.filesDir, "logs").path) {
                    reader.text("if [ -d ${quote(root)} ]; then find ${quote(root)} -depth -type d ! -path ${quote(root)} ! -path ${quote(retainedDiagnostics)} ! -path ${quote("$retainedDiagnostics/*")} -exec rmdir {} \\; 2>/dev/null; find ${quote(root)} -path ${quote(retainedDiagnostics)} -prune -o -mindepth 1 -print; fi")
                } else {
                    reader.text("if [ -d ${quote(root)} ]; then find ${quote(root)} -depth -type d -exec rmdir {} \\; 2>/dev/null; fi; if [ -e ${quote(root)} ]; then printf remains; fi")
                }
            }.getOrDefault("remains")
            if (result.isNotEmpty()) remaining = true
        }
        if (!remaining) check(work(context).deleteRecursively()) { "旧数据已清理，迁移记录清理失败" }
        else StoreAccess.atomicWrite(record, manifest.toString().byteInputStream())
        PrivateStorage.update(PrivateStorage.State(ready = true, message = if (remaining) "新目录已启用，部分旧副本待清理" else "数据迁移完成", cleanupPending = remaining))
    }

}
