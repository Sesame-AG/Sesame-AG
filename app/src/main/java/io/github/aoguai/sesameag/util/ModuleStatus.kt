package io.github.aoguai.sesameag.util

/**
 * 模块状态与 libxposed 运行时解析。
 *
 * 只使用框架通过 libxposed 提供的官方名称和 API 版本。
 */
object ModuleStatus {
    // This object also runs in the standalone settings process, where the compileOnly API jar is absent.
    const val MIN_SUPPORTED_LIBXPOSED_API = 102

    private const val UNKNOWN_FRAMEWORK = "Unknown"

    enum class FrameworkCategory {
        LSPOSED,
        UNSUPPORTED,
    }

    data class FrameworkInfo(
        val displayName: String,
        val category: FrameworkCategory,
    )

    fun resolveFrameworkInfo(officialFrameworkName: String?): FrameworkInfo {
        val displayName = officialFrameworkName?.trim()?.takeIf { it.isNotBlank() } ?: UNKNOWN_FRAMEWORK
        return FrameworkInfo(displayName, classifyFrameworkName(displayName))
    }

    fun classifyFrameworkName(frameworkName: String?): FrameworkCategory {
        return if (frameworkName?.trim() == "LSPosed") {
            FrameworkCategory.LSPOSED
        } else {
            FrameworkCategory.UNSUPPORTED
        }
    }

    fun isSupportedLsposedFramework(frameworkName: String?, apiVersion: Int): Boolean {
        return apiVersion >= MIN_SUPPORTED_LIBXPOSED_API &&
            classifyFrameworkName(frameworkName) == FrameworkCategory.LSPOSED
    }
}
