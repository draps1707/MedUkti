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

        private val KNOWN_PLACEHOLDERS = setOf(
            "message", "type a message", "ask google", "search", "search...", "write a message"
        )

        private val BANKING_PKG_PREFIXES = listOf(
            "com.sbi.", "com.csam.icici", "com.snapwork.hdfc", "com.axis.mobile",
            "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm",
            "com.dreamplug.androidapp", "com.infrasofttech", "com.fss.", "com.bob.",
            "com.kotak", "in.org.npci", "com.unionbank", "com.canarabank",
            "com.iob", "com.bankofbaroda"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventCheckMs = 0L
    private val hideRunnable = Runnable {
        if (getFreshEditableNode() == null) {
            onEditableFocusChanged?.invoke(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun pauseForBanking() {
        handler.removeCallbacks(hideRunnable)
        onEditableFocusChanged?.invoke(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            disableSelf()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && now - lastEventCheckMs < 120L) {
            return
        }
        lastEventCheckMs = now
        val pkg = event?.packageName?.toString()?.lowercase() ?: ""
        if (pkg.isNotEmpty() && BANKING_PKG_PREFIXES.any { pkg.startsWith(it) }) {
            pauseForBanking()
            return
        }
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
        val raw = node.text?.toString() ?: ""
        val isHint = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) ||
            (node.hintText != null && raw == node.hintText?.toString()) ||
            (raw.trim().lowercase() in KNOWN_PLACEHOLDERS && node.textSelectionStart <= 0)
        return if (isHint) "" else raw
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
        clipboard.setPrimaryClip(ClipData.newPlainText("KathaType", fullText))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
