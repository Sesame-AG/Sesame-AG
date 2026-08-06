package io.github.aoguai.sesameag.ui

import android.content.Context
import android.content.DialogInterface
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField

/**
 * 选择对话框工具类
 * 提供Material3风格的单选对话框
 */
object ChoiceDialog {

    /**
     * 显示单选对话框（Material3 风格）
     *
     * @param context 当前上下文，用于构建对话框
     * @param title 对话框的标题
     * @param choiceModelField 包含选项数据的 ChoiceModelField 对象
     */
    @JvmStatic
    fun show(context: Context, title: CharSequence, choiceModelField: ChoiceModelField) {
        // 使用 Material3 对话框构造器
        @Suppress("UNCHECKED_CAST")
        val items = choiceModelField.getExpandKey() as? Array<CharSequence>
        
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(
                items,
                choiceModelField.value ?: 0
            ) { _, which: Int ->
                choiceModelField.setObjectValue(which)
            }
            .setPositiveButton(context.getString(R.string.ok), null)
            .create()

        // 设置确认按钮颜色
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positiveButton?.setTextColor(
                ContextCompat.getColor(context, R.color.selection_color)
            )
        }
        
        dialog.show()
    }

    // 注意：PriorityModelField 相关的方法已被移除，因为现在使用简单的 BooleanModelField 开关模式
}

