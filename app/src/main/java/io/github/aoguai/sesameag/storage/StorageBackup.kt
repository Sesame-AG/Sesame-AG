package io.github.aoguai.sesameag.storage

import android.content.Context
import android.net.Uri
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.data.General
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Full-store backups are independent of the removable legacy-directory migration. */
object StorageBackup {
    data class Entry(val name: String, val length: Long, val sha256: String)
    data class Snapshot(val directory: File, val entries: List<Entry>)

    suspend fun export(context: Context, uri: Uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            exportDirectory(context, uri, PrivateStorage.root)
        }
    }

    internal fun exportDirectory(context: Context, uri: Uri, root: File) {
        val archive = File.createTempFile("store-backup-", ".zip", context.cacheDir)
        try {
            val manifest = JSONArray()
            val writeArchive = {
                ZipOutputStream(archive.outputStream().buffered()).use { output ->
                    root.walkTopDown().forEach { file ->
                        if (file == root) return@forEach
                        val name = file.relativeTo(root).invariantSeparatorsPath
                        if (name == ".store-ready") return@forEach
                        require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "备份目录包含链接：$name" }
                        output.putNextEntry(ZipEntry("store/$name" + if (file.isDirectory) "/" else "").apply {
                            time = file.lastModified()
                        })
                        if (file.isFile) {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var length = 0L
                            file.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    length += count
                                }
                            }
                            manifest.put(JSONObject().put("name", name).put("length", length).put("sha256",
                                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
                        } else check(file.isDirectory) { "无法备份文件：$name" }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("backup.json"))
                    output.write(JSONObject().put("format", "sesame-storage-backup").put("files", manifest).toString().toByteArray(Charsets.UTF_8))
                    output.closeEntry()
                }
            }
            if (root == PrivateStorage.root) StoreAccess.transaction(writeArchive) else writeArchive()
            val destination = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法创建备份文件")
            destination.use { output -> archive.inputStream().use { it.copyTo(output) }; output.flush() }
            val saved = context.contentResolver.openInputStream(uri) ?: throw IOException("无法校验已导出的备份")
            check(saved.use(::digest) == digest(archive)) { "备份文件写入不完整，请重新导出" }
        } finally { archive.delete() }
    }

    internal fun read(context: Context, uri: Uri): Snapshot {
        val archive = File.createTempFile("store-restore-", ".zip", context.cacheDir)
        val directory = File(context.noBackupFilesDir, "store-restore-pending")
        check(!directory.exists() || directory.deleteRecursively()) { "无法清理上次恢复的临时文件" }
        check(directory.mkdirs())
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取备份文件")
            source.use { input -> archive.outputStream().use { copyWithSpaceCheck(input, it, archive.parentFile!!) } }
            val records = arrayListOf<Entry>()
            ZipFile(archive).use { zip ->
                val manifest = zip.getEntry("backup.json") ?: throw IOException("请选择完整配置备份 ZIP")
                val metadata = JSONObject(zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { it.readText() })
                require(metadata.getString("format") == "sesame-storage-backup") { "不支持的备份格式" }
                val files = metadata.getJSONArray("files")
                val expected = linkedMapOf<String, Entry>()
                for (index in 0 until files.length()) {
                    val item = files.getJSONObject(index)
                    val entry = Entry(item.getString("name"), item.getLong("length"), item.getString("sha256"))
                    validateName(entry.name)
                    require(entry.length >= 0 && expected.put(entry.name, entry) == null) { "备份清单包含重复或无效文件" }
                }
                val seen = hashSetOf<String>()
                var manifestCount = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "backup.json") { manifestCount++; continue }
                    require(entry.name.startsWith("store/")) { "备份包含未知资源" }
                    val name = entry.name.removePrefix("store/").removeSuffix("/")
                    validateName(name)
                    require(seen.add(name)) { "备份包含重复资源：$name" }
                    val target = File(directory, name)
                    require(target.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "无法创建备份目录：$name" }
                    } else {
                        val record = expected[name] ?: throw IOException("备份文件不在清单中：$name")
                        check(directory.usableSpace >= record.length + 1024L * 1024L) { "私有目录空间不足，原数据未改变" }
                        check(entry.size == record.length) { "备份文件长度与清单不一致：$name" }
                        zip.getInputStream(entry).use { input -> StoreAccess.atomicWrite(target, input) }
                        check(target.length() == record.length && digest(target) == record.sha256) { "备份校验失败：$name" }
                        if (entry.time >= 0) target.setLastModified(entry.time)
                        records.add(record)
                    }
                }
                require(manifestCount == 1 && records.map { it.name }.toSet() == expected.keys) { "备份内容与清单不一致" }
            }
            validateAndRecover(directory)
            return Snapshot(directory, records)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        } finally { archive.delete() }
    }

    suspend fun restore(context: Context, uri: Uri): Snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        PrivateStorage.requireReady()
        val snapshot = read(context, uri)
        try {
            val result = CommandUtil.executeCommand(context, "am force-stop --user 0 ${General.PACKAGE_NAME} && printf stopped")
            check(result?.trim() == "stopped") { "无法停止目标应用，原配置未改变" }
            StoreAccess.installSnapshot(snapshot.directory)
            snapshot
        } catch (failure: Throwable) {
            snapshot.directory.deleteRecursively()
            throw failure
        }
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty() && name != ".store-ready" && '\u0000' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "备份资源路径无效" }
    }

    private fun copyWithSpaceCheck(input: java.io.InputStream, output: java.io.OutputStream, directory: File) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(directory.usableSpace >= count + 1024L * 1024L) { "备份暂存空间不足" }
            output.write(buffer, 0, count)
        }
    }

    internal fun digest(file: File): String = file.inputStream().use(::digest)
    private fun digest(input: java.io.InputStream): String {
        val value = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; value.update(buffer, 0, count) }
        return value.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun validateAndRecover(root: File) {
        val config = File(root, "config")
        config.listFiles()?.filter { it.isDirectory }?.forEach { account ->
            val journal = File(account, ".account-transaction.json")
            val committed = File(account, ".account-transaction.committed")
            if (journal.exists() && !committed.exists()) {
                val backup = JSONObject(journal.readText(Charsets.UTF_8))
                backup.keys().forEach { name ->
                    require(name.isNotEmpty() && name.none { it == '/' || it == '\\' } && !name.startsWith('.'))
                    val file = File(account, name)
                    if (backup.isNull(name)) check(!file.exists() || file.delete())
                    else StoreAccess.atomicWrite(file, backup.getString(name).byteInputStream(Charsets.UTF_8))
                }
            }
            check(!journal.exists() || journal.delete()); check(!committed.exists() || committed.delete())
        }
        val objectFiles = setOf("config_v2.json", "DataStore.json", "UserDataStore.json", "self.json",
            "account-slots.json", "status.json", "statistics.json", "runtime.json", "customset.json", "friendCenter.json")
        config.walkTopDown().filter { it.isFile && (it.name in objectFiles ||
            (it.name.startsWith("config_v2-") && it.name.endsWith(".json"))) }.forEach {
            val body = it.readText(Charsets.UTF_8)
            check((body.isBlank() && it.name !in setOf("DataStore.json", "UserDataStore.json", "account-slots.json") &&
                !it.name.startsWith("config_v2")) || io.github.aoguai.sesameag.util.JsonUtil.toNode(body)?.isObject == true) {
                "配置内容无效：${it.relativeTo(root)}"
            }
        }
        File(config, "rpc_capture_session.json").takeIf { it.exists() }?.let {
            val history = File(root, "log/legacy-runtime")
            check(history.isDirectory || history.mkdirs())
            var destination = File(history, it.name)
            var suffix = 1
            while (destination.exists()) destination = File(history, "rpc_capture_session-${suffix++}.json")
            check(it.renameTo(destination)) { "无法保留旧抓包会话记录" }
        }
        check(config.isDirectory || config.mkdirs())
        val logs = File(root, "log")
        check(logs.isDirectory || logs.mkdirs())
    }
}
