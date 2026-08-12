package io.github.aoguai.sesameag.task.antSports

internal fun AntSports.runSportsCenterWorkflow() {
    if (tiyubiz.value != true) {
        return
    }
    userTaskGroupQuery("SPORTS_DAILY_SIGN_GROUP")
    userTaskGroupQuery("SPORTS_DAILY_GROUP")
    userTaskRightsReceive()
    participate()
}

internal fun AntSports.runBattleForFriendsWorkflow() {
    if (battleForFriends.value != true) {
        return
    }
    buyBackSoldSelfMember()
    if (trainFriend.value == true) {
        queryTrainItem()
    }
    buyMember()
}
