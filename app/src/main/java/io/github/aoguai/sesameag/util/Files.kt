@file:JvmName("Files") // 让 Java 调用时类名依然是 Files

package io.github.aoguai.sesameag.util

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.storage.PrivateStorage
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.storage.StoreFile
import io.github.aoguai.sesameag.entity.UserEntity
import java.io.File
import java.io.FileWriter
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

object Files {
    private val TAG = Files::class.java.simpleName
    private val lockedAccounts = ThreadLocal.withInitial { mutableSetOf<String>() }

    /** 同一进程可重入，跨进程覆盖账号数据的整个读改写过程。 */
    fun <T> withAccountLock(userId: String, action: () -> T): T = StoreAccess.transaction {
        require(userId.isNotBlank()) { "账号为空" }
        io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.ensureCurrent(userId)
        val held = checkNotNull(lockedAccounts.get())
        if (userId in held) return@transaction action()
        held.add(userId)
        try { recoverAccountTransaction(userId); action() }
        finally { held.remove(userId) }
    }

    /** 备份先落盘；未提交的多文件变更在下次账号读取前恢复。 */
    fun <T> withAccountTransaction(userId: String, files: Collection<File>, action: () -> T): T =
        withAccountLock(userId) {
            val directory = getUserConfigDir(userId).canonicalFile
            val journal = child(directory, ".account-transaction.json")
            val committed = child(directory, ".account-transaction.committed")
            check(!journal.exists() && !committed.exists()) { "账号存在未恢复的事务" }
            val backup = JSONObject()
            files.distinctBy { it.canonicalPath }.forEach { file ->
                require(file.canonicalFile.parentFile == directory && !file.name.startsWith(".")) { "事务文件不属于目标账号" }
                backup.put(file.name, if (file.exists()) readText(file) else JSONObject.NULL)
            }
            check(writeAtomically(backup.toString(), journal)) { "保存账号事务备份失败" }
            val result = try {
                action().also {
                    io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.ensureCurrent(userId)
                    check(writeAtomically("committed", committed)) { "提交账号事务失败" }
                }
            } catch (failure: Throwable) {
                try {
                    recoverAccountTransaction(userId)
                } catch (rollback: Throwable) {
                    throw IOException("操作失败且恢复不完整：${rollback.message}", failure).also {
                        it.addSuppressed(rollback)
                    }
                }
                throw failure
            }
            runCatching { recoverAccountTransaction(userId) }.onFailure {
                Log.printStackTrace(TAG, "事务已提交，清理记录将在下次账号读取时重试", it)
            }
            result
        }

    private fun recoverAccountTransaction(userId: String) {
        val directory = getUserConfigDir(userId)
        val journal = child(directory, ".account-transaction.json")
        val committed = child(directory, ".account-transaction.committed")
        if (journal.exists() && !committed.exists()) {
            val backup = JSONObject(readText(journal))
            val names = backup.keys()
            val failed = mutableListOf<String>()
            while (names.hasNext()) {
                val name = names.next()
                val file = child(directory, name)
                require(file.canonicalFile.parentFile == directory.canonicalFile && !name.startsWith(".")) { "事务备份路径无效" }
                val restored = if (backup.isNull(name)) !file.exists() || file.delete()
                    else writeAtomically(backup.getString(name), file)
                if (!restored) failed.add(name)
            }
            check(failed.isEmpty()) { "无法恢复文件：${failed.joinToString()}" }
            Log.record(TAG, "已恢复未完成的账号文件事务")
        }
        check(!journal.exists() || journal.delete()) { "无法清理账号事务备份" }
        check(!committed.exists() || committed.delete()) { "无法清理账号事务提交记录" }
    }

    fun writeAtomically(text: String, file: File): Boolean {
        if (file is StoreFile) { StoreAccess.write(file, text); return true }
        var temporary: File? = null
        return try {
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "无法创建目录: $parent" }
            temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "原子保存失败: ${file.name}", e)
            false
        } finally {
            runCatching { temporary?.delete() }
        }
    }

    data class ClearAllResult(
        val failedPaths: List<String>
    ) {
        val success: Boolean
            get() = failedPaths.isEmpty()
    }

    const val CONFIG_DIR_NAME = "sesame-AG"

    @JvmField val MAIN_DIR: File = StoreFile.root
    @JvmField val CONFIG_DIR: File = StoreFile(StoreFile.root, "config")
    val LOG_DIR: File get() { PrivateStorage.requireReady(); return File(PrivateStorage.root, "log").apply { mkdirs() } }

    fun child(parent: File, name: String): File = if (parent is StoreFile) StoreFile(parent, name) else File(parent, name)

    fun localFile(file: File): File = if (file is StoreFile) {
        check(!StoreAccess.isRemote) { "宿主不能访问模块私有路径" }
        PrivateStorage.requireReady()
        file.localFile()
    } else file

    fun <T> withInputStream(file: File, block: (java.io.InputStream) -> T): T =
        if (file is StoreFile) StoreAccess.input(file, block) else file.inputStream().use(block)

    fun appendSynced(file: File, text: String) {
        if (file is StoreFile) StoreAccess.write(file, text, append = true)
        else FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }

    fun truncate(file: File, length: Long) {
        if (file is StoreFile) StoreAccess.truncate(file, length)
        else RandomAccessFile(file, "rw").use { it.setLength(length); it.fd.sync() }
    }

    fun readText(file: File): String = if (file is StoreFile) StoreAccess.read(file) else file.readText(Charsets.UTF_8)

    /**
     * 确保指定的目录存在且不是一个文件。
     */
    private fun ensureDir(directory: File?) {
        try {
            if (directory == null) {
                android.util.Log.e(TAG, "Directory cannot be null")
                return
            }
            if (!directory.exists()) {
                if (!directory.mkdirs()) {
                    android.util.Log.e(TAG, "Failed to create directory: ${directory.absolutePath}")
                }
            } else if (directory.isFile) {
                if (!directory.delete() || !directory.mkdirs()) {
                    android.util.Log.e(TAG, "Failed to replace file with directory: ${directory.absolutePath}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ensureDir error", e)
        }
    }

    @JvmStatic
    fun getUserConfigDir(userId: String): File {
        val configDir = child(CONFIG_DIR, userId)
        ensureDir(configDir)
        return configDir
    }

    /** Lists existing account configuration directories without creating or migrating files. */
    @JvmStatic
    @JvmOverloads
    fun listExistingUserConfigIds(requireReadable: Boolean = false): List<String> =
        (CONFIG_DIR.listFiles() ?: if (requireReadable) {
            throw IOException("account_config_directory_unreadable")
        } else null)
            ?.asSequence()
            ?.filter { directory ->
                directory.isDirectory && child(directory, "config_v2.json").isFile
            }
            ?.map { directory -> directory.name }
            ?.toList()
            .orEmpty()

    @JvmStatic
    fun getDefaultConfigV2File(): File {
        return child(CONFIG_DIR, "config_v2.json")
    }

    @JvmStatic
    fun setDefaultConfigV2File(json: String): Boolean {
        return write2File(json, child(CONFIG_DIR, "config_v2.json"))
    }

    @JvmStatic
    fun getConfigV2File(userId: String): File {
        var confV2File = child(child(CONFIG_DIR, userId), "config_v2.json")
        // 如果新配置文件不存在，则尝试从旧配置文件迁移
        if (!confV2File.exists()) {
            val oldFile = child(CONFIG_DIR, "config_v2-$userId.json")
            if (oldFile.exists()) {
                val content = readFromFile(oldFile)
                if (write2File(content, confV2File)) {
                    if (!oldFile.delete()) {
                        Log.error(TAG, "Failed to delete old config file: ${oldFile.absolutePath}")
                    }
                } else {
                    confV2File = oldFile
                    Log.error(TAG, "Failed to migrate config file for user: $userId")
                }
            }
        }
        return confV2File
    }

    @JvmStatic
    fun setConfigV2File(userId: String, json: String): Boolean {
        return write2File(json, child(child(CONFIG_DIR, userId), "config_v2.json"))
    }

    @JvmStatic
    fun getCustomSetFile(userId: String): File? {
        return getTargetFileofUser(userId, "customset.json")
    }

    @JvmStatic
    fun getTargetFileofUser(userId: String?, fullTargetFileName: String): File? {
        if (userId.isNullOrEmpty()) {
            Log.error(TAG, "Invalid userId for target file: $fullTargetFileName")
            return null
        }
        val userDir = child(CONFIG_DIR, userId)
        ensureDir(userDir)
        val targetFile = child(userDir, fullTargetFileName)
        if (!targetFile.exists()) {
            try {
                targetFile.createNewFile()
            } catch (e: IOException) {
                Log.printStackTrace(TAG, "Failed to create file: ${targetFile.name}", e)
            }
        } else {
            val canWrite = targetFile.canWrite()
//            Log.record(TAG, "$fullTargetFileName permissions: r=$canRead; w=$canWrite")
            if (!canWrite) {
                if (targetFile.setWritable(true)) {
                    Log.record(TAG, "${targetFile.name} write permission set successfully")
                } else {
                    Log.record(TAG, "${targetFile.name} write permission set failed")
                }
            }
        }
        return targetFile
    }

    @JvmStatic
    fun getTargetFileofDir(dir: File, fullTargetFileName: String): File {
        ensureDir(dir)
        val targetFile = child(dir, fullTargetFileName)

        if (!targetFile.exists()) {
            try {
                targetFile.createNewFile()
            } catch (e: IOException) {
                Log.printStackTrace(TAG, "Failed to create file: ${targetFile.absolutePath}",e)
            }
        } else {
            val canWrite = targetFile.canWrite()
//            Log.record(TAG, "File permissions for ${targetFile.absolutePath}: r=$canRead; w=$canWrite")
            if (!canWrite) {
                if (targetFile.setWritable(true)) {
                    Log.record(TAG, "Write permission set successfully for file: ${targetFile.absolutePath}")
                } else {
                    Log.record(TAG, "Write permission set failed for file: ${targetFile.absolutePath}")
                }
            }
        }
        return targetFile
    }

    @JvmStatic
    fun getSelfIdFile(userId: String?): File? {
        return getTargetFileofUser(userId, "self.json")
    }

    @JvmStatic
    fun getFriendIdMapFile(userId: String?): File? {
        return getTargetFileofUser(userId, "friend.json")
    }

    @JvmStatic
    fun getFriendCenterFile(userId: String): File {
        return getTargetFileofUser(userId, "friendCenter.json")!!
    }

    @JvmStatic
    fun runtimeInfoFile(userId: String?): File? {
        return getTargetFileofUser(userId, "runtime.json")
    }

    @JvmStatic
    fun getStatusFile(userId: String?): File? {
        return getTargetFileofUser(userId, "status.json")
    }

    @JvmStatic
    fun exportFile(context: Context, file: File, hasTime: Boolean, mimeType: String = "text/plain"): File? {
        val fileName = file.name
        val dotIndex = fileName.lastIndexOf('.')
        val fileNameWithoutExtension = if (dotIndex != -1) fileName.take(dotIndex) else fileName
        val fileExtension = if (dotIndex != -1) fileName.substring(dotIndex) else ""
        val newFileName = if (hasTime) {
            @SuppressLint("SimpleDateFormat")
            val dateTimeString = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(Date())
            "${fileNameWithoutExtension}_$dateTimeString$fileExtension"
        } else fileName
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$CONFIG_DIR_NAME/"
        val resolver = context.contentResolver
        var target: android.net.Uri? = null
        return try {
            val sensitiveKeywords = if (LogCatalog.findByFileName(file.name)?.techKind == LogTechKind.CAPTURE) {
                emptyList()
            } else collectLogSensitiveKeywords()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建下载文件")
            target = uri
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("无法打开下载文件")
            output.use { stream ->
                if (sensitiveKeywords.isEmpty()) {
                    file.inputStream().use { it.copyTo(stream) }
                } else {
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        file.bufferedReader(Charsets.UTF_8).use { reader ->
                            var firstLine = true
                            reader.forEachLine { line ->
                                if (!firstLine) writer.newLine()
                                writer.write(maskSensitiveText(line, sensitiveKeywords))
                                firstLine = false
                            }
                        }
                    }
                }
            }
            val exportedFile = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null,
            ).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) throw IOException("无法确认导出位置")
                File(Environment.getExternalStorageDirectory(), cursor.getString(0) + cursor.getString(1))
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "无法完成下载文件导出" }
            exportedFile
        } catch (e: Exception) {
            target?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            Log.printStackTrace(TAG, "日志导出到下载目录失败", e)
            null
        }
    }

    internal fun maskSensitiveText(text: String, sensitiveKeywords: List<String>): String {
        val capture = if (text.firstOrNull() == '{') {
            runCatching { JSONObject(text) }.getOrNull()
        } else null
        if (capture?.optString("schema") == "rpc-capture") {
            fun maskValue(value: Any?): Any? = when (value) {
                is JSONObject -> {
                    // 编码字节不属于文本，替换其中的字符会破坏字节长度与流偏移。
                    if (value.optString("encoding") == "base64" &&
                        value.opt("size") is Number && value.opt("data") is String
                    ) {
                        value
                    } else {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            value.put(key, maskValue(value.opt(key)))
                        }
                        value
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.put(index, maskValue(value.opt(index)))
                    }
                    value
                }
                is String -> sensitiveKeywords.fold(value) { result, keyword -> result.replace(keyword, "***") }
                else -> value
            }
            return maskValue(capture).toString()
        }
        var sanitized = text
        sensitiveKeywords.forEach { keyword ->
            sanitized = sanitized.replace(keyword, "***")
        }
        return sanitized
    }

    internal fun collectLogSensitiveKeywords(): List<String> {
        val keywords = linkedSetOf<String>()
        CONFIG_DIR.listFiles()?.filter { it.isDirectory }?.forEach { userDir ->
            collectKeywordsFromSelfFile(child(userDir, "self.json"), keywords)
            collectKeywordsFromFriendFile(child(userDir, "friend.json"), keywords)
        }
        return keywords.sortedByDescending { it.length }
    }

    private fun collectKeywordsFromSelfFile(file: File, keywords: MutableSet<String>) {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return
        try {
            val body = readFromFile(file)
            if (body.isBlank()) return
            val dto: UserEntity.UserDto? = JsonUtil.parseObject(
                body,
                object : TypeReference<UserEntity.UserDto>() {}
            )
            addUserSensitiveKeywords(dto?.toEntity(), keywords)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Failed to load self user info from ${file.absolutePath}", e)
        }
    }

    private fun collectKeywordsFromFriendFile(file: File, keywords: MutableSet<String>) {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return
        try {
            val body = readFromFile(file)
            if (body.isBlank()) return
            val dtoMap: Map<String, UserEntity.UserDto>? = JsonUtil.parseObject(
                body,
                object : TypeReference<Map<String, UserEntity.UserDto>>() {}
            )
            dtoMap?.values?.forEach { dto ->
                addUserSensitiveKeywords(dto.toEntity(), keywords)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Failed to load friend user info from ${file.absolutePath}", e)
        }
    }

    private fun addUserSensitiveKeywords(userEntity: UserEntity?, keywords: MutableSet<String>) {
        if (userEntity == null) return
        addSensitiveKeyword(keywords, userEntity.fullName)
        addSensitiveKeyword(keywords, userEntity.maskName)
        addSensitiveKeyword(keywords, userEntity.showName)
        addSensitiveKeyword(keywords, userEntity.realName)
        addSensitiveKeyword(keywords, userEntity.nickName)
        addSensitiveKeyword(keywords, userEntity.remarkName)
        addSensitiveKeyword(keywords, userEntity.account)
        addSensitiveKeyword(keywords, userEntity.userId)
        addDerivedSensitiveKeywords(keywords, userEntity)
    }

    private fun addDerivedSensitiveKeywords(keywords: MutableSet<String>, userEntity: UserEntity) {
        addSensitiveKeyword(keywords, userEntity.maskName.substringAfter('|', ""))
        addSensitiveKeyword(keywords, userEntity.fullName.substringAfter('|', ""))

        addCommonMaskedVariants(keywords, userEntity.showName)
        addCommonMaskedVariants(keywords, userEntity.realName)
        addCommonMaskedVariants(keywords, userEntity.nickName)
        addCommonMaskedVariants(keywords, userEntity.remarkName)
        addCommonMaskedVariants(keywords, userEntity.account)
    }

    private fun addCommonMaskedVariants(keywords: MutableSet<String>, value: String?) {
        val keyword = value?.trim().orEmpty()
        if (keyword.length < 2 || keyword == "***" || keyword.contains('*')) return

        addSensitiveKeyword(keywords, "*${keyword.substring(1)}")
        addSensitiveKeyword(keywords, "${keyword.first()}${"*".repeat(keyword.length - 1)}")

        if (keyword.length == 11 && keyword.all { it.isDigit() }) {
            addSensitiveKeyword(keywords, "${keyword.take(3)}****${keyword.takeLast(4)}")
        }
    }

    private fun addSensitiveKeyword(keywords: MutableSet<String>, value: String?) {
        val keyword = value?.trim().orEmpty()
        if (keyword.isEmpty() || keyword == "***") return
        if (keyword.length < 2 && keyword.none { it.isDigit() }) return
        keywords.add(keyword)
    }

    @JvmStatic
    fun getCityCodeFile(): File {
        val cityCodeFile = child(MAIN_DIR, "cityCode.json")
        if (cityCodeFile.exists() && cityCodeFile.isDirectory) {
            if (!cityCodeFile.delete()) {
                Log.error(TAG, "Failed to delete directory: ${cityCodeFile.absolutePath}")
            }
        }
        return cityCodeFile
    }

    private fun ensureLogFile(logFileName: String): File {
        val logFile = File(LOG_DIR, logFileName)
        if (logFile.exists() && logFile.isDirectory) {
            if (logFile.delete()) {
                Log.record(TAG, "日志${logFile.name}目录存在，删除成功！")
            } else {
                Log.error(TAG, "日志${logFile.name}目录存在，删除失败！")
            }
        }
        if (!logFile.exists()) {
            try {
                if (logFile.createNewFile()) {
                    Log.record(TAG, "日志${logFile.name}文件不存在，创建成功！")
                } else {
                    Log.error(TAG, "日志${logFile.name}文件不存在，创建失败！")
                }
            } catch (_: IOException) {
            }
        }
        return logFile
    }

    @JvmStatic
    fun getLogFile(channel: LogChannel): File =
        if (channel == LogChannel.SYSTEM) {
            checkNotNull(Logback.systemLogFile) { "System diagnostics not initialized" }
        } else ensureLogFile(channel.fileName)

    @JvmStatic
    fun readFromFile(f: File): String {
        if (f is StoreFile) return if (f.exists()) StoreAccess.read(f) else ""
        if (!f.exists()) return ""
        if (!f.canRead()) {
            ToastUtil.showToast("${f.name}没有读取权限！")
            return ""
        }
        // Kotlin 扩展方法：一行代码读取所有文本
        return try {
            f.readText()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
            ""
        }
    }

    private fun beforeWrite(f: File): Boolean {
        if (f.exists()) {
            if (!f.canWrite()) {
                ToastUtil.showToast("${f.absoluteFile}没有写入权限！")
                return true
            }
            if (f.isDirectory) {
                if (!f.delete()) {
                    ToastUtil.showToast("${f.absoluteFile}无法删除目录！")
                    return true
                }
            }
        } else {
            val parent = f.parentFile
            if (parent != null && !parent.mkdirs() && !parent.exists()) {
                ToastUtil.showToast("${f.absoluteFile}无法创建目录！")
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun write2File(s: String, f: File): Boolean {
        if (f is StoreFile) return writeAtomically(s, f)
        if (beforeWrite(f)) return false
        var fw: FileWriter? = null
        try {
            fw = FileWriter(f, false)
            fw.write(s)
            fw.flush()
            return true
        } catch (e: IOException) {
            Log.printStackTrace(TAG, e)
            return false
        } finally {
            if (fw != null) {
                try {
                    fw.close()
                } catch (e: IOException) {
                    Log.printStackTrace(TAG, "文件关闭异常（数据已写入）", e)
                }
            }
        }
    }

    private fun copy(source: File, dest: File): Boolean {
        // Kotlin 扩展方法，内部使用了 FileChannel 或 Files.copy
        return try {
            createFile(dest)?.let { target ->
                source.copyTo(target, overwrite = true)
                true
            } ?: false
        } catch (e: Exception) {
            Log.printStackTrace(e)
            false
        }
    }

    private fun createFile(file: File): File? {
        if (file.exists() && file.isDirectory) {
            if (!file.delete()) return null
        }
        if (!file.exists()) {
            try {
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) return null
            } catch (e: Exception) {
                Log.printStackTrace(e)
                return null
            }
        }
        return file
    }

    @JvmStatic
    fun clearFile(file: File): Boolean {
        if (file is StoreFile) return writeAtomically("", file)
        if (!file.exists()) return false
        return try {
            // Kotlin 扩展方法
            file.writeText("")
            true
        } catch (e: Exception) {
            Log.printStackTrace(e)
            false
        }
    }

    @JvmStatic
    fun delFile(file: File): Boolean {
        if (!file.exists()) {
            ToastUtil.showToast("${file.absoluteFile}不存在，无需删除")
            Log.record(TAG, "delFile: ${file.absoluteFile}不存在！,无须删除")
            return false
        }

        // Kotlin 提供了 deleteRecursively() 扩展，但它不带重试机制
        // 为了保留你的重试逻辑，我们依然手动实现
        if (file.isFile) {
            return deleteFileWithRetry(file)
        }

        val files = file.listFiles() ?: return deleteFileWithRetry(file)

        var allSuccess = true
        for (innerFile in files) {
            if (!delFile(innerFile)) {
                allSuccess = false
            }
        }
        return allSuccess && deleteFileWithRetry(file)
    }

    /**
     * 清理模块自有的全部持久化文件，但保留静态目录对象及其根目录本身。
     * 用户主动导出的备份由系统文件选择器保存，不属于模块存储范围。
     */
    @JvmStatic
    fun clearAllModuleData(context: Context): ClearAllResult {
        val failedPaths = linkedSetOf<String>()
        val targets = linkedSetOf<File>()

        runCatching { PrivateStorage.clearContents() }
            .onSuccess { failedPaths.addAll(it) }
            .onFailure { failedPaths += PrivateStorage.root.absolutePath }
        targets += File(context.filesDir, "logs")

        targets.forEach { directory ->
            clearDirectoryContents(directory, failedPaths)
        }

        ensureDir(MAIN_DIR)
        ensureDir(CONFIG_DIR)
        ensureDir(LOG_DIR)

        return ClearAllResult(failedPaths.toList())
    }

    private fun clearDirectoryContents(directory: File, failedPaths: MutableSet<String>) {
        if (!directory.exists()) return
        if (!directory.isDirectory) {
            if (!deleteFileWithRetry(directory)) {
                failedPaths += directory.absolutePath
            }
            return
        }

        val children = directory.listFiles()
        if (children == null) {
            failedPaths += directory.absolutePath
            return
        }
        children.forEach { child ->
            if (!delFile(child)) {
                failedPaths += child.absolutePath
            }
        }
    }

    private fun deleteFileWithRetry(file: File): Boolean {
        var retryCount = 3
        while (retryCount > 0) {
            if (file.delete()) {
                return true
            }
            retryCount--
            if (retryCount > 0) {
                Log.record(TAG, "删除失败，重试中: ${file.absolutePath}")
                // 文件删除失败时保留 500ms 本地退避，给临时占用释放机会；成功和最后一次失败后不再等待。
                CoroutineUtils.sleepCompat(500)
            }
        }
        Log.error(TAG, "删除失败: ${file.absolutePath}")
        return false
    }
}

