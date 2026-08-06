package io.github.aoguai.sesameag.entity

import io.github.aoguai.sesameag.task.other.credit2101.Credit2101

class OtherEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}

object OtherEntityProvider {
    @JvmStatic
    fun listEcoLifeOptions(): List<OtherEntity> = listOf(
        OtherEntity("tick", "绿色行动🍃"),
        OtherEntity("plate", "光盘行动💽")
    )

    @JvmStatic
    fun listHealthcareOptions(): List<OtherEntity> = listOf(
        OtherEntity("FEEDS", "绿色医疗💉"),
        OtherEntity("BILL", "电子小票🎫")
    )

    @JvmStatic
    fun farmFamilyOption():List<OtherEntity> = listOf(
        OtherEntity("familySign", "每日签到📅"),
        OtherEntity("assignRights", "使用顶梁柱特权👷‍♂️"),
        OtherEntity("familyClaimReward", "领取奖励🏆️"),
        OtherEntity("feedFamilyAnimal", "帮喂小鸡🐔"),
        OtherEntity("sleepTogether", "去睡觉🛌"),
        OtherEntity("eatTogetherConfig", "请吃美食🍲"),
        OtherEntity("familyDonateStep", "运动公益捐步🚶"),
        OtherEntity("deliverMsgSend", "道早安🌞"),
        OtherEntity("ExchangeFamilyDecoration", "兑换装饰物品🧱"),
        OtherEntity("shareToFriends", "好友分享🙆‍♂️|下方配置排除列表"),
    )

    //信用2101任务列表
    @JvmStatic
    fun listCreditOptions(): List<OtherEntity> = listOf(
        OtherEntity(Credit2101.EventType.MINI_GAME_ELIMINATE, "消除小游戏 🎮"),
        OtherEntity(Credit2101.EventType.MINI_GAME_COLLECTYJ, "收集小游戏 🏺"),
        OtherEntity(Credit2101.EventType.MINI_GAME_MATCH3, "击杀小游戏 🧩"),
        OtherEntity(Credit2101.EventType.GOLD_MARK, "金色印记 🟡"),
        OtherEntity(Credit2101.EventType.BLACK_MARK, "黑色印记 ⚫"),
        OtherEntity(Credit2101.EventType.SPACE_TIME_GATE, "时空之门 🌀")
    )

}

