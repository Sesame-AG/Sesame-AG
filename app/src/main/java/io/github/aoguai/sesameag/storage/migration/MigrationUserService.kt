package io.github.aoguai.sesameag.storage.migration

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import io.github.aoguai.sesameag.data.General
import java.util.concurrent.Executors

/** Short-lived Shizuku transport, bound only while the user-requested migration is running. */
@Keep
class MigrationUserService(context: Context) : IMigrationReader.Stub() {
    private val moduleUid = context.packageManager.getApplicationInfo(General.MODULE_PACKAGE_NAME, 0).uid
    private val workers = Executors.newCachedThreadPool()

    override fun execute(command: String): ParcelFileDescriptor {
        check(Process.myUid() == 0 && Binder.getCallingUid() == moduleUid) { "Migration requires the module and UID 0" }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        workers.execute {
            var process: java.lang.Process? = null
            val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            try {
                process = ProcessBuilder("/system/bin/sh", "-c", command).redirectError(java.io.File("/dev/null")).start()
                process.inputStream.use { it.copyTo(output) }
                // 管道数据传输结束后核对进程退出码，确认读取/维护真正成功再正常关闭结果通道。
                check(process.waitFor() == 0) { "旧目录读取或维护失败" }
                output.close()
            } catch (failure: Exception) {
                runCatching { pipe[1].closeWithError(failure.message ?: "Migration operation failed") }
            } finally {
                process?.destroy()
                runCatching { output.close() }
            }
        }
        return pipe[0]
    }

    override fun destroy() {
        workers.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
