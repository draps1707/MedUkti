package com.voicerambler.free

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DictationAccessibilityService? = null
            private set

        @Volatile
        var onEditableFocusChanged: ((Boolean) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (getFreshEditableNode() == null) {
            onEditableFocusChanged?.invoke(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val focused = getFreshEditableNode()
        if (focused != null) {
            handler.removeCallbacks(hideRunnable)
            onEditableFocusChanged?.invoke(true)
        } else {
            val t = event?.eventType
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                t == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                t == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                t == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, 150L)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(hideRunnable)
        instance = null
        onEditableFocusChanged?.invoke(false)
        super.onDestroy()
    }

    private fun getFreshEditableNode(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    private fun getRealText(node: AccessibilityNodeInfo): String {
        val isHint = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) ||
            (node.hintText != null && node.text?.toString() == node.hintText?.toString())
        return if (isHint) "" else (node.text?.toString() ?: "")
    }

    /** Returns (beforeCursor, afterCursor). Defaults to end of existing text if selection is -1. */
    fun getCursorContext(): Pair<String, String>? {
        val node = getFreshEditableNode() ?: return null
        val full = getRealText(node)
        if (full.isEmpty()) return Pair("", "")
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart < 0) full.length else rawStart.coerceIn(0, full.length)
        val end = if (rawEnd < 0) start else rawEnd.coerceIn(start, full.length)
        return Pair(full.substring(0, start), full.substring(end))
    }

    fun getActiveFieldText(): String? {
        val node = getFreshEditableNode() ?: return null
        return getRealText(node)
    }

    fun setFullTextWithCursor(fullText: String, cursorPos: Int): Boolean {
        val node = getFreshEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fullText)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk) {
            val safePos = cursorPos.coerceIn(0, fullText.length)
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, safePos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, safePos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            return true
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VoiceRambler", fullText))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
