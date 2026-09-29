package com.xyf.mastergrid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
    private val refreshTask = Runnable {
        refreshPending = false
        refresh()
    }

    override fun onServiceConnected() {
        store = FilterStore(this)
        driver = CameraDriver(this, store)
        overlay = Overlay(this, store, this)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.packageName != CameraDriver.PKG
        ) return
        if (!refreshPending) {
            refreshPending = true
            handler.postDelayed(refreshTask, 150)
        }
    }

    private fun refresh() {
        if (job?.isActive == true) return
        val screen = driver.screen()
        if (screen == CameraDriver.Screen.OTHER) {
            // 相机切界面的动画中间会短暂读不到控件，连续一段时间都读不到才隐藏，免得按钮闪
            val now = SystemClock.uptimeMillis()
            if (otherSince == 0L) otherSince = now
            if (now - otherSince >= HIDE_DELAY_MS) {
                overlay.removeAll()
            } else if (!refreshPending) {
                refreshPending = true
                handler.postDelayed(refreshTask, HIDE_DELAY_MS)
            }
            return
        }
        otherSince = 0L
        overlay.showButton()
        if (screen == CameraDriver.Screen.MASTER_FILTER) overlay.currentName = driver.currentName()
    }

    override fun onPick(name: String) = runExclusive {
        val short = FilterStore.split(name).first
        overlay.showMessage("切换到「$short」…", sticky = true)
        val t0 = SystemClock.uptimeMillis()
        val err = driver.goTo(name)
        Log.i(TAG, "goTo($name) -> ${err ?: "ok"} in ${SystemClock.uptimeMillis() - t0}ms")
        if (err == null) {
            overlay.currentName = name
            if (store.returnAfterSelect) driver.leaveFilterScreen()
            overlay.showMessage("已切换：$short")
        } else {
            overlay.showMessage(err)
        }
    }

    override fun onScan() = runExclusive {
        overlay.showMessage("开始扫描，请别碰屏幕…", sticky = true)
        driver.scan { overlay.showMessage(it, sticky = true) }
            .onSuccess {
                overlay.currentName = driver.currentName()
                overlay.showMessage("扫描完成：找到 ${it.size} 个自己导入的滤镜")
            }
            .onFailure { overlay.showMessage(it.message ?: "扫描失败") }
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun runExclusive(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            overlay.setBusy(true)
            // 宫格面板刚关闭的一瞬间，相机会忽略紧接着的触摸（实测按下后指示条不展开），稍等一下再动手
            delay(PANEL_CLOSE_SETTLE_MS)
            try {
                block()
            } finally {
                overlay.setBusy(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "MasterGrid"
        const val HIDE_DELAY_MS = 500L
        const val PANEL_CLOSE_SETTLE_MS = 100L
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacks(refreshTask)
        if (::overlay.isInitialized) overlay.removeAll()
        super.onDestroy()
    }
}
