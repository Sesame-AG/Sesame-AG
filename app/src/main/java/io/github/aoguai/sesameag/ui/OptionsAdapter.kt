package io.github.aoguai.sesameag.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 选项适配器
 * 用于在列表视图中显示选项
 * 
 * @Description 好友统计，列表长按菜单
 * @UpdateDate 2024/10/23
 * @UpdateTime 16:39
 */
class OptionsAdapter private constructor(
    private val context: Context
) : BaseAdapter() {

    private val list: ArrayList<String> = ArrayList<String>().apply {
        // 初始化列表项
        add("查看森林")
        add("查看庄园")
        add("查看资料")
        add("删除")
    }

    override fun getCount(): Int {
        // 返回列表项的数量
        return list.size
    }

    override fun getItem(position: Int): Any {
        // 返回指定位置的列表项
        return list[position]
    }

    override fun getItemId(position: Int): Long {
        // 返回列表项的唯一ID，这里简单地使用位置作为ID
        return position.toLong()
    }

    @SuppressLint("InflateParams")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // 复用convertView以提高性能
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_1, null)
        
        // 获取TextView并设置文本
        val txt = view as TextView
        txt.text = getItem(position).toString()
        return view
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var adapter: OptionsAdapter? = null

        @JvmStatic
        fun get(c: Context): OptionsAdapter {
            return adapter ?: OptionsAdapter(c).also { adapter = it }
        }
    }
}

