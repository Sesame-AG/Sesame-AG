package io.github.aoguai.sesameag.service.patch

import com.niki.cmd.Shell
import com.niki.cmd.model.bean.ShellResult
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.io.File

internal class SafeRootShell : Shell {
    companion object {
        private const val TAG = "SafeRootShell"
    }

    override val TEST_TIMEOUT: Long = 20_000L
    override val PERMISSION_LEVEL: String = "Root"

    override suspend fun isAvailable(): Boolean {
        val result = runCommand("/system/bin/id -u", TEST_TIMEOUT, logFailure = false)

        val stdout = result.stdout.trim()
        val available = result.isSuccess && stdout == "0"
        if (!available) {
            Log.d(TAG, "Root不可用: Code=${result.exitCode}, Out='$stdout', Err='${result.stderr}'")
        }
        return available
    }

    override suspend fun exec(command: String): ShellResult {
        return runCommand(command, Long.MAX_VALUE)
    }

    override suspend fun exec(command: String, timeoutMillis: Long): ShellResult {
        return runCommand(command, timeoutMillis)
    }

    private suspend fun runCommand(
        cmd: String,
        timeoutMillis: Long,
        logFailure: Boolean = true
    ): ShellResult {
        return withContext(Dispatchers.IO) {
            try {
                if (timeoutMillis < Long.MAX_VALUE) {
                    // 这是调用方指定的协程超时预算，不是命令执行前后附加的定长休眠。
                    withTimeout(timeoutMillis) { executeSu(cmd) }
                } else {
                    executeSu(cmd)
                }
            } catch (e: Exception) {
                if (logFailure) {
                    Log.e(TAG, "命令执行异常: ${e.message}")
                } else {
                    Log.d(TAG, "Root探测失败: ${e.message}")
                }
                ShellResult.error(e.message ?: "Execution failed")
            }
        }
    }

    /**
     * 🔥 核心修复：使用 String[] 数组传参
     * 这样 Java 就不会因为空格或引号而错误地切分命令了
     */
    private fun executeSu(command: String): ShellResult {
        val failures = ArrayList<String>()
        for (suPath in resolveSuCandidates()) {
            try {
                val directResult = runSuWithArgs(suPath, command)
                if (directResult.isSuccess && (command != "/system/bin/id -u" || directResult.stdout.trim() == "0")) {
                    return directResult
                }

                val interactiveResult = runSuInteractively(suPath, command)
                if (interactiveResult.isSuccess && (command != "/system/bin/id -u" || interactiveResult.stdout.trim() == "0")) {
                    return interactiveResult
                }

                failures.add(
                    "$suPath -> direct(code=${directResult.exitCode}, err=${directResult.stderr}); " +
                        "interactive(code=${interactiveResult.exitCode}, err=${interactiveResult.stderr})"
                )
            } catch (t: Throwable) {
                failures.add("$suPath -> ${t.message}")
            }
        }
        throw IllegalStateException(
            failures.joinToString(" | ").ifBlank { "未找到可用的 su 可执行文件" }
        )
    }

    /** Select once before migration; never retry a filesystem mutation on another su path. */
    internal fun streamingCommandFactory(): (String) -> Process {
        for (suPath in resolveSuCandidates()) {
            val direct = runCatching { runSuWithArgs(suPath, "/system/bin/id -u") }.getOrNull()
            if (direct?.isSuccess == true && direct.stdout.trim() == "0") {
                return { command ->
                    ProcessBuilder(suPath, "-c", command).redirectError(File("/dev/null")).start()
                }
            }
            val interactive = runCatching { runSuInteractively(suPath, "/system/bin/id -u") }.getOrNull()
            if (interactive?.isSuccess == true && interactive.stdout.trim() == "0") {
                return { command ->
                    val process = ProcessBuilder(suPath).redirectError(File("/dev/null")).start()
                    try {
                        process.outputStream.use { it.write((command + "\nexit\n").toByteArray(Charsets.UTF_8)) }
                        process
                    } catch (failure: Throwable) {
                        process.destroy()
                        throw failure
                    }
                }
            }
        }
        throw IllegalStateException("迁移没有可用的 Root 通道")
    }

    private fun resolveSuCandidates(): List<String> {
        val pathCandidates = System.getenv("PATH")
            .orEmpty()
            .split(":")
            .mapNotNull { dir ->
                val path = dir.trim()
                if (path.isEmpty()) null else path.trimEnd('/') + "/su"
            }

        return (
            listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/system/sbin/su",
                "/sbin/su",
                "/vendor/bin/su",
                "/su/bin/su",
                "/magisk/.core/bin/su",
                "/debug_ramdisk/su",
                "su"
            ) + pathCandidates
            ).distinct().filter { candidate ->
            !candidate.contains("/") || File(candidate).exists()
        }
    }

    private fun runSuWithArgs(suPath: String, command: String): ShellResult {
        val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
        // 退出码必须等待子进程真正结束才能读取；这里是执行完成同步，没有固定睡眠时长。
        val exitCode = process.waitFor()
        return ShellResult(stdout, stderr, exitCode)
    }

    private fun runSuInteractively(suPath: String, command: String): ShellResult {
        var process: Process? = null
        var output: DataOutputStream? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf(suPath))
            output = DataOutputStream(process.outputStream)
            output.writeBytes(command)
            output.writeBytes("\n")
            output.writeBytes("exit\n")
            output.flush()

            // 已写入 exit 后等待交互 shell 完成，以取得本次命令的真实退出码和输出。
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            ShellResult(stdout, stderr, exitCode)
        } finally {
            try {
                output?.close()
            } catch (_: Throwable) {
            }
            process?.destroy()
        }
    }

}

