package com.xyf.mastergrid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MasterGridService : AccessibilityService(), Overlay.Listener {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: FilterStore
    private lateinit var driver: CameraDriver
    private lateinit var overlay: Overlay
    private var job: Job? = null

    // 相机界面的参数一直在刷新，事件很密，所以节流而不是防抖
    private var refreshPending = false
    private var otherSince = 0L
    private var unrecognizedSince = 0L
    private val refreshTask = Runnable {
        refreshPending = false
        refresh()
    }

    override fun onServiceConnected() {
        current = this
        store = FilterStore(this)
        driver = CameraDriver(this, store)
        overlay = Overlay(this, store, this)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        scheduleRefresh(150)
    }

    private fun scheduleRefresh(delayMs: Long) {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed(refreshTask, delayMs)
    }

    private fun refresh() {
        if (job?.isActive == true) return
        val screen = driver.screen()
        if (screen == CameraDriver.Screen.OTHER) {
            checkUnrecognized()
            if (!overlay.buttonShown) return
            // 相机切界面的动画中间会短暂读不到控件，连续一段时间都读不到才隐藏，免得按钮闪
            val now = SystemClock.uptimeMillis()
            if (otherSince == 0L) otherSince = now
            if (now - otherSince >= HIDE_DELAY_MS) {
                Log.d(TAG, "hide button")
                overlay.removeAll()
                otherSince = 0L
            } else {
                scheduleRefresh(HIDE_DELAY_MS)
            }
            return
        }
        otherSince = 0L
        unrecognizedSince = 0L
        if (!overlay.buttonShown) {
            Log.d(TAG, "show button")
            overlay.showButton()
            rememberCameraVersion()
        }
        if (screen == CameraDriver.Screen.MASTER_FILTER) overlay.currentName = driver.currentName()
        // 只订阅了相机的事件，离开相机时收不到别的应用的事件，所以按钮显示期间自己定时看一眼
        scheduleRefresh(POLL_MS)
    }

    /** 能认出大师模式，说明这个相机版本能用，记下来给设置页显示。 */
    private fun rememberCameraVersion() {
        val v = CameraDriver.cameraVersion(this) ?: return
        if (store.verifiedCameraVersion != v) store.verifiedCameraVersion = v
    }

    /**
     * 相机在前台，却一个认识的控件都找不到：多半是相机更新后界面变了。
     * 没实测过的版本才提示，每个版本只提示一次。
     */
    private fun checkUnrecognized() {
        if (driver.recognizesCamera() != false) {
            unrecognizedSince = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        if (unrecognizedSince == 0L) unrecognizedSince = now
        if (now - unrecognizedSince < UNRECOGNIZED_WARN_MS) {
            scheduleRefresh(UNRECOGNIZED_WARN_MS)
            return
        }
        val v = CameraDriver.cameraVersion(this) ?: return
        if (v in CameraDriver.TESTED_CAMERA_VERSIONS || v == store.verifiedCameraVersion || v == store.warnedCameraVersion) return
        store.warnedCameraVersion = v
        Log.w(TAG, "camera $v: no known view ids")
        overlay.showMessage("大师滤镜宫格：没认出相机界面（相机版本 $v 未适配）")
    }

    override fun onPick(name: String) = runExclusive {
        val short = FilterLists.split(name).first
        overlay.showMessage("切换到「$short」…  点此停止", sticky = true, onTap = ::cancelJob)
        val t0 = SystemClock.uptimeMillis()
        val r = driver.goTo(name)
        Log.i(TAG, "goTo($name) -> ${r.error ?: "ok"} in ${SystemClock.uptimeMillis() - t0}ms")
        val learned = buildList {
            if (r.added.isNotEmpty()) add("新滤镜已加入宫格：" + r.added.joinToString("、") { FilterLists.split(it).first })
            if (r.removed.isNotEmpty()) add("已移除：" + r.removed.joinToString("、") { FilterLists.split(it).first })
        }.joinToString("\n")
        if (r.error == null) {
            overlay.currentName = name
            if (store.returnAfterSelect) driver.leaveFilterScreen()
            overlay.showMessage(listOf("已切换：$short", learned).filter { it.isNotEmpty() }.joinToString("\n"))
        } else {
            overlay.showMessage(listOf(r.error, learned).filter { it.isNotEmpty() }.joinToString("\n"))
        }
    }

    override fun onScan() = runExclusive {
        overlay.showMessage("开始扫描，请别碰屏幕…", sticky = true, onTap = ::cancelJob)
        driver.scan { overlay.showMessage("$it  点此停止", sticky = true, onTap = ::cancelJob) }
            .onSuccess {
                overlay.currentName = driver.currentName()
                overlay.showMessage("扫描完成：找到 ${it.size} 个自己导入的滤镜")
            }
            .onFailure { overlay.showMessage(it.message ?: "扫描失败") }
    }

    private fun cancelJob() {
        job?.cancel()
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun runExclusive(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            overlay.setBusy(true)
            try {
                // 宫格面板刚关闭的一瞬间，相机会忽略紧接着的触摸（实测按下后指示条不展开），稍等一下再动手
                delay(PANEL_CLOSE_SETTLE_MS)
                block()
            } catch (e: CancellationException) {
                Log.i(TAG, "cancelled by user")
                overlay.showMessage("已停止")
                throw e
            } finally {
                overlay.setBusy(false)
                scheduleRefresh(POLL_MS)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    companion object {
        /** 仅供 debug 构建里的 DebugInputProvider 使用 */
        @Volatile
        var current: MasterGridService? = null
            private set

        private const val TAG = "MasterGrid"
        private const val HIDE_DELAY_MS = 500L
        private const val PANEL_CLOSE_SETTLE_MS = 100L
        private const val POLL_MS = 800L
        private const val UNRECOGNIZED_WARN_MS = 5000L
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        handler.removeCallbacks(refreshTask)
        if (::overlay.isInitialized) overlay.removeAll()
        super.onDestroy()
    }
}
