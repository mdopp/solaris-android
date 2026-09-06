package cloud.dopp.solaris.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cloud.dopp.solaris.R
import cloud.dopp.solaris.realtime.NoticeHistory

/**
 * What Solaris told this household (#158).
 *
 * Reached by tapping a notification — which until now landed on the empty chat,
 * because there was no destination at all — and from the home screen, so it is
 * reachable when no notification is on screen.
 *
 * The list is the answer to "what did that say again?": a notification is gone the
 * moment it is swiped away, and the text went with it. Bounded to a week and to
 * this phone (see [NoticeHistory]).
 */
class NoticesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notices)
        findViewById<Button>(R.id.notices_clear).setOnClickListener {
            NoticeHistory.clear(this)
            render()
        }
        render()
    }

    /** Re-read on every return; a notice may have landed while this was open. */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val entries = NoticeHistory.all(this)
        val view = findViewById<TextView>(R.id.notices_text)
        if (entries.isEmpty()) {
            view.setText(R.string.notices_empty)
            return
        }
        view.text = entries.joinToString("\n\n") { e ->
            val ago = DateUtils.getRelativeTimeSpanString(
                e.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            buildString {
                append(e.title.ifBlank { getString(R.string.notice_generic) })
                append("  ·  ").append(ago).append('\n')
                append(e.body)
            }
        }
    }
}
