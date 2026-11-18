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

        @Volatile
        var onEditableFocusChanged: ((Boolean) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val focused = getFreshEditableNode()
        if (focused != null) {
            onEditableFocusChanged?.invoke(true)
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onEditableFocusChanged?.invoke(false)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        onEditableFocusChanged?.invoke(false)
        super.onDestroy()
    }

    private fun getFreshEditableNode(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    /** Returns (beforeCursor, afterCursor) so dictation never overwrites surrounding text. */
    fun getCursorContext(): Pair<String, String>? {
        val node = getFreshEditableNode() ?: return null
        val full = node.text?.toString() ?: ""
        val start = node.textSelectionStart.coerceIn(0, full.length)
        val end = node.textSelectionEnd.coerceIn(start, full.length)
        return Pair(full.substring(0, start), full.substring(end))
    }

    fun getActiveFieldText(): String? = getFreshEditableNode()?.text?.toString()

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
