package io.github.aoguai.sesameag.util.settingsTransfer

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.task.antFarm.ChouChouLe
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File

object SettingsTransferCacheRegistry {
    private const val TAG = "SettingsTransferCacheRegistry"
    private const val IP_DRAW_LEGACY_CACHE_KEY = "farm_ip_chouchoule_legacy_snapshot"
    private const val IP_DRAW_LEGACY_CACHE_FILE = "farmIPChouChouLeShop.json"

    private data class CacheRule(
        val key: String,
        val scope: ConfigPortScope,
        val fileName: String
    )

    private val rules = listOf(
        CacheRule("exchange_bill_coin", ConfigPortScope.ACCOUNT_PRIVATE, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_BILL_COIN}.json"),
        CacheRule("exchange_golden_bean_mall", ConfigPortScope.ACCOUNT_PRIVATE, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_GOLDEN_BEAN_MALL}.json"),
        CacheRule("exchange_member_point", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_MEMBER_POINT}.json"),
        CacheRule("exchange_mybank_welfare", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE}.json"),
        CacheRule("exchange_bean_right", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_BEAN_RIGHT}.json"),
        CacheRule("exchange_farm_paradise", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_FARM_PARADISE}.json"),
        CacheRule("exchange_farm_ip_chouchoule", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE}.json"),
        CacheRule("exchange_sports_energy", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_SPORTS_ENERGY}.json"),
        CacheRule("exchange_forest_vitality", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY}.json"),
        CacheRule("exchange_sesame_grain", ConfigPortScope.GENERIC, "exchange_options_${ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN}.json"),
        CacheRule("map_member_point", ConfigPortScope.GENERIC, "MemberBenefitsMap.json"),
        CacheRule("map_mybank_welfare", ConfigPortScope.GENERIC, "MyBankWelfareBenefitMap.json"),
        CacheRule("map_bean_right", ConfigPortScope.GENERIC, "beanExchangeRightMap.json"),
        CacheRule("map_farm_paradise", ConfigPortScope.GENERIC, "paradiseCoinBenefitMap.json"),
        CacheRule("map_sports_energy", ConfigPortScope.GENERIC, "sportsEnergyExchangeMap.json"),
        CacheRule("map_forest_vitality", ConfigPortScope.GENERIC, "vitalityRewardsMap.json"),
        CacheRule("map_sesame_grain", ConfigPortScope.GENERIC, "sesameGift.json"),
        CacheRule("map_cooperate", ConfigPortScope.ACCOUNT_PRIVATE, "cooperateMap.json")
    )

    fun exportCaches(userId: String?, scope: ConfigPortScope): List<SettingsTransferCacheEntry> {
        val safeUserId = userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) {
            return emptyList()
        }
        val entries = mutableListOf<SettingsTransferCacheEntry>()
        rules.filter { it.scope == scope }.forEach { rule ->
            val file = resolveUserFile(safeUserId, rule.fileName)
            val body = Files.readFromFile(file)
            if (body.isNotBlank()) {
                entries.add(SettingsTransferCacheEntry(rule.key, rule.fileName, body))
            }
        }
        if (scope == ConfigPortScope.GENERIC) {
            exportIpDrawLegacyCache(safeUserId)?.let(entries::add)
        }
        return entries
    }

    fun validateEntries(entries: Collection<SettingsTransferCacheEntry>, scope: ConfigPortScope) {
        val entryMap = entries.associateBy { it.key }
        rules.filter { it.scope == scope }.forEach { rule ->
            entryMap[rule.key]?.let { validateRuleEntry(rule, it.content) }
        }
        if (scope == ConfigPortScope.GENERIC) {
            entryMap[IP_DRAW_LEGACY_CACHE_KEY]?.let { validateIpDrawLegacyCache(it.content) }
        }
    }

    fun writeCaches(userId: String?, entries: Collection<SettingsTransferCacheEntry>, scope: ConfigPortScope): Boolean {
        val safeUserId = userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) {
            return true
        }
        val entryMap = entries.associateBy { it.key }
        rules.filter { it.scope == scope }.forEach { rule ->
            val entry = entryMap[rule.key]
            if (entry == null) {
                return@forEach
            }
            val file = resolveUserFile(safeUserId, rule.fileName)
            if (!Files.writeAtomically(entry.content, file)) {
                Log.runtime(TAG, "写入缓存失败: ${rule.fileName}")
                return false
            }
        }
        if (scope == ConfigPortScope.GENERIC) {
            entryMap[IP_DRAW_LEGACY_CACHE_KEY]?.let { entry ->
                if (!writeIpDrawLegacyCache(safeUserId, entry.content)) {
                    return false
                }
            }
        }
        return true
    }

    fun targetFilesForScope(userId: String?, scope: ConfigPortScope, entries: Collection<SettingsTransferCacheEntry>): List<File> {
        val safeUserId = userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) {
            return emptyList()
        }
        val files = linkedSetOf<File>()
        val keys = entries.map { it.key }.toSet()
        rules.filter { it.scope == scope && it.key in keys }
            .forEach { files.add(resolveUserFile(safeUserId, it.fileName)) }
        if (scope == ConfigPortScope.GENERIC && IP_DRAW_LEGACY_CACHE_KEY in keys) {
            files.add(resolveUserFile(safeUserId, IP_DRAW_LEGACY_CACHE_FILE))
        }
        return files.toList()
    }

    private fun exportIpDrawLegacyCache(userId: String): SettingsTransferCacheEntry? {
        return runCatching {
            val file = resolveUserFile(userId, IP_DRAW_LEGACY_CACHE_FILE)
            if (!file.exists()) {
                return@runCatching null
            }
            val body = Files.readFromFile(file)
            if (body.isBlank()) {
                return@runCatching null
            }
            val data = JsonUtil.parseObject(body, ChouChouLe.IpChouChouLeData::class.java)
            if (data.shopItems.isEmpty()) {
                null
            } else {
                val snapshot = IpChouChouLeLegacyCacheSnapshot(
                    shopItems = LinkedHashMap(data.shopItems)
                )
                SettingsTransferCacheEntry(
                    key = IP_DRAW_LEGACY_CACHE_KEY,
                    fileName = IP_DRAW_LEGACY_CACHE_FILE,
                    content = JsonUtil.formatJson(snapshot, false)
                )
            }
        }.onFailure {
            Log.printStackTrace(TAG, "导出 IP 抽抽乐旧快照失败", it)
        }.getOrNull()
    }

    private fun writeIpDrawLegacyCache(userId: String, content: String): Boolean {
        return runCatching {
            val snapshot = JsonUtil.parseObject(content, IpChouChouLeLegacyCacheSnapshot::class.java)
            val file = resolveUserFile(userId, IP_DRAW_LEGACY_CACHE_FILE)
            val data = if (file.exists()) {
                val body = Files.readFromFile(file)
                if (body.isBlank()) {
                    ChouChouLe.IpChouChouLeData()
                } else {
                    JsonUtil.parseObject(body, ChouChouLe.IpChouChouLeData::class.java)
                }
            } else {
                ChouChouLe.IpChouChouLeData()
            }.apply {
                shopItems.clear()
                shopItems.putAll(snapshot.shopItems)
            }
            Files.writeAtomically(JsonUtil.formatJson(data), file)
        }.onFailure {
            Log.printStackTrace(TAG, "写入 IP 抽抽乐旧快照失败", it)
        }.getOrDefault(false)
    }

    private fun resolveUserFile(userId: String, fileName: String): File {
        return Files.child(Files.getUserConfigDir(userId), fileName)
    }

    private fun validateRuleEntry(rule: CacheRule, content: String) {
        if (content.isBlank()) {
            return
        }
        runCatching {
            if (rule.fileName.startsWith("exchange_options_")) {
                val rows = if (content.trimStart().startsWith("{")) {
                    org.json.JSONObject(content).getJSONArray("rows").toString()
                } else content
                JsonUtil.parseObject(
                    rows,
                    object : TypeReference<List<ExchangeOptionRow>>() {}
                )
            } else {
                JsonUtil.parseObject(
                    content,
                    object : TypeReference<Map<String, String>>() {}
                )
            }
        }.getOrElse { throwable ->
            throw IllegalArgumentException("缓存 ${rule.fileName} 内容无效", throwable)
        }
    }

    private fun validateIpDrawLegacyCache(content: String) {
        if (content.isBlank()) {
            return
        }
        runCatching {
            JsonUtil.parseObject(content, IpChouChouLeLegacyCacheSnapshot::class.java)
        }.getOrElse { throwable ->
            throw IllegalArgumentException("缓存 $IP_DRAW_LEGACY_CACHE_FILE 内容无效", throwable)
        }
    }
}
