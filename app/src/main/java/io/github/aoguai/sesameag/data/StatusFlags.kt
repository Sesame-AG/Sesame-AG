package io.github.aoguai.sesameag.data

/**
 * 用于统一管理所有【每日 / 状态 Flag】的常量定义。
 *
 * 设计目标：
 * 1. 避免项目中散落字符串常量
 * 2. 统一命名规范，便于搜索和维护
 * 3. 明确业务模块归属
 *
 * 命名规范：
 * - 常量名：全大写 + 下划线（FLAG_XXX）
 * - 常量值：实际存储使用的 Key
 *
 * 状态语义：
 * - 完成态：任务或奖励已经确认完成，通常用于同日短路，避免重复请求。
 * - 止损态：服务端明确返回今日上限、未开通、无可执行项、风控/冷却等结果，本日不再继续撞接口。
 * - 计数态：记录当日已执行次数或已消费的时间槽索引，用于和用户配置的上限做比较。
 *
 * 使用约束：
 * - 只有成功闭环或明确业务终态才能落完成/止损标记。
 * - 完成态/止损态必须通过 Status.setFlagToday() 写入，以继承全局离线后的今日标识保护。
 * - 计数态只记录进度、次数或触发槽，不应复用为“今日已完成/停止”的闭环标识。
 * - 参数错误、RPC 未验证、抓包不足不应伪装成完成态；需要保留日志上下文或进入待支持/补抓流程。
 * - 新增 flag 时优先使用“模块名::业务名::状态”的值格式；是否保留历史 key 由对应重构策略决定。
 */
object StatusFlags {

    // ============================================================
    // 通用 / 调度
    // ============================================================

    /** 单次日常模式：今日已完成 */
    const val FLAG_ONCE_DAILY_FINISHED: String = "OnceDaily::Finished"

    /** 自定义 RPC 定时任务：每日计数前缀 */
    const val FLAG_CUSTOM_RPC_SCHEDULE_COUNT_PREFIX: String = "customRpcSchedule::"

    /** 好友中心：今日已从支付宝侧同步好友快照 */
    const val FLAG_FRIEND_CENTER_SYNC_TODAY: String = "friendCenter::syncToday"

    // ============================================================
    // Neverland（健康岛）
    // ============================================================

    /** 今日步数任务是否已完成 */
    const val FLAG_NEVERLAND_STEP_COUNT: String = "Flag_Neverland_StepCount"

    /** 今日健康岛签到是否已处理 */
    const val FLAG_NEVERLAND_SIGN_DONE: String = "AntSports::neverlandDoSign::已签到"

    /** 健康岛：今日独立浏览任务是否已处理 */
    const val FLAG_NEVERLAND_LIGHT_FEEDS_DONE = "AntSports::neverlandLightFeedsDone"

    /** 健康岛：按 mapId 维度记录今日奖励明确不可领取前缀 */
    const val FLAG_NEVERLAND_REWARD_UNAVAILABLE_PREFIX = "AntSports::neverlandRewardUnavailable::"

    // ============================================================
    // AntForest（蚂蚁森林）
    // ============================================================

    /** 森林 PK：今日已判定无需处理（未加入/赛季未开启），用于避免重复请求触发风控 */
    const val FLAG_ANTFOREST_PK_SKIP_TODAY: String = "AntForest::pkSkipToday"

    /** 森林 1V1 能量挑战赛：今日已查询并处理待领奖励 */
    const val FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE: String = "AntForest::energyPvpChallengeDone"

    /** 森林：光盘行动今日已完成 */
    const val FLAG_ANTFOREST_ECOLIFE_PHOTO_GUANGPAN = "EcoLife::photoGuangPan"

    /** 森林：光盘行动今日已提示“缓存中无照片” */
    const val FLAG_ANTFOREST_ECOLIFE_PLATE_NOTIFY_EMPTY_CACHE = "EcoLife::plateNotify0"

    /** 森林：光盘行动今日已提示“请先完成一次光盘打卡” */
    const val FLAG_ANTFOREST_ECOLIFE_PLATE_NOTIFY_NO_PHOTO = "EcoLife::plateNotify1"

    /** 森林：打地鼠今日已执行 */
    const val FLAG_ANTFOREST_WHACK_MOLE_EXECUTED = "forest::whackMole::executed"

    /** 森林：保护地巡护机会兑换今日已达上限 */
    const val FLAG_ANTFOREST_PATROL_CHANCE_EXCHANGE_LIMIT = "AntForest::exchangePatrolChanceLimit"

    /** 森林：双击卡时间槽已消费到的索引 */
    const val FLAG_ANTFOREST_DOUBLE_CARD_TRIGGER_INDEX = "antForest::doubleCard::triggerIndex"

    /** 森林：能量球增强时间槽已消费到的索引 */
    const val FLAG_ANTFOREST_BUBBLE_BOOST_TRIGGER_INDEX = "antForest::bubbleBoost::triggerIndex"

    /** 森林：偷能量倍率卡时间槽已消费到的索引 */
    const val FLAG_ANTFOREST_ROB_MULTIPLIER_CARD_TRIGGER_INDEX = "antForest::robMultiplierCard::triggerIndex"

    /** 森林：活力值兑换次数上限前缀 */
    const val FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX = "forest::VitalityExchangeLimit::"

    /** 森林抽抽乐：常规场景今日已完成 */
    const val FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED = "forest::chouChouLe::normal::completed"

    /** 森林抽抽乐：活动场景今日已完成 */
    const val FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED = "forest::chouChouLe::activity::completed"

    /** 森林抽抽乐：场景完成标记前缀 */
    const val FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_PREFIX = "forest::chouChouLe::"

    /** 森林抽抽乐：动态场景完成标记后缀 */
    const val FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_SUFFIX = "::completed"

    /** 青春特权：今日已领取完成 */
    const val FLAG_ANTFOREST_PRIVILEGE_RECEIVED = "youth_privilege_forest_received"

    /** 青春特权：学生签到今日已处理 */
    const val FLAG_ANTFOREST_PRIVILEGE_STUDENT_TASK = "youth_privilege_student_task"

    // ============================================================
    // AntMember（会员频道 / 积分）
    // ============================================================

    /** 今日是否已处理「会员签到」 */
    const val FLAG_ANTMEMBER_MEMBER_SIGN_DONE: String = "AntMember::memberSignDone"

    /** 今日会员任务已处理到无需继续刷新 */
    const val FLAG_ANTMEMBER_MEMBER_TASK_EMPTY_TODAY: String = "AntMember::memberTaskEmptyToday"

    /** 今日会员任务因风控/离线止损，不再继续刷新 */
    const val FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY: String = "AntMember::memberTaskRiskStopToday"

    /** 今日贴纸领取任务 */
    const val FLAG_ANTMEMBER_STICKER: String = "Flag_AntMember_Sticker"

    /** 会员积分权益兑换：今日已完成权益列表刷新/扫描 */
    const val FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE: String = "memberBenefit::refresh"

    /** 今日游戏中心签到、平台任务、乐豆和赚现金签到是否已处理 */
    const val FLAG_ANTMEMBER_GAME_CENTER_DONE = "AntMember::gameCenterDone"

    /** 今日安心豆签到与守护者奖励是否已处理 */
    const val FLAG_ANTMEMBER_BEAN_SIGN_DONE = "AntMember::beanSignInDone"

    /** 今日蚂蚁保保障金是否已处理 */
    const val FLAG_ANTMEMBER_INSURED_GOLD_DONE = "AntMember::insuredGoldDone"

    /** 余额宝体验金：任务完成前缀 */
    const val FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX = "AntMember::yebExpGoldTask::"

    /** 余额宝体验金：今日签到已处理 */
    const val FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE = "AntMember::yebExpGoldSignDone"

    /** 余额宝体验金：今日兑换检查已处理 */
    const val FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE = "AntMember::yebExpGoldExchangeDone"

    /** 余额宝体验金：今日待使用券已转换 */
    const val FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE = "AntMember::yebExpGoldVoucherConvertDone"

    // ============================================================
    // 芝麻信用 / 芝麻粒
    // ============================================================

    /** 芝麻信用：今日是否已处理全部可执行任务 */
    const val FLAG_SESAME_DO_ALL_AVAILABLE_TASK: String = "AntSesameCredit::doAllAvailableSesameTask"

    /** 芝麻树：今日任务奖励已尝试领取，等待服务端刷新确认 */
    const val FLAG_SESAME_ZHIMA_TREE_TASK_HANDLED_TODAY: String =
        "AntSesameCredit::zhimaTreeTaskHandledToday"

    /** 芝麻信用：当日加入任务次数已达上限 */
    const val FLAG_SESAME_JOIN_LIMIT_REACHED: String = "AntSesameCredit::sesameJoinLimitReached"

    /** 芝麻信用：今日是否已处理芝麻粒福利签到 */
    const val FLAG_SESAME_ZML_CHECKIN_DONE: String = "AntSesameCredit::zmlCheckInDone"

    /** 芝麻信用：今日是否已处理芝麻粒领取 */
    const val FLAG_SESAME_COLLECT_DONE: String = "AntSesameCredit::collectSesameDone"

    /** 芝麻信用：芝麻粒炼金次日奖励是否已领取 */
    const val FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD: String = "AntSesameCredit::alchemy::nextDayAward"

    /** 芝麻信用：芝麻粒兑换今日是否已处理 */
    const val FLAG_SESAME_GRAIN_EXCHANGE_DONE: String = "AntSesameCredit::sesameGrainExchangeDone"

    /** 信用 2101：图鉴章节任务是否全部完成 */
    const val FLAG_CREDIT2101_CHAPTER_TASK_DONE: String = "FLAG_Credit2101_ChapterTask_Done"

    /** 信用 2101：事件当日计数前缀 */
    const val FLAG_CREDIT2101_EVENT_COUNT_PREFIX: String = "2101_Event_"

    /** 信用 2101：事件当日计数后缀 */
    const val FLAG_CREDIT2101_EVENT_COUNT_SUFFIX: String = "_COUNT_TODAY"

    /** 商家服务：每日签到 */
    const val FLAG_ANTMEMBER_MERCHANT_SIGN_DONE: String = "AntMember::merchantSignDone"

    /** 商家服务：今日积分任务是否已处理 */
    const val FLAG_ANTMEMBER_MERCHANT_MORE_TASK_DONE: String = "AntMember::merchantMoreTaskDone"

    /** 商家服务：开门打卡签到（06:00-12:00） */
    const val FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE: String = "AntMember::merchantKmdkSignInDone"

    /** 商家服务：开门打卡报名 */
    const val FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE: String = "AntMember::merchantKmdkSignUpDone"

    /** 黄金票：今日是否已处理签到 */
    const val FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE: String = "AntMember::goldTicketSignDone"

    /** 黄金票：首页收取/任务扫描是否已处理 */
    const val FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE: String = "AntMember::goldTicketHomeDone"

    /** 黄金票：福利中心任务是否已处理 */
    const val FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE: String = "AntMember::goldTicketWelfareDone"

    /** 黄金票：今日是否已完成提取检查，无需再次尝试 */
    const val FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE: String = "AntMember::goldTicketConsumeDone"

    // ============================================================
    // 运动任务（AntSports）
    // ============================================================

    /** 运动任务大厅：今日是否已循环处理 */
    const val FLAG_ANTSPORTS_TASK_CENTER_DONE: String = "Flag_AntSports_TaskCenter_Done"

    /** 今日步数同步是否已完成 */
    const val FLAG_ANTSPORTS_SYNC_STEP_DONE: String = "FLAG_ANTSPORTS_syncStep_Done"

    /** 今日运动日常任务是否已完成 */
    const val FLAG_ANTSPORTS_DAILY_TASKS_DONE: String = "FLAG_ANTSPORTS_dailyTasks_Done"

    /** 今日运动问答是否已处理 */
    const val FLAG_ANTSPORTS_MOTION_DAILY_QUIZ_DONE: String = "AntSports::motionDailyQuizDone"

    /** 运动签到：今日已处理或已进入业务止损 */
    const val FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY: String = "AntSports::checkInHandledToday"

    /** 运动首页气泡任务：按 taskId 维度的当日冷却前缀 */
    const val FLAG_ANTSPORTS_HOME_BUBBLE_COOLDOWN_PREFIX = "AntSports::homeBubbleCooldown::"

    /** 走路挑战赛线上赛报名：今日报名不可继续，停止重复报名 */
    const val FLAG_ANTSPORTS_WALK_CHALLENGE_SIGNUP_BLOCKED_TODAY =
        "AntSports::walkChallengeSignupBlockedToday"

    /** 走路挑战赛线上赛：今日已提交一次运动记录 */
    const val FLAG_ANTSPORTS_WALK_CHALLENGE_PROGRESS_DONE =
        "AntSports::walkChallengeProgressDoneToday"

    /** 运动路线：今日已尝试复活步数且无可继续复活资源 */
    const val FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED = "AntSports::routeReviveTried"

    /** 健康岛：今日能量不足以执行单倍建造 */
    const val FLAG_ANTSPORTS_NEVERLAND_ENERGY_LIMIT = "AntSports::neverlandEnergyLimit"

    // ============================================================
    // 合种 / 海洋
    // ============================================================

    /** 真爱合种：今日已浇水 */
    const val FLAG_ANTCOOPERATE_LOVE_TEAM_WATER = "love::teamWater"

    /** 神奇海洋：帮助好友清理垃圾今日已达到上限 */
    const val FLAG_ANTOCEAN_HELP_CLEAN_ALL_FRIEND_LIMIT = "Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT"

    /** 神奇海洋：今日任务列表已确认无可执行项 */
    const val FLAG_ANTOCEAN_TASKS_DONE = "AntOcean::tasksDone"

    // ============================================================
    // 农场 / 新村 / 团队
    // ============================================================

    /** 团队浇水：今日次数统计 */
    const val FLAG_TEAM_WATER_DAILY_COUNT: String = "Flag_Team_Weater_Daily_Count"

    /** 农场组件：每日回访奖励 */
    const val FLAG_ANTORCHARD_WIDGET_DAILY_AWARD: String = "Flag_Antorchard_Widget_Daily_Award"

    /** 农场：今日施肥次数 */
    const val FLAG_ANTORCHARD_SPREAD_MANURE_COUNT: String = "FLAG_Antorchard_SpreadManure_Count"

    /** 摇钱树：今日施肥次数 */
    const val FLAG_ANTORCHARD_SPREAD_MANURE_COUNT_YEB = "ANTORCHARD_SPREAD_MANURE_COUNT_YEB"

    /** 摇钱树：今日是否已收取金币树奖励 */
    const val FLAG_ANTORCHARD_MONEY_TREE_COLLECTED = "ANTORCHARD_MONEY_TREE_COLLECTED"

    /** 农场好友助力：好友关系无效前缀 */
    const val FLAG_ANTORCHARD_ASSIST_RELATION_INVALID_PREFIX = "orchard::assistRelationInvalid::"

    /** 福气鱼池：今日签到已处理 */
    const val FLAG_ANTFISHPOND_SIGN_DONE = "AntFishPond::signDone"

    /** 福气鱼池：每日宝箱已领取 */
    const val FLAG_ANTFISHPOND_GIFT_BOX_DONE = "AntFishPond::giftBoxDone"

    /** 福气鱼池：明日钓竿奖励已领取 */
    const val FLAG_ANTFISHPOND_TOMORROW_ROD_DONE = "AntFishPond::tomorrowRodDone"

    /** 福气鱼池：今日稳定任务已无可执行项 */
    const val FLAG_ANTFISHPOND_TASKS_DONE = "AntFishPond::tasksDone"

    /** 福气鱼池：已达到兑换条件，但兑换 RPC 未接入 */
    const val FLAG_ANTFISHPOND_EXCHANGE_REACHED = "AntFishPond::exchangeReached"

    /** 福气鱼池：缺少 fishpondAngle riskToken */
    const val FLAG_ANTFISHPOND_RISK_TOKEN_MISSING = "AntFishPond::riskTokenMissing"

    /** 福气鱼池：今日自动钓鱼次数 */
    const val FLAG_ANTFISHPOND_FISH_COUNT = "AntFishPond::fishCount"

    /** 福气鱼池：今日自动钓鱼达到配置上限 */
    const val FLAG_ANTFISHPOND_FISH_LIMIT_REACHED = "AntFishPond::fishLimitReached"

    /** 蚂蚁新村：今日丢肥料是否达到上限 */
    const val FLAG_ANTSTALL_THROW_MANURE_LIMIT: String = "Flag_AntStall_Throw_Manure_Limit"

    /** 蚂蚁新村：村庄路线图今日已进入前缀 */
    const val FLAG_ANTSTALL_ROADMAP_VISITED_PREFIX: String = "stall::roadmap::"

    /** 今日小鸡抽抽乐是否已完成 */
    const val FLAG_FARM_CHOUCHOULE_FINISHED = "antFarm::chouChouLeFinished"

    /** 今日改分/小游戏是否已完成 */
    const val FLAG_FARM_GAME_FINISHED = "antFarm::farmGameFinished"

    /** 今日饲料任务是否已完成 */
    const val FLAG_FARM_TASK_FINISHED = "antFarm::farmTaskFinished"

    /** 今日多阶段任务是否已完成 */
    const val FLAG_FARM_MULTI_STAGE_TASK_FINISHED = "AntFarm::multiStageTaskFinished"

    /** 庄园：加速卡每日次数上限标记 */
    const val FLAG_FARM_ACCELERATE_LIMIT = "antFarm::accelerateLimit"

    /** 庄园：特殊食品今日已使用数量 */
    const val FLAG_FARM_SPECIAL_FOOD_DAILY_COUNT = "antFarm::specialFoodDailyCount"

    /** 庄园：特殊食品今日已达自定义上限 */
    const val FLAG_FARM_SPECIAL_FOOD_LIMIT = "antFarm::specialFoodLimit"

    /** 庄园：排位赛特殊食品今日已使用数量 */
    const val FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_DAILY_COUNT =
        "antFarm::specialFoodDonationCompetitionDailyCount"

    /** 庄园：排位赛特殊食品今日已达自定义上限 */
    const val FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_LIMIT =
        "antFarm::specialFoodDonationCompetitionLimit"

    /** 庄园：今日是否已签到 */
    const val FLAG_FARM_SIGNED = "antFarm::signed"

    /** 庄园：今日帮喂次数已达上限 */
    const val FLAG_FARM_FEED_FRIEND_LIMIT = "antFarm::feedFriendLimit"

    /** 庄园：按好友维度记录帮喂上限的前缀 */
    const val FLAG_FARM_FEED_FRIEND_LIMIT_PREFIX = "antFarm::feedFriendLimit::"

    /** 庄园：乐园币兑换达到上限的前缀 */
    const val FLAG_FARM_PARADISE_COIN_EXCHANGE_LIMIT_PREFIX = "farm::paradiseCoinExchangeLimit::"

    /** 庄园：任务限流标记前缀 */
    const val FLAG_FARM_TASK_LIMIT_PREFIX = "farm::task::limit::"

    /** 庄园：好友串门邀请今日已处理 */
    const val FLAG_FARM_INVITE_FRIEND_VISIT_FAMILY = "antFarm::inviteFriendVisitFamily"

    /** 庄园：家庭批量串门送扭蛋今日已处理 */
    const val FLAG_FARM_FAMILY_BATCH_INVITE_P2P = "antFarm::familyBatchInviteP2P"

    /** 庄园答题：今日已答题 */
    const val FLAG_FARM_QUESTION_ANSWERED = "farmQuestion::answered"

    /** 庄园答题：今日已缓存明日答案 */
    const val FLAG_FARM_QUESTION_CACHE = "farmQuestion::cache"

    /** 庄园饲料任务：时间触发槽已消费到的索引 */
    const val FLAG_FARM_TASK_TRIGGER_INDEX = "antFarm::farmTask::triggerIndex"

    /** 庄园抽抽乐：限时任务今日已结束前缀 */
    const val FLAG_FARM_CHOUCHOULE_LIMITED_ENDED_PREFIX = "antFarm::chouchouleLimitedEnded::"

    /** 庄园：普通每日公益捐蛋今日已完成前缀 */
    const val FLAG_FARM_DAILY_DONATION_DONE_PREFIX = "antFarm::dailyDonationDone|"

    /** 庄园：今日已捐蛋总数，普通公益捐蛋与捐蛋排位赛共享 */
    const val FLAG_FARM_DONATION_COUNT = "antFarm::donationCount|"

    /** 庄园：捐蛋排位赛奖励今日已领取 */
    const val FLAG_FARM_DONATION_COMPETITION_AWARD_RECEIVED = "antFarm::donationCompetitionAwardReceived"

    /** 庄园家庭：今日签到已处理 */
    const val FLAG_FARM_FAMILY_SIGNED = "antFarm::familyDailySign"

    /** 庄园家庭：今日一起睡觉已处理 */
    const val FLAG_FARM_FAMILY_SLEEP_TOGETHER = "antFarm::familySleepTogether"

    /** 庄园家庭：今日道早安已处理 */
    const val FLAG_FARM_FAMILY_DELIVER_MSG_SEND = "antFarm::deliverMsgSend"

    /** 庄园家庭：今日好友分享已处理 */
    const val FLAG_FARM_FAMILY_SHARE_TO_FRIENDS = "antFarm::familyShareToFriends"

    /** 森林：能量雨机会卡今日已使用 */
    const val FLAG_FOREST_RAIN_CHANCE_CARD = "AntForest::useEnergyRainChanceCard"

    /** 森林：限时能量雨机会今日使用前缀 */
    const val FLAG_FOREST_RAIN_LIMIT_TIME_CHANCE_PREFIX = "AntForest::useEnergyRainChanceCard::LIMIT_TIME_ENERGY_RAIN_CHANCE::"

    /** 森林：能量雨赠送已达到今日上限标记 */
    const val FLAG_FOREST_RAIN_GRANT_EXCEED = "AntForest::grantEnergyRainExceed"

    /** 森林：能量雨附加游戏任务标记 */
    const val FLAG_FOREST_RAIN_GAME_TASK = "AntForest::EnergyRainGameTask"

}
