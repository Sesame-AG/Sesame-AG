package io.github.aoguai.sesameag.ui.permissions

enum class PermissionRequirement {
    MODULE_FILE,
    MODULE_NOTIFICATION,
    LSPOSED_TARGET_SCOPE,
    MODULE_EXACT_ALARM,
    TARGET_EXACT_ALARM,
    MODULE_BATTERY,
    TARGET_BATTERY,
    SHELL_EXECUTOR
}

enum class PermissionStatus {
    GRANTED,
    MISSING,
    REQUESTING,
    DENIED,
    UNAVAILABLE,
    UNSUPPORTED
}

enum class PermissionPolicy {
    AUTO_CRITICAL,
    MANUAL_CARD,
    OBSERVE_ONLY
}

data class PermissionHealthItem(
    val requirement: PermissionRequirement,
    val status: PermissionStatus,
    val policy: PermissionPolicy,
    val title: String,
    val description: String,
    val actionLabel: String? = null,
    val requestWhenUnavailable: Boolean = false
) {
    val isGranted: Boolean
        get() = status == PermissionStatus.GRANTED

    val needsAttention: Boolean
        get() = status == PermissionStatus.MISSING ||
            status == PermissionStatus.DENIED ||
            status == PermissionStatus.REQUESTING ||
            (requestWhenUnavailable && status == PermissionStatus.UNAVAILABLE)

    val canRequest: Boolean
        get() = actionLabel != null &&
            (
                status in setOf(PermissionStatus.MISSING, PermissionStatus.DENIED) ||
                    (requestWhenUnavailable && status == PermissionStatus.UNAVAILABLE)
                )
}

data class PermissionHealthSnapshot(
    val items: List<PermissionHealthItem> = emptyList(),
    val refreshedAtMs: Long = System.currentTimeMillis()
) {
    val totalCount: Int
        get() = items.size

    val grantedCount: Int
        get() = items.count { it.status == PermissionStatus.GRANTED }

    val attentionCount: Int
        get() = items.count { it.needsAttention }

    val requestableCount: Int
        get() = items.count { it.canRequest }

    val hasRequestableIssue: Boolean
        get() = requestableCount > 0

    val hasCriticalIssue: Boolean
        get() = items.any {
            it.policy == PermissionPolicy.AUTO_CRITICAL && it.status != PermissionStatus.GRANTED
        }

    fun item(requirement: PermissionRequirement): PermissionHealthItem? {
        return items.firstOrNull { it.requirement == requirement }
    }

    companion object {
        val EMPTY = PermissionHealthSnapshot()
    }
}
