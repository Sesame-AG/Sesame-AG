package io.github.aoguai.sesameag.data

import java.io.Serializable

/** A concrete flag stored in [Status.moduleFlags]. */
data class TodayFlagKey(
    val module: String,
    val name: String,
) : Serializable

/**
 * Declarative ownership of daily flags used by the settings UI and manual reset actions.
 *
 * The storage module in status.json is not always the same as the configuration module,
 * so module resets use these rules rather than deleting one JSON object by name.
 */
object TodayFlagRegistry {
    private enum class MatchMode { EXACT_WITH_TIME_SLOTS, PREFIX }

    private data class FlagPattern(
        val module: String,
        val name: String,
        val mode: MatchMode,
    ) {
        fun matches(flag: TodayFlagKey): Boolean = when (mode) {
            MatchMode.EXACT_WITH_TIME_SLOTS -> {
                flag.module == module && (flag.name == name || flag.name.startsWith("${name}_"))
            }
            MatchMode.PREFIX -> flag.module == module && flag.name.startsWith(name)
        }
    }

    private data class FieldBinding(
        val modelCode: String,
        val fieldCodes: Set<String>,
        val patterns: List<FlagPattern>,
    )

    private fun exact(flag: String): FlagPattern = pattern(flag, MatchMode.EXACT_WITH_TIME_SLOTS)

    private fun prefix(flag: String): FlagPattern = pattern(flag, MatchMode.PREFIX)

    private fun pattern(flag: String, mode: MatchMode): FlagPattern {
        val separator = flag.indexOf("::")
        return if (separator > 0) {
            FlagPattern(flag.substring(0, separator), flag.substring(separator + 2), mode)
        } else {
            FlagPattern("general", flag, mode)
        }
    }

    private fun binding(
        modelCode: String,
        vararg fieldCodes: String,
        patterns: List<FlagPattern>,
    ): FieldBinding = FieldBinding(modelCode, fieldCodes.toSet(), patterns)

    private val fieldBindings = listOf(
        binding("BaseModel", "customRpcScheduleEnable", patterns = listOf(prefix(StatusFlags.FLAG_CUSTOM_RPC_SCHEDULE_COUNT_PREFIX))),
        binding("AntForest", "pkEnergy", patterns = listOf(exact(StatusFlags.FLAG_ANTFOREST_PK_SKIP_TODAY))),
        binding("AntForest", "energyPvpChallenge", patterns = listOf(exact(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE))),
        binding("AntForest", "whackMoleMode", "whackMoleTime", patterns = listOf(exact(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED))),
        binding("AntForest", "ecoLife", "ecoLifeOption", patterns = listOf(prefix("EcoLife::"))),
        binding("AntForest", "vitalityExchange", "vitalityExchangeList", patterns = listOf(prefix(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX))),
        binding("AntForest", "forestChouChouLe", patterns = listOf(prefix(StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_PREFIX))),
        binding("AntForest", "userPatrol", patterns = listOf(exact(StatusFlags.FLAG_ANTFOREST_PATROL_CHANCE_EXCHANGE_LIMIT))),
        binding("YouthPrivilege", "youthPrivilegeForestProps", patterns = listOf(exact(StatusFlags.FLAG_YOUTH_PRIVILEGE_FOREST_PROPS_DONE))),
        binding("YouthPrivilege", "youthPrivilegeCheckIn", patterns = listOf(exact(StatusFlags.FLAG_YOUTH_PRIVILEGE_CHECK_IN_DONE))),
        binding("YouthPrivilege", "youthPrivilegeTasks", patterns = listOf(exact(StatusFlags.FLAG_YOUTH_PRIVILEGE_TASKS_DONE))),
        binding("AntMember", "memberSign", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE))),
        binding("AntMember", "memberTask", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_EMPTY_TODAY), exact(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY))),
        binding("AntMember", "yebExpGold", patterns = listOf(prefix(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX), exact(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE), exact(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE), exact(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE))),
        binding("AntMember", "CollectStickers", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_STICKERS_DONE))),
        binding("AntMember", "billBlockWorld", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_BILL_BLOCK_WORLD_DONE))),
        binding("AntMember", "memberPointExchangeBenefit", "memberPointExchangeBenefitList", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE))),
        binding("AntMember", "enableGameCenter", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_GAME_CENTER_DONE))),
        binding("AntMember", "beanSignIn", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_BEAN_SIGN_DONE))),
        binding("AntMember", "collectInsuredGold", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_INSURED_GOLD_DONE), prefix(StatusFlags.FLAG_ANTMEMBER_INSURED_TASK_CENTER_DONE_PREFIX))),
        binding("AntMember", "merchantSign", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MERCHANT_SIGN_DONE))),
        binding("AntMember", "merchantMoreTask", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MERCHANT_MORE_TASK_DONE))),
        binding("AntMember", "merchantKmdk", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE), exact(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE))),
        binding("AntMember", "enableGoldTicket", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE), exact(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE), exact(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE))),
        binding("AntMember", "enableGoldTicketConsume", patterns = listOf(exact(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE))),
        binding("MyBankWelfare", "myBankWelfareSign", patterns = listOf(exact(StatusFlags.FLAG_MYBANK_WELFARE_SIGN_DONE))),
        binding("MyBankWelfare", "myBankWelfareExchange", "myBankWelfareExchangeList", patterns = listOf(exact(StatusFlags.FLAG_MYBANK_WELFARE_EXCHANGE_REFRESH_DONE))),
        binding("MyBankWelfare", "myBankWelfareTask", patterns = listOf(exact(StatusFlags.FLAG_MYBANK_WELFARE_TASKS_DONE))),
        binding("AntSesameCredit", "sesameTask", patterns = listOf(exact(StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK), exact(StatusFlags.FLAG_SESAME_JOIN_LIMIT_REACHED))),
        binding("AntSesameCredit", "enableZhimaTree", patterns = listOf(exact(StatusFlags.FLAG_SESAME_ZHIMA_TREE_TASK_HANDLED_TODAY))),
        binding("AntSesameCredit", "collectSesame", "collectSesameWithOneClick", patterns = listOf(exact(StatusFlags.FLAG_SESAME_COLLECT_DONE))),
        binding("AntSesameCredit", "sesameGrainExchange", "sesameGrainExchangeList", patterns = listOf(exact(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE))),
        binding("OtherTask", "credit2101", "CreditOptions", patterns = listOf(exact(StatusFlags.FLAG_CREDIT2101_CHAPTER_TASK_DONE), prefix(StatusFlags.FLAG_CREDIT2101_EVENT_COUNT_PREFIX))),
        binding("AntSports", "sportsTasks", patterns = listOf(exact(StatusFlags.FLAG_ANTSPORTS_DAILY_TASKS_DONE), exact(StatusFlags.FLAG_ANTSPORTS_MOTION_DAILY_QUIZ_DONE))),
        binding("AntSports", "syncStepCount", patterns = listOf(exact(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE))),
        binding("AntSports", "walkReviveSteps", "walkReviveTask", patterns = listOf(exact(StatusFlags.FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED))),
        binding("AntSports", "neverlandGrid", "neverlandGridStepCount", patterns = listOf(exact(StatusFlags.FLAG_ANTSPORTS_NEVERLAND_ENERGY_LIMIT), exact(StatusFlags.FLAG_NEVERLAND_STEP_COUNT))),
        binding("AntSports", "neverlandTask", patterns = listOf(exact(StatusFlags.FLAG_NEVERLAND_SIGN_DONE), exact(StatusFlags.FLAG_ANTSPORTS_TASK_CENTER_DONE), exact(StatusFlags.FLAG_NEVERLAND_LIGHT_FEEDS_DONE), prefix(StatusFlags.FLAG_NEVERLAND_TASK_SUBMITTED_PREFIX), prefix(StatusFlags.FLAG_NEVERLAND_TASK_DONE_PREFIX))),
        binding("AntSports", "neverlandAutoReward", "neverlandPreferMedal", patterns = listOf(prefix(StatusFlags.FLAG_NEVERLAND_REWARD_UNAVAILABLE_PREFIX))),
        binding("AntCooperate", "teamCooperateWaterNum", patterns = listOf(exact(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT))),
        binding("AntCooperate", "loveCooperateWater", "loveCooperateWaterNum", patterns = listOf(exact(StatusFlags.FLAG_ANTCOOPERATE_LOVE_TEAM_WATER))),
        binding("AntOcean", "cleanOcean", "cleanOceanType", "cleanOceanList", patterns = listOf(exact(StatusFlags.FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT))),
        binding("AntOcean", "dailyOceanTask", patterns = listOf(exact(StatusFlags.FLAG_ANTOCEAN_TASKS_DONE))),
        binding("AntDodo", "collectToFriend", patterns = listOf(exact(StatusFlags.FLAG_ANTDODO_DAILY_COLLECT_DONE))),
        binding("AntOrchard", "orchardSpreadManureCount", patterns = listOf(exact(StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT))),
        binding("AntOrchard", "orchardSpreadManureCountYeb", patterns = listOf(exact(StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT_YEB))),
        binding("GoldenBeanTreasure", "goldenBeanTreasure", patterns = listOf(exact(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE))),
        binding("GoldenBeanTreasure", "goldenBeanManureExchangeDailyReserveAmount", patterns = listOf(exact(StatusFlags.FLAG_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT))),
        binding("GoldenBeanTreasure", "goldenBeanSesameExchangeDailyBeanAmount", patterns = listOf(exact(StatusFlags.FLAG_GOLDEN_BEAN_ZHIMA_EXCHANGE_BEAN_AMOUNT))),
        binding("AntFishPond", "fishPondTask", patterns = listOf(exact(StatusFlags.FLAG_ANTFISHPOND_SIGN_DONE), exact(StatusFlags.FLAG_ANTFISHPOND_GIFT_BOX_DONE), exact(StatusFlags.FLAG_ANTFISHPOND_TOMORROW_ROD_DONE), exact(StatusFlags.FLAG_ANTFISHPOND_TASKS_DONE))),
        binding("AntFishPond", "autoFish", "fishDailyLimit", patterns = listOf(exact(StatusFlags.FLAG_ANTFISHPOND_RISK_TOKEN_MISSING), exact(StatusFlags.FLAG_ANTFISHPOND_FISH_COUNT), exact(StatusFlags.FLAG_ANTFISHPOND_FISH_LIMIT_REACHED))),
        binding("AntStall", "stallThrowManure", patterns = listOf(exact(StatusFlags.FLAG_ANTSTALL_THROW_MANURE_LIMIT))),
        binding("AntStall", "stallAutoTask", patterns = listOf(exact(StatusFlags.FLAG_ANTSTALL_TASKS_DONE))),
        binding("AntFarm", "doFarmTask", "farmTaskTrigger", patterns = listOf(exact(StatusFlags.FLAG_FARM_TASK_FINISHED), prefix(StatusFlags.FLAG_FARM_TASK_LIMIT_PREFIX))),
        binding("AntFarm", "paradiseCoinExchangeBenefit", "paradiseCoinExchangeBenefitList", patterns = listOf(prefix(StatusFlags.FLAG_FARM_PARADISE_COIN_EXCHANGE_LIMIT_PREFIX))),
        binding("AntFarm", "enableChouchoule", "chouChouLeTrigger", patterns = listOf(exact(StatusFlags.FLAG_FARM_CHOUCHOULE_FINISHED), exact(StatusFlags.FLAG_FARM_MULTI_STAGE_TASK_FINISHED), prefix(StatusFlags.FLAG_FARM_CHOUCHOULE_LIMITED_ENDED_PREFIX))),
        binding("AntFarm", "recordFarmGame", "farmGameTrigger", patterns = listOf(exact(StatusFlags.FLAG_FARM_GAME_FINISHED))),
        binding("AntFarm", "feedFriendAnimalList", patterns = listOf(prefix(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT_PREFIX), exact(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT))),
        binding("AntFarm", "family", "familyOptions", patterns = listOf(exact(StatusFlags.FLAG_FARM_FAMILY_SIGNED), exact(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER), exact(StatusFlags.FLAG_FARM_FAMILY_DELIVER_MSG_SEND), exact(StatusFlags.FLAG_FARM_FAMILY_SHARE_TO_FRIENDS), exact(StatusFlags.FLAG_FARM_INVITE_FRIEND_VISIT_FAMILY), prefix(StatusFlags.FLAG_FARM_FAMILY_DECORATION_CHECK_DONE_PREFIX))),
        binding("AntFarm", "useAccelerateTool", "remainingTime", "accelerateToolDailyLimit", patterns = listOf(exact(StatusFlags.FLAG_FARM_ACCELERATE_LIMIT))),
        binding("AntFarm", "useSpecialFood", "useSpecialFoodCount", patterns = listOf(exact(StatusFlags.FLAG_FARM_SPECIAL_FOOD_LIMIT), exact(StatusFlags.FLAG_FARM_SPECIAL_FOOD_DAILY_COUNT))),
        binding("AntFarm", "donationCompetitionTrySpecialFood", "donationCompetitionSpecialFoodCount", patterns = listOf(exact(StatusFlags.FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_LIMIT), exact(StatusFlags.FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_DAILY_COUNT))),
        binding("AntFarm", "donation", patterns = listOf(prefix(StatusFlags.FLAG_FARM_DAILY_DONATION_DONE_PREFIX), prefix(StatusFlags.FLAG_FARM_DONATION_COUNT))),
        binding("AntFarm", "receiveDonationCompetitionAward", patterns = listOf(exact(StatusFlags.FLAG_FARM_DONATION_COMPETITION_AWARD_RECEIVED), exact(StatusFlags.FLAG_FARM_DONATION_COMPETITION_UNAVAILABLE))),
        binding("AntFarm", "signRegardless", patterns = listOf(exact(StatusFlags.FLAG_FARM_SIGNED))),
    )

    private val modulePatterns = mapOf(
        "AntForest" to listOf(prefix("AntForest::"), prefix("forest::"), prefix("antForest::"), prefix("EcoLife::")),
        "YouthPrivilege" to listOf(prefix("YouthPrivilege::")),
        "AntMember" to listOf(prefix("AntMember::"), prefix("memberBenefit::")),
        "MyBankWelfare" to listOf(prefix("MyBankWelfare::")),
        "AntSesameCredit" to listOf(prefix("AntSesameCredit::")),
        "AntSports" to listOf(prefix("AntSports::"), prefix("Flag_AntSports_"), prefix("FLAG_ANTSPORTS_"), prefix("Flag_Neverland_")),
        "AntCooperate" to listOf(prefix("love::"), exact(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT)),
        "AntOcean" to listOf(prefix("AntOcean::"), prefix("Ocean::")),
        "AntDodo" to listOf(prefix("AntDodo::")),
        "AntOrchard" to listOf(prefix("AntOrchard::"), prefix("orchard::"), prefix("Flag_Antorchard_"), prefix("FLAG_Antorchard_"), prefix("ANTORCHARD_")),
        "GoldenBeanTreasure" to listOf(prefix("GoldenBeanTreasure::"), exact(StatusFlags.FLAG_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT), exact(StatusFlags.FLAG_GOLDEN_BEAN_ZHIMA_EXCHANGE_BEAN_AMOUNT)),
        "AntFishPond" to listOf(prefix("AntFishPond::")),
        "AntStall" to listOf(prefix("AntStall::"), prefix("stall::"), prefix("Flag_AntStall_")),
        "AntFarm" to listOf(prefix("AntFarm::"), prefix("antFarm::"), prefix("farm::"), prefix("farmQuestion::")),
        "BaseModel" to listOf(prefix("OnceDaily::"), prefix("customRpcSchedule::"), prefix("friendCenter::")),
        "OtherTask" to listOf(prefix("OnceDaily::"), prefix("friendCenter::"), prefix(StatusFlags.FLAG_CREDIT2101_EVENT_COUNT_PREFIX), exact(StatusFlags.FLAG_CREDIT2101_CHAPTER_TASK_DONE)),
        "ManualTaskModel" to listOf(prefix("customRpcSchedule::")),
    )

    fun fieldKeys(status: Status, modelCode: String, fieldCode: String): Set<TodayFlagKey> =
        keysFor(status, fieldBindings
            .filter { it.modelCode == modelCode && fieldCode in it.fieldCodes }
            .flatMap { it.patterns })

    fun moduleKeys(status: Status, modelCode: String): Set<TodayFlagKey> =
        keysFor(status, modulePatterns[modelCode].orEmpty())

    private fun keysFor(status: Status, patterns: List<FlagPattern>): Set<TodayFlagKey> {
        if (patterns.isEmpty()) return emptySet()
        return buildSet {
            status.moduleFlags.forEach { (module, flags) ->
                flags.keys.forEach { name ->
                    val key = TodayFlagKey(module, name)
                    if (patterns.any { it.matches(key) }) add(key)
                }
            }
        }
    }
}
