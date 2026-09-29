package com.xyf.mastergrid

import android.content.Context
import org.json.JSONArray

/** 持久化：扫描到的滤镜顺序、用户排序/隐藏、滑动方向校准、悬浮按钮位置。 */
class FilterStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mastergrid", Context.MODE_PRIVATE)

    /** 相机里完整的滤镜顺序（含系统滤镜），用来算当前位置到目标要滑几次。 */
    var allFilters: List<String>
        get() = readList(KEY_ALL)
        set(value) = writeList(KEY_ALL, value)

    /** 自己导入的滤镜（「标准」左边的部分），按用户在设置里拖出来的顺序。 */
    var customOrder: List<String>
        get() = readList(KEY_ORDER)
        set(value) = writeList(KEY_ORDER, value)

    var hidden: Set<String>
        get() = readList(KEY_HIDDEN).toSet()
        set(value) = writeList(KEY_HIDDEN, value.toList())

    var returnAfterSelect: Boolean
        get() = sp.getBoolean(KEY_RETURN, false)
        set(value) = sp.edit().putBoolean(KEY_RETURN, value).apply()

    fun visibleCustom(): List<String> {
        val h = hidden
        return customOrder.filter { it !in h }
    }

    /**
     * 「向后」手势在某个屏幕方向下的手指方向：+1 表示手指往坐标减小的方向滑。
     * 默认值来自实测（横屏 rotation=3 时手指往上滑 = 下一个），其余方向靠运行时自动校准。
     */
    fun swipeSign(rotation: Int): Int = sp.getInt("sign_$rotation", 1)

    fun flipSwipeSign(rotation: Int) {
        sp.edit().putInt("sign_$rotation", -swipeSign(rotation)).apply()
    }

    /**
     * 圆点指示条快速翻页的方向：+1 表示「向后」时手指往坐标增大的一侧出界。
     * 默认值来自实测：竖屏往右 = 向后；横屏 rotation=1 时往上 = 向后。其余方向运行时自动校准。
     */
    fun scrubSign(rotation: Int): Int = sp.getInt("scrub_$rotation", if (rotation == 1) -1 else 1)

    fun flipScrubSign(rotation: Int) {
        sp.edit().putInt("scrub_$rotation", -scrubSign(rotation)).apply()
    }

    fun buttonPos(landscape: Boolean): Pair<Float, Float>? {
        val key = if (landscape) "btn_land" else "btn_port"
        if (!sp.contains("${key}_x")) return null
        return sp.getFloat("${key}_x", 1f) to sp.getFloat("${key}_y", 0.35f)
    }

    fun saveButtonPos(landscape: Boolean, fx: Float, fy: Float) {
        val key = if (landscape) "btn_land" else "btn_port"
        sp.edit().putFloat("${key}_x", fx).putFloat("${key}_y", fy).apply()
    }

    /**
     * 按基底风格分组：风格之间按相机里系统滤镜（「标准」右边）的先后排，没对应上的风格按首次出现排在后面；
     * 组内保持原来的相对顺序。
     */
    fun sortByStyle() {
        val all = allFilters
        val system = all.drop(all.indexOf(STANDARD) + 1)
        val order = customOrder
        val bases = order.map { split(it).second ?: it }.distinct()
            .sortedBy { system.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        customOrder = order.sortedBy { bases.indexOf(split(it).second ?: it) }
    }

    /** 保存一次扫描结果；保留用户原有的排序和隐藏，新滤镜追加到末尾。 */
    fun applyScan(all: List<String>): List<String> {
        val std = all.indexOf(STANDARD)
        val custom = if (std >= 0) all.subList(0, std) else all.filter { SEPARATOR in it }
        val old = customOrder
        allFilters = all
        customOrder = old.filter { it in custom } + custom.filter { it !in old }
        hidden = hidden.filter { it in custom }.toSet()
        return custom
    }

    private fun readList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { arr.getString(it) }
    }

    private fun writeList(key: String, value: List<String>) {
        sp.edit().putString(key, JSONArray(value).toString()).apply()
    }

    companion object {
        const val STANDARD = "标准"
        const val SEPARATOR = " - "

        private const val KEY_ALL = "all_filters"
        private const val KEY_ORDER = "custom_order"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_RETURN = "return_after_select"

        /** 「和光 - 阴天 拍花」→ (「阴天 拍花」, 「和光」)；系统滤镜没有基底风格。 */
        fun split(name: String): Pair<String, String?> {
            val i = name.indexOf(SEPARATOR)
            if (i < 0) return name to null
            return name.substring(i + SEPARATOR.length) to name.substring(0, i)
        }
    }
}
