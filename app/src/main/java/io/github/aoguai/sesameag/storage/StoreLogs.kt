package io.github.aoguai.sesameag.storage

import android.os.ParcelFileDescriptor
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.Logback
import io.github.aoguai.sesameag.util.LogCatalog
import kotlin.concurrent.thread

object StoreLogs {
    fun open(channel: String, processName: String): ParcelFileDescriptor {
        PrivateStorage.requireReady()
        require(channel in LogCatalog.loggerNames() + listOf("other", "captcha"))
        val suffix = if (processName == General.PACKAGE_NAME) null else processName.removePrefix("${General.PACKAGE_NAME}:").also {
            require(it.startsWith("lite") && it.length > 4 && it.drop(4).all(Char::isDigit))
        }
        var logger = Logback.remoteLogger(channel, suffix)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        // 日志流会随宿主长驻，阻塞读取不能占用首页和导出共用的 IO 线程池。
        try {
            thread(isDaemon = true, name = "SesameStoreLog-$channel") {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine { line ->
                            if (!logger.iteratorForAppenders().hasNext()) logger = Logback.remoteLogger(channel, suffix)
                            logger.info("{}", line)
                        }
                    }
                } catch (failure: Exception) {
                    android.util.Log.e("StoreLogs", "Log stream closed", failure)
                }
            }
        } catch (failure: Throwable) {
            pipe.forEach { runCatching { it.close() } }
            throw failure
        }
        return pipe[1]
    }
}
