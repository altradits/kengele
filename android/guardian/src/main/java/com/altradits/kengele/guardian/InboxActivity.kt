package com.altradits.kengele.guardian

import android.os.Bundle
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.confirm.Action
import com.altradits.kengele.guardian.confirm.TallyException
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException

/**
 * Alarm inbox: events arrive as pasted 304-character text until the transport
 * lands. Each opened event feeds the tally; the screen shows the threshold
 * progress. No network code.
 */
class InboxActivity : AppCompatActivity() {
    private lateinit var progress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.inbox_title), 20f)
        Ui.addText(column, getString(R.string.transport_note), 14f)
        progress = Ui.addText(column, "")
        val input = Ui.addEdit(column, getString(R.string.inbox_hint), InputType.TYPE_CLASS_TEXT)
        input.isSingleLine = false
        input.minLines = 4
        val result = Ui.addText(column, "")
        result.setTextIsSelectable(true)
        Ui.addButton(column, getString(R.string.inbox_open)) {
            val t = GuardianState.tally()
            if (t == null) {
                Toast.makeText(this, R.string.need_enrollment, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val text = input.text.toString().trim().replace("\\s".toRegex(), "")
            try {
                val wire = KengeleMessage.decodeText(text)
                val event = KengeleMessage.open(wire, GuardianState.ownPriv(), t.senders(), GuardianState.nowSecs())
                val outcome = try {
                    t.handle(event, GuardianState.nowSecs())
                } catch (e: TallyException) {
                    result.text = getString(R.string.inbox_ignored, e.message ?: "")
                    refresh()
                    return@addButton
                }
                result.text = getString(R.string.inbox_result_count, describe(outcome.action))
                input.setText("")
            } catch (e: MessageException) {
                result.text = getString(R.string.inbox_bad_text)
            } catch (e: IllegalArgumentException) {
                result.text = getString(R.string.inbox_bad_text)
            }
            refresh()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        progress.text = statusLine()
    }

    private fun statusLine(): String {
        val t = GuardianState.tally() ?: return getString(R.string.need_enrollment)
        val now = GuardianState.nowSecs()
        val c = t.confirmStatus(now)
        val s = t.clearStatus(now)
        return getString(R.string.status_line, c.epoch, c.confirms, c.needed, s.guardianClears)
    }

    private fun describe(action: Action): String = when (action) {
        Action.NONE -> statusLine()
        Action.RELEASE -> getString(R.string.status_released, GuardianState.tally()?.confirmStatus(GuardianState.nowSecs())?.confirms ?: 0)
        Action.STAND_DOWN -> getString(R.string.clear_done, statusLine())
        Action.FOUND_SAFE -> getString(R.string.clear_done, statusLine())
    }
}
