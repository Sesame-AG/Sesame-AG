package io.github.aoguai.sesameag.entity

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
        OtherEntity("assignRights", "使用顶梁柱特权👷‍♂️"),
        OtherEntity("feedFamilyAnimal", "帮喂小鸡🐔"),
        OtherEntity("sleepTogether", "去睡觉🛌"),
        OtherEntity("eatTogetherConfig", "请吃美食🍲"),
        OtherEntity("familyDonateStep", "运动公益捐步🚶"),
        OtherEntity("deliverMsgSend", "道早安🌞"),
        OtherEntity("ExchangeFamilyDecoration", "兑换装饰物品🧱"),
        OtherEntity("shareToFriends", "好友分享🙆‍♂️|下方配置排除列表"),
    )

}

