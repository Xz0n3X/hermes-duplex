package org.hermes.duplex

import android.text.Layout
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * Tappable links in text that can still be selected.
 *
 * `LinkMovementMethod` is the usual answer and it costs selection: it
 * replaces the movement method that handles dragging a cursor, so a reply
 * becomes clickable and uncopyable at the same time. Both matter here — a
 * cited URL should open, and a wrong answer should be quotable.
 *
 * So this extends the arrow-key method, which is what a selectable TextView
 * normally uses, and only intercepts a tap that actually lands on a link.
 * Everything else falls through to selection behaving as it did.
 */
object LinkMovement : ArrowKeyMovementMethod() {

    fun install(view: TextView) {
        view.movementMethod = this
    }

    override fun onTouchEvent(
        widget: TextView,
        buffer: Spannable,
        event: MotionEvent,
    ): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            spanAt(widget, buffer, event)?.let { span ->
                span.onClick(widget)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    /** The link under the finger, if the finger is on one. */
    private fun spanAt(
        widget: TextView,
        buffer: Spannable,
        event: MotionEvent,
    ): ClickableSpan? {
        val layout: Layout = widget.layout ?: return null
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y.toInt())

        // getOffsetForHorizontal snaps to the nearest character even when the
        // tap is past the end of the line, which would make the whitespace
        // beyond a trailing link part of the link.
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null

        val offset = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}
