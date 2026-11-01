package io.github.aoguai.sesameag.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import io.github.aoguai.sesameag.util.settingsTransfer.ResolvedSettingsImport
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferApplyResult
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferApplier
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExportMode
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExporter
/**
 * Utility class for handling import and export operations.
 */
object PortUtil {

    @JvmStatic
    fun handleExport(
        context: Context,
        uri: Uri?,
        userId: String?,
        exportMode: SettingsTransferExportMode
    ): Boolean {
        if (uri == null) {
            ToastUtil.showToast("未选择保存位置")
            return false
        }
        try {
            val json = SettingsTransferExporter.exportJson(userId, exportMode)
            val outputStream = context.contentResolver.openOutputStream(uri, "wt")
            if (outputStream == null) {
                ToastUtil.showToast("导出失败：无法写入所选位置")
                return false
            }

            outputStream.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val saved = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法校验导出文件" }
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(saved == json) { "导出文件写入不完整" }
            ToastUtil.showToast("导出成功")
            return true
        } catch (e: Throwable) {
            Log.printStackTrace(e)
            ToastUtil.showToast("导出失败，请稍后重试")
            return false
        }
    }

    @JvmStatic
    fun readImportText(context: Context, uri: Uri?): String? {
        if (uri == null) {
            ToastUtil.showToast(context, "导入失败：未选择文件")
            return null
        }
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                ToastUtil.showToast(context, "导入失败：无法读取所选文件")
                return null
            }
            inputStream.use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            Log.printStackTrace(e)
            ToastUtil.showToast(context, "导入失败，请检查文件后重试")
            return null
        }
        return null
    }

    @JvmStatic
    suspend fun applyImport(context: Context, userId: String?, resolvedImport: ResolvedSettingsImport): SettingsTransferApplyResult {
        val result = SettingsTransferApplier.apply(userId, resolvedImport)
        if (!result.success) {
            return result
        }
        return result.copy(message = notifyConfigReload(context, userId))
    }

    private suspend fun notifyConfigReload(context: Context, userId: String?): String {
        if (userId.isNullOrBlank()) return "默认配置已保存"
        val unconfirmed = "设置已保存，尚未确认运行态，请在目标应用中重新加载配置"
        // 配置已保存后最多等待八秒确认宿主运行态已重载；超时返回未确认提示，不延后保存本身。
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine<String> { continuation ->
                val receiver = object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (continuation.isActive) continuation.resume(resultData?.getString("message") ?: unconfirmed)
                    }
                }
                try {
                    val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
                        .setPackage("com.eg.android.AlipayGphone")
                        .putExtra("userId", userId)
                        .putExtra("configReload", true)
                        .putExtra("configReloadResult", receiver)
                    context.sendBroadcast(intent)
                } catch (failure: Throwable) {
                    Log.printStackTrace(failure)
                    if (continuation.isActive) continuation.resume(unconfirmed)
                }
            }
        } ?: unconfirmed
    }

}

