package io.github.aoguai.sesameag.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import io.github.aoguai.sesameag.model.BaseModel
import java.util.Locale
import java.util.TimeZone

object LocaleSettingsApplier {
    private const val TAG = "LocaleSettingsApplier"
    private val originalLocale: Locale by lazy { Locale.getDefault() }
    private val originalTimeZone: TimeZone by lazy { TimeZone.getDefault() }
    private val simplifiedChineseTimeZone: TimeZone by lazy { TimeZone.getTimeZone("Asia/Shanghai") }

    @Volatile
    private var lastAppliedSignature: String? = null

    @JvmStatic
    fun apply(context: Context? = null) {
        val useSimplifiedChinese = BaseModel.languageSimplifiedChinese.value == true
        val targetLocale = if (useSimplifiedChinese) Locale.SIMPLIFIED_CHINESE else originalLocale
        val targetTimeZone = if (useSimplifiedChinese) simplifiedChineseTimeZone else originalTimeZone

        Locale.setDefault(targetLocale)
        TimeZone.setDefault(targetTimeZone)
        updateResources(context, targetLocale)

        val signature = "${targetLocale.toLanguageTag()}@${targetTimeZone.id}"
        if (lastAppliedSignature != signature) {
            lastAppliedSignature = signature
            val modeText = if (useSimplifiedChinese) {
                "已应用简体中文与 Asia/Shanghai 时区"
            } else {
                "已恢复系统默认语言与时区"
            }
            Log.runtime(TAG, modeText)
            Log.record(TAG, modeText)
        }
    }

    private fun updateResources(context: Context?, locale: Locale) {
        if (context == null) {
            return
        }
        applyLocaleToResources(context, locale)
        val appContext = context.applicationContext
        if (appContext != null && appContext !== context) {
            applyLocaleToResources(appContext, locale)
        }
    }

    private fun applyLocaleToResources(context: Context, locale: Locale) {
        val resources = context.resources ?: return
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
