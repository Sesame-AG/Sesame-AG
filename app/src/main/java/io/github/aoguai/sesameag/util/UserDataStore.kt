package io.github.aoguai.sesameag.util

import android.annotation.SuppressLint
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/**
 * 用户特异性数据存储类 (UserDataStore)
 * 每个实例绑定一个特定的 UID，存储在对应的用户目录下。
 */
class UserDataStore(val uid: String, private val storageFile: File) {
    private val tag = "UserDataStore-$uid"

    companion object {
        private val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        private val prettyPrinter = DefaultPrettyPrinter().apply {
            indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
            indentObjectsWith(DefaultIndenter("    ", DefaultIndenter.SYS_LF))
        }
    }

    private val data = ConcurrentHashMap<String, Any>()
    private val lock = ReentrantReadWriteLock()

    private val lastLoadedTime = AtomicLong(0)
    private val lastWriteTime = AtomicLong(0)

    @Volatile
    private var watcherJob: Job? = null

    @Volatile
    private var watchService: WatchService? = null

    private var onChangeListener: (() -> Unit)? = null

    init {
        ensureFileExists()
        loadFromDisk()
        startWatcherNio()
    }

    fun setOnChangeListener(listener: () -> Unit) {
        onChangeListener = listener
    }

    private fun ensureFileExists() {
        try {
            val dir = storageFile.parentFile ?: return
            if (!dir.exists()) {
                dir.mkdirs()
            }
            setWorldReadableWritable(dir)

            if (!storageFile.exists()) {
                storageFile.createNewFile()
                setWorldReadableWritable(storageFile)
                storageFile.writeText("{}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to ensure storage file exists", e)
        }
    }

    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    private fun setWorldReadableWritable(file: File) {
        try {
            file.setReadable(true, false)
            file.setWritable(true, false)
            file.setExecutable(true, false)
        } catch (_: Exception) {}
    }

    fun shutdown() {
        watcherJob?.cancel()
        watcherJob = null
        try {
            watchService?.close()
        } catch (_: Throwable) {}
        watchService = null
        Log.i(tag, "Watcher stopped for user $uid")
    }

    inline fun <reified T : Any> getOrCreate(key: String): T {
        return getOrCreate(key, object : TypeReference<T>() {})
    }

    fun <T> get(key: String, clazz: Class<T>): T? = lock.read {
        try {
            data[key]?.let { mapper.convertValue(it, clazz) }
        } catch (e: Exception) {
            Log.e(tag, "Error converting value for key: $key", e)
            null
        }
    }

    fun <T : Any> getOrCreate(key: String, typeRef: TypeReference<T>): T = lock.write {
        forceLoadFromDisk()
        data[key]?.let {
            try {
                return mapper.convertValue(it, typeRef)
            } catch (e: Exception) {
                Log.w(tag, "Data mismatch for key $key, overwriting with default.", e)
            }
        }

        val default: T = createDefault(typeRef)
        data[key] = default
        saveToDisk()
        default
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createDefault(typeRef: TypeReference<T>): T {
        val javaType = mapper.typeFactory.constructType(typeRef)
        val rawClass = javaType.rawClass
        return when {
            List::class.java.isAssignableFrom(rawClass) -> ArrayList<Any>() as T
            Set::class.java.isAssignableFrom(rawClass) -> LinkedHashSet<Any>() as T
            Map::class.java.isAssignableFrom(rawClass) -> LinkedHashMap<String, Any>() as T
            rawClass == String::class.java -> "" as T
            rawClass == Boolean::class.java || rawClass == Boolean::class.javaObjectType -> false as T
            rawClass == Int::class.java || rawClass == Int::class.javaObjectType -> 0 as T
            rawClass == Long::class.java || rawClass == Long::class.javaObjectType -> 0L as T
            else -> {
                try {
                    rawClass.getDeclaredConstructor().newInstance() as T
                } catch (e: Exception) {
                    throw RuntimeException("Could not create default value for ${rawClass.name}", e)
                }
            }
        }
    }

    private fun forceLoadFromDisk() {
        try {
            if (!storageFile.exists() || storageFile.length() == 0L) {
                data.clear()
                lastLoadedTime.set(0)
                return
            }
            val currentModTime = storageFile.lastModified()
            if (currentModTime <= lastLoadedTime.get()) return

            val loaded: Map<String, Any> = mapper.readValue(storageFile)
            data.clear()
            data.putAll(loaded)
            lastLoadedTime.set(currentModTime)
        } catch (e: Exception) {
            if (e !is MismatchedInputException) {
                Log.w(tag, "Force load from disk failed: ${e.message}")
            }
        }
    }

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        val currentModTime = storageFile.lastModified()
        if (currentModTime <= lastLoadedTime.get()) return
        if (abs(currentModTime - lastWriteTime.get()) < 500) {
            lastLoadedTime.set(currentModTime)
            return
        }

        lock.write {
            try {
                if (storageFile.length() == 0L) return@write
                val loaded: Map<String, Any> = mapper.readValue(storageFile)
                data.clear()
                data.putAll(loaded)
                lastLoadedTime.set(currentModTime)
                onChangeListener?.invoke()
            } catch (e: Exception) {
                if (e !is MismatchedInputException) {
                    Log.w(tag, "Failed to load: ${e.message}")
                }
            }
        }
    }

    private fun saveToDisk() {
        try {
            val tempFile = File(storageFile.parentFile, storageFile.name + ".tmp")
            mapper.writer(prettyPrinter).writeValue(tempFile, data)
            setWorldReadableWritable(tempFile)
            lastWriteTime.set(System.currentTimeMillis())
            var renameSuccess = tempFile.renameTo(storageFile)
            if (!renameSuccess) {
                if (storageFile.exists()) storageFile.delete()
                renameSuccess = tempFile.renameTo(storageFile)
            }
            if (!renameSuccess) {
                tempFile.copyTo(storageFile, overwrite = true)
                tempFile.delete()
            }
            setWorldReadableWritable(storageFile)
            lastLoadedTime.set(storageFile.lastModified())
        } catch (e: Exception) {
            Log.e(tag, "Failed to save config", e)
        }
    }

    private fun startWatcherNio() {
        watcherJob?.cancel()
        watcherJob = GlobalThreadPools.execute(Dispatchers.IO) {
            val path = storageFile.toPath().parent ?: return@execute
            val watch = runCatching {
                watchService?.close()
                path.fileSystem.newWatchService()
            }.getOrNull() ?: return@execute

            watchService = watch
            runCatching {
                path.register(watch, StandardWatchEventKinds.ENTRY_MODIFY)
            }.onFailure { e ->
                runCatching { watch.close() }
                Log.e(tag, "Failed to register watch service", e)
                return@execute
            }

            try {
                while (isActive) {
                    val key = try {
                        watch.poll(1, TimeUnit.SECONDS) ?: continue
                    } catch (_: Exception) { return@execute }

                    var shouldReload = false
                    key.pollEvents().forEach { event ->
                        val changedPath = event.context() as? Path
                        if (changedPath?.toString() == storageFile.name) {
                            shouldReload = true
                        }
                    }
                    if (shouldReload) {
                        delay(100)
                        loadFromDisk()
                    }
                    if (!key.reset()) return@execute
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(tag, "File watcher died", e)
            } finally {
                runCatching { watch.close() }
                if (watchService === watch) watchService = null
            }
        }
    }

    fun put(key: String, value: Any) = lock.write {
        forceLoadFromDisk()
        data[key] = value
        saveToDisk()
    }

    fun remove(key: String) = lock.write {
        forceLoadFromDisk()
        data.remove(key)
        saveToDisk()
    }

    /* --- 持续化标记 (Persistent Flags) 扩展 --- */

    /**
     * 设置一个持久化标记，直到指定的时间戳过期。
     * @param flag 标记名
     * @param expiryTimeMillis 过期时间戳（毫秒）。传入 -1 代表永久有效。
     */
    fun setPersistentFlag(flag: String, expiryTimeMillis: Long) {
        lock.write {
            val typeRef = object : TypeReference<MutableMap<String, Long>>() {}
            val flags = getOrCreate("persistent_flags", typeRef).toMutableMap()
            flags[flag] = expiryTimeMillis
            put("persistent_flags", flags)
        }
    }

    /**
     * 检查持久化标记是否有效（未过期）。
     */
    fun hasPersistentFlag(flag: String): Boolean {
        lock.read {
            val flags = data["persistent_flags"] as? Map<*, *> ?: return false
            val expiry = (flags[flag] as? Number)?.toLong() ?: return false
            if (expiry == -1L) return true
            return System.currentTimeMillis() < expiry
        }
    }

    /**
     * 移除持久化标记。
     */
    fun removePersistentFlag(flag: String) {
        lock.write {
            val typeRef = object : TypeReference<MutableMap<String, Long>>() {}
            val flags = getOrCreate("persistent_flags", typeRef).toMutableMap()
            if (flags.remove(flag) != null) {
                put("persistent_flags", flags)
            }
        }
    }
}
