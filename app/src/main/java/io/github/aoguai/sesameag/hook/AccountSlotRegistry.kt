package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.UserDataStoreManager
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

const val MAX_EXECUTABLE_ACCOUNT_SLOTS = 2

enum class AccountSlotMigrationState {
    READY,
    SELECTION_REQUIRED,
}

data class AccountSlotRecord(
    val schemaVersion: Int = 1,
    val migrationState: AccountSlotMigrationState = AccountSlotMigrationState.READY,
    val activeUserIds: List<String> = emptyList(),
)

data class AccountSlotSnapshot(
    val migrationState: AccountSlotMigrationState,
    val activeUserIds: List<String>,
    val legacyCandidates: List<String>,
    val errorCode: String? = null,
) {
    val isReady: Boolean
        get() = errorCode == null && migrationState == AccountSlotMigrationState.READY
}

sealed interface AccountSlotAdmission {
    data class Allowed(
        val userId: String,
        val addedToSlot: Boolean,
    ) : AccountSlotAdmission

    data class Denied(val reasonCode: String) : AccountSlotAdmission
}

data class AccountSlotRemoval(
    val removed: Boolean,
    val reasonCode: String? = null,
)

/**
 * Separates locally stored account data from the two accounts allowed to execute target-app work.
 * Registry writes are serialized across the module UI and injected target process with a stable lock
 * file; a lock or persistence failure deliberately denies business execution.
 */
object AccountSlotRegistry {
    private const val TAG = "AccountSlotRegistry"
    private const val RECORD_FILE_NAME = "account-slots.json"
    private const val LOCK_FILE_NAME = ".account-slots.lock"
    private const val SCHEMA_VERSION = 1
    private const val MAX_USER_ID_LENGTH = 128

    private data class LoadedRecord(
        val record: AccountSlotRecord,
        val needsWrite: Boolean,
    )

    private data class LockedResult<T>(
        val value: T,
        val updatedRecord: AccountSlotRecord? = null,
    )

    fun snapshot(): AccountSlotSnapshot =
        withLockedRecord { loaded ->
            LockedResult(snapshotFor(loaded.record))
        } ?: AccountSlotSnapshot(
            migrationState = AccountSlotMigrationState.SELECTION_REQUIRED,
            activeUserIds = emptyList(),
            legacyCandidates = emptyList(),
            errorCode = "registry_unavailable",
        )

    fun admitRuntimeUser(rawUserId: String?): AccountSlotAdmission {
        val userId = normalizeUserId(rawUserId)
            ?: return AccountSlotAdmission.Denied("invalid_user_id")
        return withLockedRecord { loaded ->
            val record = loaded.record
            when {
                record.migrationState != AccountSlotMigrationState.READY -> {
                    LockedResult(AccountSlotAdmission.Denied("account_slot_migration_required"))
                }
                userId in record.activeUserIds -> {
                    LockedResult(AccountSlotAdmission.Allowed(userId, addedToSlot = false))
                }
                record.activeUserIds.size >= MAX_EXECUTABLE_ACCOUNT_SLOTS -> {
                    LockedResult(AccountSlotAdmission.Denied("account_slot_full"))
                }
                else -> {
                    val updated = record.copy(activeUserIds = record.activeUserIds + userId)
                    LockedResult(AccountSlotAdmission.Allowed(userId, addedToSlot = true), updated)
                }
            }
        } ?: AccountSlotAdmission.Denied("registry_unavailable")
    }

    fun isExecutableUser(rawUserId: String?): Boolean {
        val userId = normalizeUserId(rawUserId) ?: return false
        val current = snapshot()
        return current.isReady && userId in current.activeUserIds
    }

    fun selectLegacySlots(rawUserIds: Collection<String?>): AccountSlotRemoval {
        val selected = rawUserIds.mapNotNull(::normalizeUserId).distinct()
        if (selected.size != MAX_EXECUTABLE_ACCOUNT_SLOTS) {
            return AccountSlotRemoval(false, "exactly_two_accounts_required")
        }
        return withLockedRecord { loaded ->
            val record = loaded.record
            val candidates = legacyCandidates()
            when {
                record.migrationState != AccountSlotMigrationState.SELECTION_REQUIRED -> {
                    LockedResult(AccountSlotRemoval(false, "migration_not_required"))
                }
                !candidates.containsAll(selected) -> {
                    LockedResult(AccountSlotRemoval(false, "invalid_legacy_selection"))
                }
                else -> {
                    LockedResult(
                        AccountSlotRemoval(true),
                        AccountSlotRecord(
                            schemaVersion = SCHEMA_VERSION,
                            migrationState = AccountSlotMigrationState.READY,
                            activeUserIds = selected,
                        ),
                    )
                }
            }
        } ?: AccountSlotRemoval(false, "registry_unavailable")
    }

    fun removeExecutableSlot(context: android.content.Context?, rawUserId: String?): AccountSlotRemoval {
        val userId = normalizeUserId(rawUserId)
            ?: return AccountSlotRemoval(false, "invalid_user_id")
        val removal = withLockedRecord { loaded ->
            val record = loaded.record
            if (userId !in record.activeUserIds) {
                LockedResult(AccountSlotRemoval(false, "account_slot_not_active"))
            } else {
                LockedResult(
                    AccountSlotRemoval(true),
                    record.copy(activeUserIds = record.activeUserIds - userId),
                )
            }
        } ?: return AccountSlotRemoval(false, "registry_unavailable")

        if (removal.removed) {
            PersistentScheduleRegistry.cancelByOwner(context, userId)
            UserDataStoreManager.releaseInstance(userId)
            Log.record(TAG, "account_slot_revoked: account=${shortHash(userId)}")
        }
        return removal
    }

    fun normalizeUserId(rawUserId: String?): String? {
        val userId = rawUserId?.trim().orEmpty()
        if (userId.isEmpty() || userId.length > MAX_USER_ID_LENGTH) return null
        if (userId == "." || userId == ".." || userId.contains("..")) return null
        if (
            userId.any { character ->
                character.isISOControl() ||
                    character == '/' ||
                    character == '\\' ||
                    character == File.pathSeparatorChar
            }
        ) {
            return null
        }
        return userId
    }

    private fun snapshotFor(record: AccountSlotRecord): AccountSlotSnapshot =
        AccountSlotSnapshot(
            migrationState = record.migrationState,
            activeUserIds = record.activeUserIds,
            legacyCandidates = if (record.migrationState == AccountSlotMigrationState.SELECTION_REQUIRED) {
                legacyCandidates()
            } else {
                emptyList()
            },
        )

    private fun legacyCandidates(): List<String> =
        Files.listExistingUserConfigIds()
            .asSequence()
            .map { userId -> File(Files.CONFIG_DIR, userId) }
            .mapNotNull { directory -> verifiedDirectoryUserId(directory) }
            .distinct()
            .sorted()
            .toList()

    private fun verifiedDirectoryUserId(directory: File): String? {
        val directoryUserId = normalizeUserId(directory.name) ?: return null
        val configFile = File(directory, "config_v2.json")
        val selfFile = File(directory, "self.json")
        if (!configFile.isFile || configFile.length() == 0L || !selfFile.isFile || selfFile.length() == 0L) {
            return null
        }
        val snapshotUserId = runCatching {
            JsonUtil.parseObject(Files.readFromFile(selfFile), UserEntity.UserDto::class.java).userId
        }.getOrNull()
        return normalizeUserId(snapshotUserId)?.takeIf { it == directoryUserId }
    }

    private fun bootstrapRecord(): AccountSlotRecord {
        val candidates = legacyCandidates()
        return when {
            candidates.size <= MAX_EXECUTABLE_ACCOUNT_SLOTS -> AccountSlotRecord(
                schemaVersion = SCHEMA_VERSION,
                migrationState = AccountSlotMigrationState.READY,
                activeUserIds = candidates,
            )
            else -> AccountSlotRecord(
                schemaVersion = SCHEMA_VERSION,
                migrationState = AccountSlotMigrationState.SELECTION_REQUIRED,
                activeUserIds = emptyList(),
            )
        }
    }

    private fun validateRecord(record: AccountSlotRecord): AccountSlotRecord? {
        if (record.schemaVersion != SCHEMA_VERSION) return null
        val activeUserIds = record.activeUserIds.mapNotNull(::normalizeUserId)
        if (activeUserIds.size != record.activeUserIds.size || activeUserIds.distinct().size != activeUserIds.size) {
            return null
        }
        if (activeUserIds.size > MAX_EXECUTABLE_ACCOUNT_SLOTS) return null
        if (record.migrationState == AccountSlotMigrationState.SELECTION_REQUIRED && activeUserIds.isNotEmpty()) {
            return null
        }
        return record.copy(activeUserIds = activeUserIds)
    }

    private fun <T> withLockedRecord(operation: (LoadedRecord) -> LockedResult<T>): T? {
        return runCatching {
            val configDir = Files.CONFIG_DIR
            if (!configDir.isDirectory && !configDir.mkdirs()) {
                return null
            }
            val lockFile = File(configDir, LOCK_FILE_NAME)
            FileChannel.open(lockFile.toPath(), CREATE, WRITE).use { channel ->
                val lock = runCatching { channel.tryLock() }.getOrNull() ?: return null
                lock.use {
                    val loaded = readLockedRecord(configDir) ?: return null
                    val result = operation(loaded)
                    val recordToWrite = result.updatedRecord ?: loaded.record.takeIf { loaded.needsWrite }
                    if (recordToWrite != null && !writeLockedRecord(configDir, recordToWrite)) {
                        return null
                    }
                    result.value
                }
            }
        }.getOrNull()
    }

    private fun readLockedRecord(configDir: File): LoadedRecord? {
        val recordFile = File(configDir, RECORD_FILE_NAME)
        if (!recordFile.exists()) {
            return LoadedRecord(bootstrapRecord(), needsWrite = true)
        }
        val record = runCatching {
            JsonUtil.parseObject(Files.readFromFile(recordFile), AccountSlotRecord::class.java)
        }.getOrNull() ?: return null
        return validateRecord(record)?.let { LoadedRecord(it, needsWrite = false) }
    }

    private fun writeLockedRecord(configDir: File, record: AccountSlotRecord): Boolean {
        val canonicalRecord = validateRecord(record) ?: return false
        var temporaryFile: File? = null
        return runCatching {
            temporaryFile = File.createTempFile("$RECORD_FILE_NAME.", ".tmp", configDir)
            temporaryFile!!.writeText(JsonUtil.formatJson(canonicalRecord), Charsets.UTF_8)
            NioFiles.move(
                temporaryFile!!.toPath(),
                File(configDir, RECORD_FILE_NAME).toPath(),
                ATOMIC_MOVE,
                REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false).also { success ->
            if (!success) {
                Log.record(TAG, "account_slot_registry_write_failed")
            }
            temporaryFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
