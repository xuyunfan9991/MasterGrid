package com.xyf.mastergrid

import kotlin.math.abs
import kotlin.math.min

/** 滤镜列表的纯逻辑，不依赖 Android，方便单元测试。 */
object FilterLists {
    const val STANDARD = "标准"
    const val SEPARATOR = " - "

    /** 「和光 - 阴天 拍花」→ (「阴天 拍花」, 「和光」)；系统滤镜没有基底风格。 */
    fun split(name: String): Pair<String, String?> {
        val i = name.indexOf(SEPARATOR)
        if (i < 0) return name to null
        return name.substring(i + SEPARATOR.length) to name.substring(0, i)
    }

    /** 自己导入的滤镜：「标准」左边的部分；找不到「标准」时取名字里带「 - 」的。 */
    fun customPart(all: List<String>): List<String> {
        val std = all.indexOf(STANDARD)
        return if (std >= 0) all.subList(0, std).toList() else all.filter { SEPARATOR in it }
    }

    /** 保留用户原来的排序，去掉已经不存在的，新滤镜追加到末尾。 */
    fun mergeOrder(old: List<String>, custom: List<String>): List<String> =
        old.filter { it in custom } + custom.filter { it !in old }

    /**
     * 按基底风格分组：风格之间按相机里系统滤镜（「标准」右边）的先后排，没对应上的风格按首次出现排在后面；
     * 组内保持原来的相对顺序。
     */
    fun sortByStyle(all: List<String>, order: List<String>): List<String> {
        val system = all.drop(all.indexOf(STANDARD) + 1)
        val bases = order.map { split(it).second ?: it }.distinct()
            .sortedBy { system.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return order.sortedBy { bases.indexOf(split(it).second ?: it) }
    }

    /**
     * 扫描时是沿「向后」手势的方向逐个记录的。自己导入的滤镜（名字是「基底 - 名称」）应该在开头，
     * 如果它们反而集中在后半段，说明「向后」手势的方向标反了。
     * 返回正确顺序的列表，以及手势方向是否需要翻转。
     */
    fun orientScan(names: List<String>): Pair<List<String>, Boolean> {
        val std = names.indexOf(STANDARD)
        val cut = if (std >= 0) std else names.size / 2
        val dashBefore = names.take(cut).count { SEPARATOR in it }
        val dashAfter = names.drop(if (std >= 0) std + 1 else cut).count { SEPARATOR in it }
        return if (dashAfter > dashBefore) names.reversed() to true else names to false
    }
}

/**
 * 切换滤镜时顺路校正保存的列表。
 * 记下一路上依次看到的滤镜名：夹在两个相邻已知滤镜之间的陌生名字，就是扫描之后新导入的；
 * 逐格滑动时一步跨过的已知滤镜，就是已经删掉的。
 */
class ListLearner(private val all: List<String>) {
    private val seen = mutableListOf<String>()
    private val removed = linkedSetOf<String>()

    fun see(name: String) {
        if (seen.lastOrNull() != name) seen += name
    }

    fun markRemoved(names: Collection<String>) {
        removed += names
    }

    /** 校正后的完整列表；没有变化时返回 null。 */
    fun result(): List<String>? {
        var list = all.filter { it !in removed }
        var changed = removed.isNotEmpty()
        var i = 0
        while (i < seen.size) {
            if (seen[i] !in list) {
                i++
                continue
            }
            var j = i + 1
            while (j < seen.size && seen[j] !in list) j++
            if (j >= seen.size) break
            val a = list.indexOf(seen[i])
            val b = list.indexOf(seen[j])
            // 只在两端的已知滤镜本来就相邻时插入，位置才确定；快速拨动时漏读几个名字也不会插错
            if (j > i + 1 && abs(a - b) == 1) {
                val fresh = seen.subList(i + 1, j).distinct()
                val at = min(a, b) + 1
                list = list.take(at) + (if (a < b) fresh else fresh.reversed()) + list.drop(at)
                changed = true
            }
            i = j
        }
        return list.takeIf { changed }
    }
}

/** 逐格滑动时对相机的操作：真机上用无障碍手势实现，单元测试里用假相机。 */
interface StepCamera {
    /** 当前滤镜名，读不到时返回 null。 */
    suspend fun name(): String?

    /** 滑一格；手势被系统拒绝时返回 false。 */
    suspend fun swipe(forward: Boolean): Boolean

    /** 等滤镜名从 old 变成别的，返回新名字；一直没变返回 null。 */
    suspend fun awaitChange(old: String): String?

    /** 最近一次滑动的手指方向和「向后」是反的，翻转保存的方向。 */
    fun flipSign()
}

/** 逐格滑到目标滤镜。列表过时（有新导入或删除的滤镜）时也不会来回打转。 */
object Stepper {
    const val MAX_STEPS = 120
    private const val MAX_VISITS = 4

    /** 成功返回 null，失败返回给用户看的原因。 */
    suspend fun walk(all: List<String>, target: String, cam: StepCamera, learner: ListLearner): String? {
        val ti = all.indexOf(target)
        val short = FilterLists.split(target).first
        var stuck = 0
        var lastForward: Boolean? = null
        val visits = HashMap<String, Int>()
        repeat(MAX_STEPS) {
            val cur = cam.name() ?: return "读不到当前滤镜"
            learner.see(cur)
            if (cur == target) return null
            // 兜底：同一个滤镜经过太多次，说明列表和相机对不上了
            if ((visits.merge(cur, 1, Int::plus) ?: 0) > MAX_VISITS) return "找不到「$short」，请重新扫描"
            val ci = all.indexOf(cur)
            // 不认识的滤镜（扫描后新导入的）夹在已知滤镜中间：沿原方向穿过去，否则会在它和上一个之间来回切。
            // 一开始就停在不认识的滤镜上时，先往开头方向找
            val forward = if (ci >= 0) ci < ti else lastForward ?: false
            if (!cam.swipe(forward)) return "滑动手势被系统拒绝"
            val next = cam.awaitChange(cur)
            if (next == null) {
                if (++stuck >= 3) return "滑不动了，可以试试重新扫描"
                if (ci < 0) {
                    // 在不认识的滤镜上滑不动，多半是到了列表一端，掉头找
                    lastForward = !forward
                } else if (stuck == 1) {
                    // 没动：要么在列表一端而方向标反了，要么手势没生效
                    cam.flipSign()
                }
                return@repeat
            }
            val clean = stuck == 0 // 上一下没反应的手势可能迟到，这一步就不一定只走了一格
            stuck = 0
            lastForward = forward
            val ni = all.indexOf(next)
            if (ci < 0 || ni < 0) return@repeat
            if ((ni > ci) != forward) {
                cam.flipSign()
            } else if (clean && abs(ni - ci) > 1) {
                // 一次滑动只走一格，中间跳过的滤镜在相机里已经没有了
                val gone = all.subList(min(ci, ni) + 1, maxOf(ci, ni))
                learner.markRemoved(gone)
                if (target in gone) return "相机里已经没有「$short」了，已从宫格移除"
            }
        }
        return "步数过多，已停止"
    }
}
