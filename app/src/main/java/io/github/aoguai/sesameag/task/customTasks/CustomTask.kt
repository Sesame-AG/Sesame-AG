package io.github.aoguai.sesameag.task.customTasks

/**
 * 庄园子任务枚举
 */
enum class CustomTask(val displayName: String, val isModule: Boolean = false) {
    FOREST_WHACK_MOLE("森林打地鼠"),
    FOREST_ENERGY_RAIN("能量雨"),
    FARM_SEND_BACK_ANIMAL("遣返小鸡"),
    FARM_GAME_LOGIC("庄园游戏改分"),
    FARM_CHOUCHOULE("庄园抽抽乐"),
    FARM_SPECIAL_FOOD("庄园使用特殊美食"),
    FARM_USE_TOOL("使用庄园道具"),
    // 任务模块整体手动触发（用于风控后手动重跑单个模块，跳过 check() 调度门控）
    ANT_FOREST("蚂蚁森林", isModule = true),
    ANT_FARM("蚂蚁庄园", isModule = true),
    ANT_OCEAN("海洋", isModule = true),
    ANT_STALL("新村", isModule = true),
    ANT_DODO("神奇物种", isModule = true),
    ANT_COOPERATE("蚂蚁森林合种", isModule = true),
    ANT_MEMBER("会员", isModule = true),
    ANT_SESAME_CREDIT("芝麻信用", isModule = true),
    ANT_ORCHARD("农场", isModule = true),
    ANT_FISH_POND("福气鱼池", isModule = true),
    ANT_SPORTS("运动", isModule = true)
}

