package com.xyf.mastergrid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

/** 悬浮按钮 + 3×N 宫格面板 + 提示气泡，全部用无障碍覆盖层窗口，不需要悬浮窗权限。 */
class Overlay(
    private val svc: AccessibilityService,
    private val store: FilterStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onPick(name: String)
        fun onScan()
        fun onOpenSettings()
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val density = svc.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var button: ImageView? = null
    private val buttonLp = baseParams(dp(52), dp(52))
    private var panel: View? = null
    private var panelHiddenAt = 0L
    private var bubble: TextView? = null
    private val bubbleLp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(56)
    }
    private val hideBubble = Runnable { removeBubble() }

    /** 最近一次在滤镜界面读到的滤镜名，用于在宫格里高亮。 */
    var currentName: String? = null

    fun showButton() {
        if (button != null) return
        val b = ImageView(svc).apply {
            setImageResource(R.drawable.ic_grid)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC202124.toInt())
                setStroke(dp(1), 0x55FFFFFF)
            }
            setOnTouchListener(DragListener())
        }
        placeButton()
        wm.addView(b, buttonLp)
        button = b
    }

    val buttonShown get() = button != null

    fun hideButton() {
        button?.let { wm.removeView(it) }
        button = null
    }

    /** 自动操作期间按钮变暗且不接收触摸，避免挡住模拟手势。 */
    fun setBusy(busy: Boolean) {
        val b = button ?: return
        b.alpha = if (busy) 0.35f else 1f
        buttonLp.flags = if (busy) {
            buttonLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            buttonLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        wm.updateViewLayout(b, buttonLp)
    }

    fun onConfigurationChanged() {
        button?.let {
            placeButton()
            wm.updateViewLayout(it, buttonLp)
        }
        if (panel != null) showPanel()
    }

    fun removeAll() {
        hidePanel()
        hideButton()
        removeBubble()
    }

    fun showPanel() {
        hidePanel()
        val screen = screenSize()
        val cardW = min((screen.x * 0.92f).toInt(), dp(440))
        val innerW = cardW - dp(32)

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = rounded(0xF21C1C1E.toInt(), dp(24))
        }

        card.addView(text("我的滤镜", 18f, Color.WHITE, bold = true))
        currentName?.let {
            card.addView(text("当前：${FilterLists.split(it).first}", 12f, 0xFF9AA0A6.toInt()))
        }

        val names = store.visibleCustom()
        val body: View = if (names.isEmpty()) {
            text(
                "还没有滤镜列表。\n点下方「扫描滤镜」，会自动把大师模式的滤镜滑一遍，记下「标准」左边你导入的滤镜。",
                14f, 0xFFDADCE0.toInt(),
            ).apply { setPadding(0, dp(12), 0, dp(12)) }
        } else {
            val gap = dp(8)
            val tileW = (innerW - gap * 2) / 3
            GridLayout(svc).apply {
                columnCount = 3
                names.forEachIndexed { i, name ->
                    val lp = GridLayout.LayoutParams().apply {
                        width = tileW
                        height = dp(56)
                        setMargins(if (i % 3 == 0) 0 else gap, if (i < 3) 0 else gap, 0, 0)
                    }
                    addView(tile(name, name == currentName), lp)
                }
            }
        }
        val scroll = MaxHeightScrollView(svc, (screen.y * (if (screen.x > screen.y) 0.85f else PANEL_MAX_HEIGHT)).toInt() - dp(120)).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        card.addView(scroll, LinearLayout.LayoutParams(innerW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val footer = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(footerButton("扫描滤镜") { hidePanel(); listener.onScan() })
            addView(footerButton("设置") { hidePanel(); listener.onOpenSettings() })
            addView(footerButton("关闭") { hidePanel() })
        }
        card.addView(footer, LinearLayout.LayoutParams(innerW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 面板窗口只有卡片大小：ColorOS 反诈会把「无障碍应用 + 全屏覆盖层」判为风险并直接关掉服务
        card.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hidePanel()
            false
        }
        val lp = baseParams(cardW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        wm.addView(card, lp)
        panel = card
    }

    fun hidePanel() {
        panel?.let {
            wm.removeView(it)
            panelHiddenAt = SystemClock.uptimeMillis()
        }
        panel = null
    }

    fun togglePanel() {
        // 面板打开时点悬浮按钮，会先收到 ACTION_OUTSIDE 把面板关掉，这里别马上又打开
        if (SystemClock.uptimeMillis() - panelHiddenAt < 400) return
        if (panel != null) hidePanel() else showPanel()
    }

    /**
     * 屏幕上方的小提示；sticky 时一直显示到下一条消息。
     * 给了 onTap 时气泡可以点（用来停止正在进行的操作），否则不接收触摸，免得挡住模拟手势。
     */
    fun showMessage(msg: String, sticky: Boolean = false, onTap: (() -> Unit)? = null) {
        handler.removeCallbacks(hideBubble)
        val t = bubble ?: text(msg, 14f, Color.WHITE).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(0xE6202124.toInt(), dp(20))
            gravity = Gravity.CENTER
        }
        t.text = msg
        t.setOnClickListener(onTap?.let { tap -> View.OnClickListener { tap() } })
        bubbleLp.flags = if (onTap != null) {
            bubbleLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            bubbleLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (bubble == null) {
            wm.addView(t, bubbleLp)
            bubble = t
        } else {
            wm.updateViewLayout(t, bubbleLp)
        }
        if (!sticky) handler.postDelayed(hideBubble, if (msg.contains('\n')) 4000 else 2500)
    }

    private fun removeBubble() {
        handler.removeCallbacks(hideBubble)
        bubble?.let { wm.removeView(it) }
        bubble = null
    }

    private fun tile(name: String, selected: Boolean): View {
        val (title, base) = FilterLists.split(name)
        val fg = if (selected) 0xFF1B1B3A.toInt() else Color.WHITE
        return LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = rounded(if (selected) 0xFFC7D2FE.toInt() else 0xFF2E2F33.toInt(), dp(14))
            addView(text(title, 13f, fg, bold = selected).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (base != null) {
                addView(text(base, 10f, if (selected) 0xFF3C3F66.toInt() else 0xFF9AA0A6.toInt()).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
            setOnClickListener {
                hidePanel()
                listener.onPick(name)
            }
        }
    }

    private fun footerButton(label: String, onClick: () -> Unit) =
        text(label, 14f, 0xFFC7D2FE.toInt(), bold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun placeButton() {
        val screen = screenSize()
        val (fx, fy) = store.buttonPos(screen.x > screen.y) ?: (1f to 0.35f)
        buttonLp.x = (fx * (screen.x - buttonLp.width)).toInt()
        buttonLp.y = (fy * (screen.y - buttonLp.height)).toInt()
    }

    private fun saveButtonPos() {
        val screen = screenSize()
        store.saveButtonPos(
            screen.x > screen.y,
            buttonLp.x.toFloat() / (screen.x - buttonLp.width),
            buttonLp.y.toFloat() / (screen.y - buttonLp.height),
        )
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point = Point().also {
        svc.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
    }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** 拖动移动按钮，轻点打开面板。 */
    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = buttonLp.x
                    startY = buttonLp.y
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val screen = screenSize()
                        buttonLp.x = (startX + dx).toInt().coerceIn(0, screen.x - buttonLp.width)
                        buttonLp.y = (startY + dy).toInt().coerceIn(0, screen.y - buttonLp.height)
                        wm.updateViewLayout(v, buttonLp)
                    }
                }

                MotionEvent.ACTION_UP -> if (dragging) saveButtonPos() else togglePanel()
            }
            return true
        }
    }

    private companion object {
        /** 竖屏时面板最多占屏幕高度的比例，别太像全屏覆盖（横屏时宽度已受限，可以更高）。 */
        const val PANEL_MAX_HEIGHT = 0.62f
    }

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}
