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
        val result = runCommand("id && echo __SESAME_ROOT_OK__", TEST_TIMEOUT, logFailure = false)

        val stdout = result.stdout.trim()
        val available = result.isSuccess && (
            stdout.contains("uid=0") || stdout.contains("__SESAME_ROOT_OK__")
            )
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
                if (directResult.isSuccess || looksLikeRootProbeSucceeded(command, directResult.stdout)) {
                    return directResult
                }

                val interactiveResult = runSuInteractively(suPath, command)
                if (interactiveResult.isSuccess || looksLikeRootProbeSucceeded(command, interactiveResult.stdout)) {
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

    private fun looksLikeRootProbeSucceeded(command: String, stdout: String): Boolean {
        if (!command.contains("__SESAME_ROOT_OK__") && !command.startsWith("id")) {
            return false
        }
        return stdout.contains("uid=0") || stdout.contains("__SESAME_ROOT_OK__")
    }
}

