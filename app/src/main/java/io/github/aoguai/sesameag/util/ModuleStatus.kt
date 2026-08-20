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
        val name = frameworkName?.trim().orEmpty()
        if (name.isEmpty()) return FrameworkCategory.UNSUPPORTED

        val lower = name.lowercase()
        // LSPatch / NPatch remain out of scope: they are in-APK patch loaders, not a system framework.
        if ("lspatch" in lower || "npatch" in lower) return FrameworkCategory.UNSUPPORTED

        // Official LSPosed name, plus JingMatrix Vector (the LSPosed repo rename / successor).
        // Vector 2.x may report "Vector", "JingMatrix-Vector", or "<id>-JingMatrix-Vector".
        if (name == "LSPosed") return FrameworkCategory.LSPOSED
        if (name.equals("Vector", ignoreCase = true)) return FrameworkCategory.LSPOSED
        if (name.contains("JingMatrix-Vector", ignoreCase = true)) return FrameworkCategory.LSPOSED
        if (lower.endsWith("-vector") || lower.endsWith(" vector")) return FrameworkCategory.LSPOSED

        return FrameworkCategory.UNSUPPORTED
    }

    fun isSupportedLsposedFramework(frameworkName: String?, apiVersion: Int): Boolean {
        return apiVersion >= MIN_SUPPORTED_LIBXPOSED_API &&
            classifyFrameworkName(frameworkName) == FrameworkCategory.LSPOSED
    }
}
