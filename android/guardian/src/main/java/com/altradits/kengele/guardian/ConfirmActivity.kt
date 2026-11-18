package com.altradits.kengele.guardian

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.confirm.ConfirmBodies
import com.altradits.kengele.guardian.confirm.TallyException
import com.altradits.kengele.guardian.nostr.NostrService
import com.altradits.kengele.message.KengeleMessage

/**
 * Confirm action: builds a sealed guardian-confirm event carrying this
 * guardian's share, shown as 304-character text per recipient guardian.
 * The guardian's own confirmation feeds the local tally too.
 */
class ConfirmActivity : AppCompatActivity() {
    private lateinit var progress: TextView
    private lateinit var texts: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.confirm_title), 20f)
        progress = Ui.addText(column, "")
        val explainer = Ui.addText(column, "", 14f)
        texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        column.addView(texts)
        val nostrLine = Ui.addText(column, "", 14f)
        val result = Ui.addText(column, "")
        Ui.addButton(column, getString(R.string.confirm_build)) {
            val t = GuardianState.tally()
            if (t == null) {
                Toast.makeText(this, R.string.need_enrollment, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val share = GuardianState.share()
            if (share == null) {
                Toast.makeText(this, R.string.confirm_needs_share, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val now = GuardianState.nowSecs()
            val event = ConfirmBodies.confirmEvent(
                GuardianState.activist()!!,
                t.epoch(),
                share,
                GuardianState.nextSeq(),
                now,
            )
            // Same signature for every guardian; only the encryption differs.
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
                // Count the guardian's own confirmation locally.
                t.handle(event.copy(signer = GuardianState.ownPub()), now)
            } catch (e: TallyException) {
                result.text = getString(R.string.inbox_ignored, e.message ?: "")
                refresh(explainer)
                return@addButton
            }
            val c = t.confirmStatus(now)
            val s = t.clearStatus(now)
            result.text = getString(
                R.string.confirm_done,
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
        val c = t.confirmStatus(GuardianState.nowSecs())
        progress.text = getString(R.string.status_line, c.epoch, c.confirms, c.needed, t.clearStatus(GuardianState.nowSecs()).guardianClears)
        explainer.text = getString(R.string.confirm_explainer, c.confirms, c.needed)
    }
}
