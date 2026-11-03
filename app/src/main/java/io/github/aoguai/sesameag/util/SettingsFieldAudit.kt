package io.github.aoguai.sesameag.util

import java.util.concurrent.ConcurrentHashMap

data class SettingsFieldAudit(
    val title: String,
    val message: String,
    val staleCount: Int = 0,
    val clearValue: String = ""
)

object SettingsFieldAuditRegistry {
    private val fieldAudits = ConcurrentHashMap<String, SettingsFieldAudit>()

    @JvmStatic
    fun save(userId: String?, fieldCode: String, audit: SettingsFieldAudit) {
        val key = buildKey(userId, fieldCode) ?: return
        fieldAudits[key] = audit
    }

    @JvmStatic
    fun get(userId: String?, fieldCode: String): SettingsFieldAudit? {
        val key = buildKey(userId, fieldCode) ?: return null
        return fieldAudits[key]
    }

    @JvmStatic
    fun clear(userId: String?, fieldCode: String) {
        val key = buildKey(userId, fieldCode) ?: return
        fieldAudits.remove(key)
    }

    private fun buildKey(userId: String?, fieldCode: String): String? {
        val normalizedUserId = userId?.trim().orEmpty()
        val normalizedFieldCode = fieldCode.trim()
        if (normalizedUserId.isEmpty() || normalizedFieldCode.isEmpty()) {
            return null
        }
        return "$normalizedUserId#$normalizedFieldCode"
    }
}
