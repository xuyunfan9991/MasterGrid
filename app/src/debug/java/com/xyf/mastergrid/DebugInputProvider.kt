package com.xyf.mastergrid

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 仅 debug 构建：`adb shell input text` 不支持中文，用这个把文字填进当前输入框。
 *
 *     adb shell content call --uri content://com.xyf.mastergrid.debuginput --method settext --arg 中文
 *
 * 只接受 adb shell 的调用。
 */
class DebugInputProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("shell only")
        require(method == "settext" && arg != null) { "usage: --method settext --arg <text>" }
        val svc = MasterGridService.current ?: error("accessibility service not running")
        val node = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: svc.windows.firstNotNullOfOrNull { it.root?.let(::findEditable) }
            ?: error("no editable field")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg)
        }
        return Bundle().apply { putBoolean("ok", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let(::findEditable)?.let { return it }
        return null
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
