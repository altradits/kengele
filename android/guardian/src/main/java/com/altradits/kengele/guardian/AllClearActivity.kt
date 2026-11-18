package com.altradits.kengele.guardian

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.confirm.AllClearChecklist
import com.altradits.kengele.guardian.confirm.ConfirmBodies
import com.altradits.kengele.guardian.confirm.ConfirmException
import com.altradits.kengele.guardian.confirm.TallyException
import com.altradits.kengele.guardian.nostr.NostrService
import com.altradits.kengele.message.KengeleMessage

/**
 * All-clear action, gated behind the verify-by-voice/video checklist: every
 * box must be ticked and a call method picked before the sealed all-clear
 * event builds. The guardian's own all-clear feeds the local tally too.
 */
class AllClearActivity : AppCompatActivity() {
    private lateinit var progress: TextView
    private lateinit var texts: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.clear_title), 20f)
        progress = Ui.addText(column, "")
        val explainer = Ui.addText(column, "", 14f)
        val called = Ui.addCheckBox(column, getString(R.string.check_called))
        val free = Ui.addCheckBox(column, getString(R.string.check_free))
        val noCoercion = Ui.addCheckBox(column, getString(R.string.check_no_coercion))
        val methods = Ui.addRadioGroup(
            column,
            listOf(getString(R.string.method_voice), getString(R.string.method_video)),
        )
        texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        column.addView(texts)
        val nostrLine = Ui.addText(column, "", 14f)
        val result = Ui.addText(column, "")
        Ui.addButton(column, getString(R.string.clear_build)) {
            val t = GuardianState.tally()
            if (t == null) {
                Toast.makeText(this, R.string.need_enrollment, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val checklist = AllClearChecklist().apply {
                spokeToActivist = called.isChecked
                activistIsFree = free.isChecked
                noSignsOfCoercion = noCoercion.isChecked
            }
            val checked = methods.findViewById<RadioButton>(methods.checkedRadioButtonId)
            val method = when (methods.indexOfChild(checked)) {
                0 -> ConfirmBodies.METHOD_VOICE
                1 -> ConfirmBodies.METHOD_VIDEO
                else -> ""
            }
            val now = GuardianState.nowSecs()
            val event = try {
                ConfirmBodies.guardianAllClearEvent(
                    GuardianState.activist()!!,
                    t.epoch(),
                    GuardianState.nextSeq(),
                    now,
                    checklist.verification(method, now),
                )
            } catch (e: ConfirmException) {
                Toast.makeText(this, R.string.clear_needs_checklist, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val inner = KengeleMessage.sign(event, GuardianState.ownPriv())
            val priv = GuardianState.ownPriv()
            texts.removeAllViews()
            Ui.addText(texts, getString(R.string.sealed_texts_title), 14f)
            for (r in t.recipients()) {
                val text = KengeleMessage.encodeText(KengeleMessage.sealInner(inner, priv, r))
                val view = Ui.addText(texts, text, 12f)
                view.setTextIsSelectable(true)
            }
            try {
                t.handle(event.copy(signer = GuardianState.ownPub()), now)
            } catch (e: TallyException) {
                result.text = getString(R.string.inbox_ignored, e.message ?: "")
                refresh(explainer)
                return@addButton
            }
            val c = t.confirmStatus(now)
            val s = t.clearStatus(now)
            result.text = getString(
                R.string.clear_done,
                getString(R.string.status_line, c.epoch, c.confirms, c.needed, s.guardianClears),
            )
            sendOverNostr(nostrLine, inner, priv, t.recipients())
            refresh(explainer)
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh(explainer)
    }

    /**
     * Sends the sealed event to every recipient guardian over Nostr; the
     * shown texts stay as the fallback when no relay is reachable.
     */
    private fun sendOverNostr(line: TextView, inner: ByteArray, priv: ByteArray, recipients: List<ByteArray>) {
        val bg = Thread {
            val wires = recipients.map { KengeleMessage.sealInner(inner, priv, it) }
            var failed = false
            val summary = try {
                NostrService.sendNow(wires, recipients)
            } catch (e: Exception) {
                failed = true
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                line.text = when {
                    failed -> getString(R.string.unknown_error)
                    summary == null -> getString(R.string.nostr_send_none)
                    summary.queued == 0 -> getString(R.string.nostr_sent_all, summary.total)
                    else -> getString(R.string.nostr_sent_partial, summary.reached, summary.total)
                }
            }
        }
        bg.isDaemon = true
        bg.start()
    }

    private fun refresh(explainer: TextView) {
        val t = GuardianState.tally()
        if (t == null) {
            progress.text = getString(R.string.need_enrollment)
            explainer.text = ""
            return
        }
        val now = GuardianState.nowSecs()
        val c = t.confirmStatus(now)
        val s = t.clearStatus(now)
        progress.text = getString(R.string.status_line, c.epoch, c.confirms, c.needed, s.guardianClears)
        explainer.text = getString(R.string.clear_explainer, s.needed)
    }
}
