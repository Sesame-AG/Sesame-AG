package io.github.aoguai.sesameag.util

enum class LogModuleDomain(val displayName: String) {
    COMMON("通用"),
    FOREST("蚂蚁森林"),
    ORCHARD("芭芭农场"),
    GOLDEN_BEAN("金豆夺宝"),
    FARM("蚂蚁庄园"),
    STALL("蚂蚁新村"),
    OCEAN("神奇海洋"),
    DODO("神奇物种"),
    MEMBER("会员"),
    YOUTH_PRIVILEGE("青春特权"),
    MYBANK("网商银行"),
    FISHPOND("福气鱼池"),
    SPORTS("运动"),
    GREEN_FINANCE("绿色经营"),
    SESAME_CREDIT("芝麻信用"),
    SYSTEM("系统");
}

enum class LogTechKind(val displayName: String) {
    BUSINESS("业务"),
    RECORD("总览"),
    SUMMARY("摘要"),
    RUNTIME("运行时"),
    DEBUG("调试"),
    ERROR("错误"),
    CAPTURE("抓包"),
    SYSTEM("系统");
}

enum class LogViewerGroup(val displayName: String) {
    OVERVIEW("总览"),
    MODULES("业务模块"),
    TECHNICAL("技术排障");
}

data class LogViewerSection(
    val group: LogViewerGroup,
    val channels: List<LogChannel>
)

enum class LogChannel(
    val loggerName: String,
    val displayName: String,
    val moduleDomain: LogModuleDomain,
    val techKind: LogTechKind,
    val description: String,
    val viewerGroup: LogViewerGroup? = null,
    val mirrorToRecord: Boolean = false,
    val visibleInViewer: Boolean = false,
    val logTag: String? = null
) {
    SYSTEM(
        loggerName = "system",
        displayName = "系统日志",
        moduleDomain = LogModuleDomain.SYSTEM,
        techKind = LogTechKind.SYSTEM,
        description = "模块内部系统日志",
    ),
    RECORD(
        loggerName = "record",
        displayName = "全部日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.RECORD,
        description = "聚合后的总览日志，适合日常查看执行过程",
        viewerGroup = LogViewerGroup.OVERVIEW,
        visibleInViewer = true,
    ),
    SUMMARY(
        loggerName = "summary",
        displayName = "执行摘要",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.SUMMARY,
        description = "任务调度与执行统计摘要",
        viewerGroup = LogViewerGroup.OVERVIEW,
        mirrorToRecord = true,
        visibleInViewer = true
    ),
    COMMON(
        loggerName = "common",
        displayName = "通用日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.BUSINESS,
        description = "未归属到特定玩法模块的通用业务日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true
    ),
    FOREST(
        loggerName = "forest",
        displayName = "森林日志",
        moduleDomain = LogModuleDomain.FOREST,
        techKind = LogTechKind.BUSINESS,
        description = "蚂蚁森林与保护地相关业务日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "森林"
    ),
    ORCHARD(
        loggerName = "orchard",
        displayName = "农场日志",
        moduleDomain = LogModuleDomain.ORCHARD,
        techKind = LogTechKind.BUSINESS,
        description = "芭芭农场施肥、肥料与果树相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "农场"
    ),
    GOLDEN_BEAN(
        loggerName = "golden_bean",
        displayName = "金豆夺宝日志",
        moduleDomain = LogModuleDomain.GOLDEN_BEAN,
        techKind = LogTechKind.BUSINESS,
        description = "金豆夺宝签到、任务、金猫矿工、金豆乐园与肥料换豆日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "金豆夺宝"
    ),
    FARM(
        loggerName = "farm",
        displayName = "庄园日志",
        moduleDomain = LogModuleDomain.FARM,
        techKind = LogTechKind.BUSINESS,
        description = "蚂蚁庄园、小鸡乐园、家庭与美食相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "庄园"
    ),
    STALL(
        loggerName = "stall",
        displayName = "新村日志",
        moduleDomain = LogModuleDomain.STALL,
        techKind = LogTechKind.BUSINESS,
        description = "蚂蚁新村摆摊、罚单、助力与任务相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "新村"
    ),
    OCEAN(
        loggerName = "ocean",
        displayName = "海洋日志",
        moduleDomain = LogModuleDomain.OCEAN,
        techKind = LogTechKind.BUSINESS,
        description = "神奇海洋、海域推进与碎片合成相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "海洋"
    ),
    DODO(
        loggerName = "dodo",
        displayName = "神奇物种日志",
        moduleDomain = LogModuleDomain.DODO,
        techKind = LogTechKind.BUSINESS,
        description = "神奇物种抽卡、道具、任务奖励与图鉴合成相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "神奇物种"
    ),
    MEMBER(
        loggerName = "member",
        displayName = "会员日志",
        moduleDomain = LogModuleDomain.MEMBER,
        techKind = LogTechKind.BUSINESS,
        description = "会员积分、余额宝体验金、黄金票与会员任务相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "会员"
    ),
    YOUTH_PRIVILEGE(
        loggerName = "youth_privilege",
        displayName = "青春特权日志",
        moduleDomain = LogModuleDomain.YOUTH_PRIVILEGE,
        techKind = LogTechKind.BUSINESS,
        description = "青春特权签到、森林道具与青春任务相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "青春特权"
    ),
    MYBANK(
        loggerName = "mybank",
        displayName = "网商银行日志",
        moduleDomain = LogModuleDomain.MYBANK,
        techKind = LogTechKind.BUSINESS,
        description = "网商银行福利金、签到、任务与兑换相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "网商银行"
    ),
    FISHPOND(
        loggerName = "fishpond",
        displayName = "福气鱼池日志",
        moduleDomain = LogModuleDomain.FISHPOND,
        techKind = LogTechKind.BUSINESS,
        description = "福气鱼池钓竿、任务、钓鱼与兑换进度相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "福气鱼池"
    ),
    SPORTS(
        loggerName = "sports",
        displayName = "运动日志",
        moduleDomain = LogModuleDomain.SPORTS,
        techKind = LogTechKind.BUSINESS,
        description = "运动步数、跑图、健康岛与联动任务日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "运动"
    ),
    GREEN_FINANCE(
        loggerName = "green_finance",
        displayName = "绿色经营日志",
        moduleDomain = LogModuleDomain.GREEN_FINANCE,
        techKind = LogTechKind.BUSINESS,
        description = "绿色经营金币、打卡、捐助与任务日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "经营"
    ),
    SESAME_CREDIT(
        loggerName = "sesame_credit",
        displayName = "芝麻信用日志",
        moduleDomain = LogModuleDomain.SESAME_CREDIT,
        techKind = LogTechKind.BUSINESS,
        description = "芝麻信用、芝麻粒与信用玩法相关日志",
        viewerGroup = LogViewerGroup.MODULES,
        mirrorToRecord = true,
        visibleInViewer = true,
        logTag = "芝麻信用"
    ),
    RUNTIME(
        loggerName = "runtime",
        displayName = "运行时日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.RUNTIME,
        description = "底层桥接、缓存与内部诊断日志",
        viewerGroup = LogViewerGroup.TECHNICAL,
        visibleInViewer = true,
    ),
    DEBUG(
        loggerName = "debug",
        displayName = "调试日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.DEBUG,
        description = "RPC 调试工具与开发排障辅助日志",
        viewerGroup = LogViewerGroup.TECHNICAL,
        visibleInViewer = true,
    ),
    ERROR(
        loggerName = "error",
        displayName = "错误日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.ERROR,
        description = "异常、失败、风控与错误堆栈日志",
        viewerGroup = LogViewerGroup.TECHNICAL,
        mirrorToRecord = true,
        visibleInViewer = true
    ),
    CAPTURE(
        loggerName = "capture",
        displayName = "抓包日志",
        moduleDomain = LogModuleDomain.COMMON,
        techKind = LogTechKind.CAPTURE,
        description = "Hook 请求响应、自定义 RPC 与抓包调试日志",
        viewerGroup = LogViewerGroup.TECHNICAL,
        visibleInViewer = true,
    );

    val fileName: String
        get() = "$loggerName.log"

    fun matchesLoggerName(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }
        return loggerName.equals(value, ignoreCase = true)
    }

    fun matchesSourceTagAlias(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }
        val candidate = value.trim()
        return LogCatalog.sourceTagAliases(this).any { it.equals(candidate, ignoreCase = true) }
    }
}

object LogCatalog {
    val channels: List<LogChannel> = LogChannel.entries

    private val channelSourceTagAliases: Map<LogChannel, Set<String>> = mapOf(
        LogChannel.FOREST to setOf(
            "AntForest",
            "AntForestRpcCall",
            "AntCooperate",
            "Reserve",
            "EcoProtection",
            "ForestUtil",
            "ForestChouChouLe",
            "EnergyWaitingPersistence",
            "EnergyWaitingManager",
            "EnergyRain",
            "EcoLife",
            "GreenLife",
            "Healthcare",
            "Privilege",
            "RebornEnergyWeekly",
            "UserEnergyPatternManager",
            "Vitality",
            "WhackMole"
        ),
        LogChannel.ORCHARD to setOf(
            "AntOrchard",
            "XLightRpcCall"
        ),
        LogChannel.GOLDEN_BEAN to setOf(
            "GoldenBeanTreasure",
            "GoldenBeanWorkflow",
            "GoldenBeanTreasureSupport",
            "GoldenBeanRpcCall",
            "GoldenBean",
            "金豆夺宝"
        ),
        LogChannel.FARM to setOf(
            "AntFarm",
            "AntFarmRpcCall",
            "FarmGame",
            "ChouChouLe",
            "AntFarmFamily"
        ),
        LogChannel.STALL to setOf(
            "AntStall",
            "ReadingDada"
        ),
        LogChannel.OCEAN to setOf(
            "AntOcean"
        ),
        LogChannel.DODO to setOf(
            "AntDodo"
        ),
        LogChannel.MEMBER to setOf(
            "AntMember"
        ),
        LogChannel.YOUTH_PRIVILEGE to setOf(
            "YouthPrivilege",
            "YouthPrivilegeRpcCall"
        ),
        LogChannel.MYBANK to setOf(
            "MyBankWelfare"
        ),
        LogChannel.FISHPOND to setOf(
            "AntFishPond"
        ),
        LogChannel.SPORTS to setOf(
            "AntSports",
            "Neverland"
        ),
        LogChannel.GREEN_FINANCE to setOf(
            "GreenFinance"
        ),
        LogChannel.SESAME_CREDIT to setOf(
            "AntSesameCredit"
        )
    )

    @JvmStatic
    fun loggerNames(): List<String> = channels.map { it.loggerName }.distinct()

    @JvmStatic
    fun viewerSections(): List<LogViewerSection> {
        return LogViewerGroup.entries.mapNotNull { group ->
            val groupChannels = channels.filter { it.visibleInViewer && it.viewerGroup == group }
            if (groupChannels.isEmpty()) {
                null
            } else {
                LogViewerSection(group, groupChannels)
            }
        }
    }

    @JvmStatic
    fun visibleChannels(): List<LogChannel> = channels.filter { it.visibleInViewer }

    @JvmStatic
    fun findByLoggerName(loggerName: String?): LogChannel? {
        return channels.firstOrNull { it.matchesLoggerName(loggerName) }
    }

    @JvmStatic
    fun findBySourceTagAlias(sourceTag: String?): LogChannel? {
        return channels.firstOrNull { it.matchesSourceTagAlias(sourceTag) }
    }

    @JvmStatic
    fun sourceTagAliases(channel: LogChannel): Set<String> {
        return channelSourceTagAliases[channel].orEmpty()
    }

    @JvmStatic
    fun findByFileName(fileName: String?): LogChannel? {
        if (fileName.isNullOrBlank()) {
            return null
        }
        return channels.firstOrNull { channel -> channel.fileName.equals(fileName, ignoreCase = true) }
    }

    @JvmStatic
    fun fileName(loggerName: String): String {
        return findByLoggerName(loggerName)?.fileName ?: "${loggerName}.log"
    }
}
