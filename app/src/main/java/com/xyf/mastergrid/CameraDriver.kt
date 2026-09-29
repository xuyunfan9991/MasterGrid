package com.xyf.mastergrid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 通过无障碍接口读取 OPPO 相机（com.oplus.camera）大师模式的界面，并用模拟手势切换滤镜。
 * 相机滤镜界面每次滑动正好切换一个滤镜，列表两端不循环。
 */
class CameraDriver(private val svc: AccessibilityService, private val store: FilterStore) {

    enum class Screen { MASTER_MAIN, MASTER_FILTER, OTHER }

    fun screen(): Screen {
        val root = cameraRoot() ?: return Screen.OTHER
        if (root.find(ID_FILTER_HINT) != null) return Screen.MASTER_FILTER
        if (root.find(ID_FILTER_ENTER) == null) return Screen.OTHER
        val desc = root.find(ID_PREVIEW)?.contentDescription ?: root.find(ID_HEADLINE)?.contentDescription
        return if (desc?.contains("大师") == true) Screen.MASTER_MAIN else Screen.OTHER
    }

    fun currentName(): String? =
        cameraRoot()?.find(ID_FILTER_HINT)?.text?.toString()?.takeIf { it.isNotBlank() }

    /** 切到目标滤镜；成功返回 null，失败返回给用户看的原因。 */
    suspend fun goTo(target: String): String? {
        if (!ensureFilterScreen()) return "没能打开滤镜界面"
        val all = store.allFilters
        val ti = all.indexOf(target)
        if (ti < 0) return "列表里没有「${FilterStore.split(target).first}」，请重新扫描"

        // 远距离先用圆点指示条快速拨过去；第二次机会留给方向校准后的重试
        repeat(2) {
            val ci = all.indexOf(currentName() ?: return@repeat)
            if (ci >= 0 && abs(ti - ci) >= SCRUB_MIN_DISTANCE) scrubToward(all, ti)
        }

        // 近距离或快速拨动没停准时，逐格滑动收尾
        var stuck = 0
        repeat(MAX_STEPS) {
            val cur = awaitName() ?: return "读不到当前滤镜"
            if (cur == target) return null
            val ci = all.indexOf(cur)
            // 不认识的滤镜多半是新导入或系统滤镜，先往开头方向找
            val forward = ci in 0 until ti
            Log.d(TAG, "step cur=$cur ($ci) -> $target ($ti) forward=$forward")
            val rot = rotation()
            if (!swipe(forward)) return "滑动手势被系统拒绝"
            val next = waitNameChange(cur)
            if (next == null) {
                // 没动：要么在列表一端而方向标反了，要么手势没生效
                if (++stuck >= 3) return "滑不动了，可以试试重新扫描"
                if (stuck == 1) store.flipSwipeSign(rot)
                return@repeat
            }
            stuck = 0
            val ni = all.indexOf(next)
            Log.d(TAG, "moved to $next ($ni)")
            if (ci >= 0 && ni >= 0 && (ni > ci) != forward) store.flipSwipeSign(rot)
        }
        return "步数过多，已停止"
    }

    /**
     * 把整个滤镜列表滑一遍：先滑到一端，再往回逐个记录。
     * 最后根据「标准」的位置判断哪头是开头，顺便校准滑动方向，并切回扫描前的滤镜。
     */
    suspend fun scan(progress: (String) -> Unit): Result<List<String>> {
        if (!ensureFilterScreen()) return Result.failure(IllegalStateException("没能打开滤镜界面"))
        val start = currentName() ?: return Result.failure(IllegalStateException("读不到当前滤镜"))
        val rot = rotation()

        var cur = start
        var moved = 0
        progress("正在滑到列表一端…")
        while (moved < MAX_STEPS) {
            cur = stepOrNull(forward = false, cur) ?: break
            moved++
        }

        val names = mutableListOf(cur)
        while (names.size < MAX_STEPS) {
            cur = stepOrNull(forward = true, cur) ?: break
            if (cur != names.last()) names += cur
            progress("扫描中… 已记录 ${names.size} 个")
        }

        // 自己导入的滤镜在「标准」左边，且名字都是「基底 - 名称」
        val std = names.indexOf(FilterStore.STANDARD)
        val dashBefore = names.take(if (std >= 0) std else names.size / 2).count { FilterStore.SEPARATOR in it }
        val dashAfter = names.drop(if (std >= 0) std + 1 else names.size / 2).count { FilterStore.SEPARATOR in it }
        val ordered = if (dashAfter > dashBefore) {
            store.flipSwipeSign(rot)
            names.reversed()
        } else {
            names
        }

        val custom = store.applyScan(ordered)
        Log.i(TAG, "scan: ${ordered.size} filters, ${custom.size} custom, reversed=${ordered !== names}")
        progress("扫描完成，正在切回原滤镜…")
        goTo(start)
        return Result.success(custom)
    }

    /** 刚操作完时名字可能短暂读不到；必要时重新进入滤镜界面。 */
    private suspend fun awaitName(): String? {
        var name: String? = null
        if (waitUntil(800, pollMs = 20) { currentName().also { name = it } != null }) return name
        return if (ensureFilterScreen()) currentName() else null
    }

    /** 点左上角返回箭头，从滤镜界面回到大师模式拍摄界面。 */
    suspend fun leaveFilterScreen() {
        val back = cameraRoot()?.find(ID_FILTER_BACK) ?: return
        if (!back.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(back.rect())
        waitUntil(1500) { screen() == Screen.MASTER_MAIN }
    }

    private suspend fun ensureFilterScreen(): Boolean {
        when (screen()) {
            Screen.MASTER_FILTER -> return true
            Screen.OTHER -> return false
            Screen.MASTER_MAIN -> Unit
        }
        val enter = cameraRoot()?.find(ID_FILTER_ENTER) ?: return false
        if (!enter.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(enter.rect())
        if (!waitUntil(2500) { currentName() != null }) return false
        delay(250) // 等进场动画结束，否则第一下滑动可能被吞
        return true
    }

    /** 滑一格并等名字变化；第一次没反应时再试一次，两次都不动视为到头。 */
    private suspend fun stepOrNull(forward: Boolean, cur: String): String? {
        repeat(2) {
            if (!swipe(forward)) return null
            waitNameChange(cur)?.let { return it }
        }
        return null
    }

    /**
     * 快速通道：按住滤镜名下面的圆点指示条（EffectStylePageIndicator）会展开；展开后把手指移到指示条外侧按住不动，
     * 相机会自己连续翻（实测约 0.12 秒一格，比逐格滑动快 2~3 倍）。边按边读名字，到了目标就松手。
     * 注意别在出界后再移动手指：每个 MOVE 事件都会重新计算目标，反而把自动翻页打断。
     * 停在目标上返回 true；否则（多走了一格、方向反了等）交给逐格滑动收尾。
     */
    private suspend fun scrubToward(all: List<String>, ti: Int): Boolean {
        val bar = cameraRoot()?.find(ID_INDICATOR)?.rect() ?: return false
        val startName = currentName() ?: return false
        val si = all.indexOf(startName)
        if (si < 0 || si == ti) return si == ti
        val rot = rotation()
        val sign = store.scrubSign(rot) * (if (ti > si) 1 else -1)
        val vertical = bar.height() > bar.width()

        // 沿指示条长轴、朝 sign 方向的端点再往外 margin
        fun end(r: Rect, dir: Int, margin: Float): PointF = when {
            vertical && dir > 0 -> PointF(r.exactCenterX(), r.bottom + margin)
            vertical -> PointF(r.exactCenterX(), r.top - margin)
            dir > 0 -> PointF(r.right + margin, r.exactCenterY())
            else -> PointF(r.left - margin, r.exactCenterY())
        }

        // 1) 按在反方向那一端的圆点上。展开结束时它会把手指下的点记成「已请求」，
        //    按在中间时这个点可能恰好就是下一格，于是永远不翻；按在反方向尽头就不可能撞上。
        //    （之前试过「展开前先挪开手指」，竖屏有效但横屏会让它完全不响应。）
        val press = end(bar, -sign, -dp(PRESS_INSET_DP))
        fun Rect.longSide() = maxOf(width(), height())
        // 带 willContinue 的手势系统会立刻回调，按住的时间要自己等；展开动画 200ms 结束后才响应拖动。
        // 刚快速翻过很多格时相机还在处理预设切换，会忽略这次按下（不展开），这时松开重按。
        var stroke: GestureDescription.StrokeDescription? = null
        var wide: Rect? = null
        for (attempt in 1..PRESS_ATTEMPTS) {
            val pressedAt = System.currentTimeMillis()
            val s0 = startStroke(press, HOLD_STROKE_MS) ?: return false
            // 展开动画一开始控件就会变宽，100ms 内没变宽说明这次按下被忽略了，立刻重按
            var r: Rect? = null
            waitUntil(EXPAND_DETECT_MS, pollMs = 20) {
                r = cameraRoot()?.find(ID_INDICATOR)?.rect()?.takeIf { it.longSide() > bar.longSide() }
                r != null
            }
            if (r != null) {
                delay(PRESS_MS - (System.currentTimeMillis() - pressedAt)) // 等展开动画结束才响应拖动
                stroke = s0
                wide = cameraRoot()?.find(ID_INDICATOR)?.rect() ?: r
                break
            }
            Log.d(TAG, "indicator not expanded, re-press ($attempt)")
            endStroke(s0, press)
            delay(60)
        }
        if (stroke == null || wide == null) return false
        // 2) 展开后控件变宽（实测 368px → 512px），移到展开后的边界外才是快速档。
        //    一步快速移过去，尽量不在中间的圆点上产生 MOVE（经过中间的点会直接跳过去）
        val outside = end(wide, sign, dp(OUTSIDE_DP))
        stroke = moveStroke(stroke, press, outside, 5) ?: return false

        val t0 = System.currentTimeMillis()
        var lastChange = t0
        var cur = startName
        var ci = si
        var changes = 0
        var reached = false
        while (true) {
            val now = System.currentTimeMillis()
            val idle = if (changes == 0) FIRST_STEP_TIMEOUT_MS else SCRUB_IDLE_MS
            if (now - t0 > SCRUB_TIMEOUT_MS || now - lastChange > idle) break // 到头了或没在翻
            delay(10)
            val name = currentName() ?: break
            if (name == cur) continue
            cur = name
            ci = all.indexOf(name)
            changes++
            lastChange = System.currentTimeMillis()
            if (ci == ti) {
                reached = true
                break
            }
            if (ci < 0 || (ti - ci) * (ti - si) < 0) break // 不认识的滤镜或已经越过目标
            if (changes >= 3 && abs(ti - ci) > abs(ti - si)) {
                store.flipScrubSign(rot) // 越翻越远：这个屏幕方向下出界方向标反了
                break
            }
        }
        endStroke(stroke, outside)
        Log.d(TAG, "scrub $si -> $ti stopped at $ci after $changes steps in ${System.currentTimeMillis() - t0}ms")
        delay(SCRUB_SETTLE_MS)
        return reached && currentName() == all[ti]
    }

    private suspend fun startStroke(p: PointF, durationMs: Long): GestureDescription.StrokeDescription? {
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, durationMs, true)
        return s.takeIf { dispatch(it) }
    }

    private suspend fun moveStroke(
        prev: GestureDescription.StrokeDescription, from: PointF, to: PointF, durationMs: Long,
    ): GestureDescription.StrokeDescription? {
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        return prev.continueStroke(path, 0, durationMs, true).takeIf { dispatch(it) }
    }

    private suspend fun endStroke(prev: GestureDescription.StrokeDescription, p: PointF) {
        dispatch(prev.continueStroke(Path().apply { moveTo(p.x, p.y) }, 0, 10, false))
    }

    private suspend fun swipe(forward: Boolean): Boolean {
        val root = cameraRoot() ?: return false
        val area = root.find(ID_PREVIEW)?.rect() ?: return false
        // 指示点排成一列时卡片是上下切换，排成一行时是左右切换
        val vertical = root.find(ID_INDICATOR)?.rect()?.let { it.height() > it.width() }
            ?: (area.width() > area.height())
        val sign = store.swipeSign(rotation()) * (if (forward) 1 else -1)
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val half = (if (vertical) area.height() else area.width()) * 0.22f
        val path = Path().apply {
            if (vertical) {
                moveTo(cx, cy + sign * half)
                lineTo(cx, cy - sign * half)
            } else {
                moveTo(cx + sign * half, cy)
                lineTo(cx - sign * half, cy)
            }
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
    }

    private suspend fun tap(r: Rect): Boolean =
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 0, 50))

    /** 系统偶尔不回调连续手势的结果，所以加超时，免得整个流程卡死。 */
    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        withTimeoutOrNull(stroke.duration + GESTURE_TIMEOUT_MS) { dispatchRaw(stroke) }
            ?: false.also { Log.w(TAG, "gesture callback timeout (continued=${stroke.willContinue()})") }

    private suspend fun dispatchRaw(stroke: GestureDescription.StrokeDescription): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(g: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private suspend fun waitNameChange(
        old: String, timeoutMs: Long = CHANGE_TIMEOUT_MS, settle: Boolean = true,
    ): String? {
        var changed: String? = null
        waitUntil(timeoutMs, pollMs = if (settle) 40 else 10) {
            changed = currentName()?.takeIf { it != old }
            changed != null
        }
        if (changed != null && settle) delay(SETTLE_MS)
        return changed
    }

    private suspend fun waitUntil(timeoutMs: Long, pollMs: Long = 40, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(pollMs)
        }
        return cond()
    }

    private fun dp(v: Float) = v * svc.resources.displayMetrics.density

    private fun cameraRoot(): AccessibilityNodeInfo? {
        svc.windows.forEach { w ->
            val root = w.root
            if (root?.packageName == PKG) return root
        }
        return svc.rootInActiveWindow?.takeIf { it.packageName == PKG }
    }

    private fun rotation(): Int =
        svc.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    private fun AccessibilityNodeInfo.find(id: String): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByViewId("$PKG:id/$id").firstOrNull { it.isVisibleToUser }

    private fun AccessibilityNodeInfo.rect() = Rect().also { getBoundsInScreen(it) }

    companion object {
        const val PKG = "com.oplus.camera"
        private const val TAG = "MasterGrid"

        // 以下 id 来自 ColorOS 16.1 / 相机 6.070.228 的界面结构
        private const val ID_FILTER_HINT = "effect_style_filter_hint"
        private const val ID_INDICATOR = "effect_style_indicator"
        private const val ID_FILTER_BACK = "effect_style_back_icon"
        private const val ID_FILTER_ENTER = "camera_menu_right_enter_button"
        private const val ID_PREVIEW = "preview_talkback"
        private const val ID_HEADLINE = "headline_view"

        private const val MAX_STEPS = 120
        private const val SWIPE_MS = 110L
        private const val CHANGE_TIMEOUT_MS = 700L
        private const val SETTLE_MS = 90L

        // 圆点指示条快速翻页
        private const val SCRUB_MIN_DISTANCE = 3
        private const val PRESS_MS = 260L
        private const val PRESS_INSET_DP = 3f
        private const val HOLD_STROKE_MS = 1000L
        private const val OUTSIDE_DP = 16f // 实测出界 8~25dp 都是约 0.12 秒一格
        private const val SCRUB_TIMEOUT_MS = 10_000L
        private const val SCRUB_IDLE_MS = 1200L
        private const val FIRST_STEP_TIMEOUT_MS = 500L
        private const val PRESS_ATTEMPTS = 4
        private const val EXPAND_DETECT_MS = 100L
        private const val SCRUB_SETTLE_MS = 100L
        private const val GESTURE_TIMEOUT_MS = 400L
    }
}
