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

    /** 按基底风格分组，规则见 [FilterLists.sortByStyle]。 */
    fun sortByStyle() {
        customOrder = FilterLists.sortByStyle(allFilters, customOrder)
    }

    /** 保存一次扫描（或切换时顺路校正）的结果；保留用户原有的排序和隐藏，新滤镜追加到末尾。返回自己导入的滤镜。 */
    fun applyScan(all: List<String>): List<String> {
        val custom = FilterLists.customPart(all)
        allFilters = all
        customOrder = FilterLists.mergeOrder(customOrder, custom)
        hidden = hidden.filter { it in custom }.toSet()
        return custom
    }

    /** 实际跑通过的相机版本（在上面识别出过大师模式），用来在设置页显示适配状态。 */
    var verifiedCameraVersion: String?
        get() = sp.getString(KEY_CAMERA_OK, null)
        set(value) = sp.edit().putString(KEY_CAMERA_OK, value).apply()

    /** 已经提示过「界面认不出」的相机版本，每个版本只提示一次。 */
    var warnedCameraVersion: String?
        get() = sp.getString(KEY_CAMERA_WARNED, null)
        set(value) = sp.edit().putString(KEY_CAMERA_WARNED, value).apply()

    private fun readList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { arr.getString(it) }
    }

    private fun writeList(key: String, value: List<String>) {
        sp.edit().putString(key, JSONArray(value).toString()).apply()
    }

    private companion object {
        const val KEY_ALL = "all_filters"
        const val KEY_ORDER = "custom_order"
        const val KEY_HIDDEN = "hidden"
        const val KEY_RETURN = "return_after_select"
        const val KEY_CAMERA_OK = "camera_verified"
        const val KEY_CAMERA_WARNED = "camera_warned"
    }
}
