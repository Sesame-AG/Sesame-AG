package io.github.aoguai.sesameag.task.EcoProtection

import io.github.aoguai.sesameag.data.Status.Companion.ancientTreeToday
import io.github.aoguai.sesameag.data.Status.Companion.canAncientTreeToday
import io.github.aoguai.sesameag.entity.AreaCode
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.util.GlobalThreadPools.sleepCompat
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
    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("ancientTreeOnlyWeek", "古树保护 | 仅周一三五", false).withDesc(
                "开启后仅在周一、周三、周五执行古树保护；关闭后早上 8 点后每天都会尝试执行。"
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
        return modelFields
    }

    override fun check(): Boolean {
        if (!super.check()) return false

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
            ancientTree(cityCodes)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "start.run err:",t)
        } finally {
            Log.forest("结束执行${getName() ?: ""}")
        }
    }

    companion object {
        private val TAG: String = EcoProtection::class.java.getSimpleName()
        private fun ancientTree(ancientTreeCityCodeList: MutableCollection<String>) {
            try {
                for (cityCode in ancientTreeCityCodeList) {
                    if (!canAncientTreeToday(cityCode)) continue
                    ancientTreeProtect(cityCode)
                    sleepCompat(1000L)
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
                        sleepCompat(1000L)
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
                                sleepCompat(200)
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
                        sleepCompat(500L)
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "districtDetail err:",th)
            }
        }
    }
}

