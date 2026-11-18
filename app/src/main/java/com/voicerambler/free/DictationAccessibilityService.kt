package com.voicerambler.free

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DictationAccessibilityService? = null
            private set
    }

    private var lastFocusedEditable: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event?.source
        if (source != null && source.isEditable) {
            lastFocusedEditable = source
        } else {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                lastFocusedEditable = focused
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getActiveFieldText(): String? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedEditable
        return node?.text?.toString()
    }

    fun insertText(text: String, replaceAll: Boolean = false): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedEditable
            ?: return false

        val finalText = if (replaceAll || node.text.isNullOrEmpty()) {
            text
        } else {
            val current = node.text.toString()
            if (current.endsWith(" ") || current.endsWith("\n")) "$current$text" else "$current $text"
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk) return true

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VoiceRambler", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
