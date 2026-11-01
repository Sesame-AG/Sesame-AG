package io.github.aoguai.sesameag.task.EcoProtection


import android.net.Uri
import io.github.aoguai.sesameag.data.Status.Companion.ancientTreeToday
import io.github.aoguai.sesameag.data.Status.Companion.canAncientTreeToday
import io.github.aoguai.sesameag.entity.AreaCode
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.reserve.ReserveRpcCall
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import org.json.JSONObject
import java.util.Calendar

class EcoProtection : ModelTask() {
    override fun getName(): String? {
        return "生态保护"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.FOREST
    }

    override fun getIcon(): String {
        return "EcoProtection.png"
    }

    private var ancientTreeOnlyWeek: BooleanModelField? = null
    private var ancientTreeCityCodeList: SelectModelField? = null
    private var marathonEnergyLimit: IntegerModelField? = null
    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("ancientTreeOnlyWeek", "古树保护 | 仅周一三五", false).withDesc(
                "开启后在周一、周三、周五执行古树保护；关闭后早上 8 点后每天都会尝试执行。"
            ).also { ancientTreeOnlyWeek = it }
        )
        modelFields.addField(
            SelectModelField(
                "ancientTreeCityCodeList",
                "古树保护 | 城市区划",
                LinkedHashSet<String?>()
            ) { AreaCode.getList() }.withDesc(
                "选择需要自动保护古树的城市区划代码；只会处理列表中的城市，留空时不会执行古树保护。"
            ).also { ancientTreeCityCodeList = it })
        modelFields.addField(
            IntegerModelField("marathonEnergyLimit", "马拉松证书 | 单项能量上限（g）", 0)
                .withDesc("0 关闭；仅按活动实际成本领取首张证书，已有证书不追加助力。成本超过上限时不兑换。")
                .also { marathonEnergyLimit = it }
        )
        return modelFields
    }

    override fun check(): Boolean {
        if (!super.check()) return false
        if ((marathonEnergyLimit?.value ?: 0) > 0) return true

        if (!TaskCommon.IS_AFTER_8AM) {
            return false
        }

        if (ancientTreeOnlyWeek?.value == true) {
            val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            return dayOfWeek == Calendar.MONDAY ||
                dayOfWeek == Calendar.WEDNESDAY ||
                dayOfWeek == Calendar.FRIDAY
        }
        return true
    }

    override suspend fun runSuspend() {
        try {
            Log.forest("开始执行${getName() ?: ""}")
            val cityCodes = ancientTreeCityCodeList?.value?.filterNotNull()?.toMutableList() ?: mutableListOf()
            val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            if (TaskCommon.IS_AFTER_8AM && (ancientTreeOnlyWeek?.value != true ||
                    day in setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY))) {
                ancientTree(cityCodes)
            }
            val marathonLimit = marathonEnergyLimit?.value ?: 0
            if (marathonLimit > 0) collectMarathonCertificates(marathonLimit)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "start.run err:",t)
        } finally {
            Log.forest("结束执行${getName() ?: ""}")
        }
    }

    companion object {
        private val TAG: String = EcoProtection::class.java.getSimpleName()

        private fun collectMarathonCertificates(energyLimit: Int) {
            try {
                val catalog = JSONObject(ReserveRpcCall.queryTreeItemsForExchange("AVAILABLE", "special"))
                if (!ResChecker.checkRes(TAG, catalog)) {
                    Log.error(TAG, "马拉松活动目录查询失败 raw=$catalog")
                    return
                }
                val items = catalog.getJSONArray("treeItems")
                val activities = mutableSetOf<String>()
                for (index in 0 until items.length()) {
                    val extend = items.getJSONObject(index).optJSONObject("extendInfo") ?: continue
                    if (extend.optString("activityType") != "marathon") continue
                    val link = Uri.parse(extend.getString("actionUrl"))
                    val activityId = link.getQueryParameter("activityId")
                        ?: link.getQueryParameter("url")?.let { Uri.parse(it).getQueryParameter("activityId") }
                    if (activityId.isNullOrBlank()) {
                        Log.error(TAG, "马拉松活动链接缺少activityId raw=$extend")
                        continue
                    }
                    if (!activities.add(activityId)) continue
                    val query = JSONObject().put("donateQueryActionParam", "marathonWater")
                    val home = JSONObject(EcoProtectionRpcCall.doRubickActivity("marathonHome", activityId, query))
                    if (!ResChecker.checkRes(TAG, home)) {
                        Log.error(TAG, "马拉松活动查询失败 activityId=$activityId raw=$home")
                        continue
                    }
                    val data = home.getJSONObject("resultData")
                    if (data.getBoolean("certLockStatus")) continue
                    val cost = data.getJSONObject("donateConfigVO").getInt("donateNum")
                    val balance = data.getInt("currentEnergy")
                    if (cost <= 0 || balance < 0) {
                        Log.error(TAG, "马拉松证书成本或余额无效 activityId=$activityId raw=$data")
                        continue
                    }
                    if (cost > energyLimit || balance < cost) {
                        Log.forest("马拉松证书🏅[${extend.optString("activityName", activityId)}]成本${cost}g，超过预算或余额不足")
                        continue
                    }
                    val before = data.optJSONObject("activityCertVO")?.optInt("energy", 0) ?: 0
                    val exchange = JSONObject(EcoProtectionRpcCall.doRubickActivity(
                        "marathonWater", activityId, JSONObject().put("donateNum", cost).put("incrNum", cost),
                    ))
                    if (!ResChecker.checkRes(TAG, exchange)) {
                        Log.error(TAG, "马拉松证书申请返回失败 activityId=$activityId raw=$exchange")
                    }
                    val refreshed = JSONObject(EcoProtectionRpcCall.doRubickActivity("marathonHome", activityId, query))
                    if (!ResChecker.checkRes(TAG, refreshed)) {
                        Log.error(TAG, "马拉松证书回查失败 activityId=$activityId raw=$refreshed")
                        continue
                    }
                    val after = refreshed.getJSONObject("resultData")
                    val certificate = after.optJSONObject("activityCertVO")
                    if (after.optBoolean("certLockStatus") && certificate != null && certificate.optInt("energy", -1) >= before + cost) {
                        Log.forest("马拉松证书🏅[${certificate.optString("name", activityId)}]已确认，消耗${cost}g")
                    } else {
                        Log.error(TAG, "马拉松证书状态未确认 activityId=$activityId raw=$refreshed")
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
                Log.printStackTrace(TAG, "马拉松证书处理异常", t)
            }
        }

        private fun ancientTree(ancientTreeCityCodeList: MutableCollection<String>) {
            try {
                for (cityCode in ancientTreeCityCodeList) {
                    if (!canAncientTreeToday(cityCode)) continue
                    ancientTreeProtect(cityCode)
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "ancientTree err:",th)
            }
        }

        private fun ancientTreeProtect(cityCode: String) {
            try {
                val jo = JSONObject(EcoProtectionRpcCall.homePage(cityCode))
                if (ResChecker.checkRes(TAG, jo)) {
                    val data = jo.getJSONObject("data")
                    if (!data.has("districtBriefInfoList")) {
                        return
                    }
                    val districtBriefInfoList = data.getJSONArray("districtBriefInfoList")
                    for (i in 0..<districtBriefInfoList.length()) {
                        val districtBriefInfo = districtBriefInfoList.getJSONObject(i)
                        val userCanProtectTreeNum = districtBriefInfo.optInt("userCanProtectTreeNum", 0)
                        if (userCanProtectTreeNum < 1) continue
                        val districtInfo = districtBriefInfo.getJSONObject("districtInfo")
                        val districtCode = districtInfo.getString("districtCode")
                        districtDetail(districtCode)
                    }
                    ancientTreeToday(cityCode)
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG,"ancientTreeProtect err:", th)
            }
        }

        private fun districtDetail(districtCode: String?) {
            try {
                var jo = JSONObject(EcoProtectionRpcCall.districtDetail(districtCode))
                if (ResChecker.checkRes(TAG, jo)) {
                    var data = jo.getJSONObject("data")
                    if (!data.has("ancientTreeList")) {
                        return
                    }
                    val districtInfo = data.getJSONObject("districtInfo")
                    var cityCode = districtInfo.getString("cityCode")
                    val cityName = districtInfo.getString("cityName")
                    val districtName = districtInfo.getString("districtName")
                    val ancientTreeList = data.getJSONArray("ancientTreeList")
                    for (i in 0..<ancientTreeList.length()) {
                        val ancientTreeItem = ancientTreeList.getJSONObject(i)
                        if (ancientTreeItem.getBoolean("hasProtected")) continue
                        val ancientTreeControlInfo = ancientTreeItem.getJSONObject("ancientTreeControlInfo")
                        val quota = ancientTreeControlInfo.optInt("quota", 0)
                        val useQuota = ancientTreeControlInfo.optInt("useQuota", 0)
                        if (quota <= useQuota) continue
                        val itemId = ancientTreeItem.getString("projectId")
                        val ancientTreeDetail = JSONObject(EcoProtectionRpcCall.projectDetail(itemId, cityCode))
                        if (ResChecker.checkRes(TAG, ancientTreeDetail)) {
                            data = ancientTreeDetail.getJSONObject("data")
                            if (data.getBoolean("canProtect")) {
                                val currentEnergy = data.getInt("currentEnergy")
                                val ancientTree = data.getJSONObject("ancientTree")
                                val activityId = ancientTree.getString("activityId")
                                val projectId = ancientTree.getString("projectId")
                                val ancientTreeInfo = ancientTree.getJSONObject("ancientTreeInfo")
                                val name = ancientTreeInfo.getString("name")
                                val age = ancientTreeInfo.getInt("age")
                                val protectExpense = ancientTreeInfo.getInt("protectExpense")
                                cityCode = ancientTreeInfo.getString("cityCode")
                                if (currentEnergy < protectExpense) break
                                jo = JSONObject(EcoProtectionRpcCall.protect(activityId, projectId, cityCode))
                                if (ResChecker.checkRes(TAG, jo)) {
                                    Log.forest(
                                        ("保护古树🎐[" + cityName + "-" + districtName
                                                + "]#" + age + "年" + name + ",消耗能量" + protectExpense + "g")
                                    )
                                } else {
                                    Log.forest(jo.getString("resultDesc"))
                                    Log.forest(jo.toString())
                                }
                            }
                        } else {
                            Log.forest(jo.getString("resultDesc"))
                            Log.forest(ancientTreeDetail.toString())
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "districtDetail err:",th)
            }
        }
    }
}

