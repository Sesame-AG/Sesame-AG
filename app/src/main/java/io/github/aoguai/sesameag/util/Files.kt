@file:JvmName("Files") // 让 Java 调用时类名依然是 Files

package io.github.aoguai.sesameag.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.UserEntity
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

object Files {
    private val TAG = Files::class.java.simpleName

    data class ClearAllResult(
        val failedPaths: List<String>
    ) {
        val success: Boolean
            get() = failedPaths.isEmpty()
    }

    const val CONFIG_DIR_NAME = "sesame-AG"

    @JvmField
    val MAIN_DIR: File = getMainDir()

    @JvmField
    val CONFIG_DIR: File = getConfigDir()

    @JvmField
    val LOG_DIR: File = getLogDir()

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

    private fun getMainDir(): File {
        val storageDirStr = Environment.getExternalStorageDirectory().toString() +
                File.separator + "Android" + File.separator + "media" + File.separator + General.PACKAGE_NAME
        val storageDir = File(storageDirStr)
        val mainDir = File(storageDir, CONFIG_DIR_NAME)
        ensureDir(mainDir)
        return mainDir
    }

    private fun getLogDir(): File {
        val logDir = File(MAIN_DIR, "log")
        ensureDir(logDir)
        return logDir
    }

    private fun getConfigDir(): File {
        val configDir = File(MAIN_DIR, "config")
        ensureDir(configDir)
        return configDir
    }

    @JvmStatic
    fun getUserConfigDir(userId: String): File {
        val configDir = File(CONFIG_DIR, userId)
        ensureDir(configDir)
        return configDir
    }

    @JvmStatic
    fun getDefaultConfigV2File(): File {
        return File(CONFIG_DIR, "config_v2.json")
    }

    @JvmStatic
    @Synchronized
    fun setDefaultConfigV2File(json: String): Boolean {
        return write2File(json, File(CONFIG_DIR, "config_v2.json"))
    }

    @JvmStatic
    @Synchronized
    fun getConfigV2File(userId: String): File {
        var confV2File = File(CONFIG_DIR.toString() + File.separator + userId, "config_v2.json")
        // 如果新配置文件不存在，则尝试从旧配置文件迁移
        if (!confV2File.exists()) {
            val oldFile = File(CONFIG_DIR, "config_v2-$userId.json")
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
    @Synchronized
    fun setConfigV2File(userId: String, json: String): Boolean {
        return write2File(json, File(CONFIG_DIR.toString() + File.separator + userId, "config_v2.json"))
    }

    @JvmStatic
    fun getCustomSetFile(userId: String): File? {
        return getTargetFileofUser(userId, "customset.json")
    }

    @JvmStatic
    @Synchronized
    fun getTargetFileofUser(userId: String?, fullTargetFileName: String): File? {
        if (userId.isNullOrEmpty()) {
            Log.error(TAG, "Invalid userId for target file: $fullTargetFileName")
            return null
        }
        val userDir = File(CONFIG_DIR, userId)
        ensureDir(userDir)
        val targetFile = File(userDir, fullTargetFileName)
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
    @Synchronized
    fun getTargetFileofDir(dir: File, fullTargetFileName: String): File {
        ensureDir(dir)
        val targetFile = File(dir, fullTargetFileName)

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
    fun exportFile(file: File, hasTime: Boolean): File? {
        val exportFile = createExportTargetFile(file, hasTime) ?: return null
        val sensitiveKeywords = collectLogSensitiveKeywords()
        if (sensitiveKeywords.isEmpty()) {
            if (!copy(file, exportFile)) {
                Log.error(TAG, "Failed to copy file: ${file.absolutePath} to ${exportFile.absolutePath}")
                return null
            }
            return exportFile
        }

        if (!copyMaskedText(file, exportFile, sensitiveKeywords)) {
            Log.error(TAG, "Failed to export masked log: ${file.absolutePath} to ${exportFile.absolutePath}")
            return null
        }
        return exportFile
    }

    private fun createExportTargetFile(file: File, hasTime: Boolean): File? {
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            CONFIG_DIR_NAME
        )
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            Log.error(TAG, "Failed to create export directory: ${exportDir.absolutePath}")
            return null
        }

        val fileName = file.name
        val dotIndex = fileName.lastIndexOf('.')
        val fileNameWithoutExtension = if (dotIndex != -1) fileName.take(dotIndex) else fileName
        val fileExtension = if (dotIndex != -1) fileName.substring(dotIndex) else ""

        val newFileName = if (hasTime) {
            @SuppressLint("SimpleDateFormat")
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")
            val dateTimeString = simpleDateFormat.format(Date())
            "${fileNameWithoutExtension}_$dateTimeString$fileExtension"
        } else {
            "$fileNameWithoutExtension$fileExtension"
        }

        val exportFile = File(exportDir, newFileName)
        if (exportFile.exists() && exportFile.isDirectory) {
            if (!exportFile.delete()) {
                Log.error(TAG, "Failed to delete existing directory: ${exportFile.absolutePath}")
                return null
            }
        }
        return exportFile
    }

    private fun copyMaskedText(source: File, dest: File, sensitiveKeywords: List<String>): Boolean {
        val target = createFile(dest) ?: return false
        return try {
            source.bufferedReader(Charsets.UTF_8).use { reader ->
                target.bufferedWriter(Charsets.UTF_8).use { writer ->
                    var isFirstLine = true
                    reader.forEachLine { line ->
                        if (!isFirstLine) {
                            writer.newLine()
                        }
                        writer.write(maskSensitiveText(line, sensitiveKeywords))
                        isFirstLine = false
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (target.exists()) {
                target.delete()
            }
            Log.printStackTrace(TAG, "copyMaskedText failed", e)
            false
        }
    }

    private fun maskSensitiveText(text: String, sensitiveKeywords: List<String>): String {
        var sanitized = text
        sensitiveKeywords.forEach { keyword ->
            sanitized = sanitized.replace(keyword, "***")
        }
        return sanitized
    }

    private fun collectLogSensitiveKeywords(): List<String> {
        val keywords = linkedSetOf<String>()
        CONFIG_DIR.listFiles()?.filter { it.isDirectory }?.forEach { userDir ->
            collectKeywordsFromSelfFile(File(userDir, "self.json"), keywords)
            collectKeywordsFromFriendFile(File(userDir, "friend.json"), keywords)
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
        val cityCodeFile = File(MAIN_DIR, "cityCode.json")
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
    fun getLogFile(channel: LogChannel): File = ensureLogFile(channel.fileName)

    @JvmStatic
    fun readFromFile(f: File): String {
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
    @Synchronized
    fun write2File(s: String, f: File): Boolean {
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
     * 用户主动导出的备份位于 Downloads，不属于这里的模块存储范围。
     */
    @JvmStatic
    @Synchronized
    fun clearAllModuleData(context: Context): ClearAllResult {
        val failedPaths = linkedSetOf<String>()
        val targets = linkedSetOf<File>()

        targets += MAIN_DIR
        context.getExternalFilesDir("logs")?.let { targets += it }
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
            Log.record(TAG, "删除失败，重试中: ${file.absolutePath}")
            CoroutineUtils.sleepCompat(500)
        }
        Log.error(TAG, "删除失败: ${file.absolutePath}")
        return false
    }
}

