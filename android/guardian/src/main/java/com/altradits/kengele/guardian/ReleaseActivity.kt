package com.altradits.kengele.guardian

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.confirm.Action
import com.altradits.kengele.guardian.confirm.Alert
import com.altradits.kengele.guardian.confirm.Hex
import com.altradits.kengele.guardian.confirm.KitOpen
import com.altradits.kengele.guardian.confirm.PublicNote
import com.altradits.kengele.guardian.nostr.NostrService
import com.altradits.kengele.message.nostr.NostrEvent

/**
 * Release screen: after k confirms it shows the sealed-then-opened alert
 * text, the alert public key for journalist verification, and a publish
 * action that broadcasts the signed public note through the configured
 * Nostr relays. Publishing always asks for explicit confirmation first.
 * After guardian all-clears post-release the same screen publishes the
 * found-safe update, which wipes the alert key.
 */
class ReleaseActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var explainer: TextView
    private lateinit var confirmedLine: TextView
    private lateinit var pubView: TextView
    private lateinit var noteView: TextView
    private lateinit var result: TextView
    private lateinit var noteIdView: TextView
    private lateinit var publish: Button

    /** Fixed when the screen opens, so retries send the same note. */
    private var createdSecs: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)
        createdSecs = GuardianState.nowSecs()

        val column = Ui.column(this)
        title = Ui.addText(column, "", 20f)
        explainer = Ui.addText(column, "", 14f)
        confirmedLine = Ui.addText(column, "")
        Ui.addText(column, getString(R.string.release_alert_pub_label), 14f)
        pubView = Ui.addText(column, "", 12f)
        pubView.setTextIsSelectable(true)
        Ui.addText(column, getString(R.string.release_note_label), 14f)
        noteView = Ui.addText(column, "", 14f)
        noteView.setTextIsSelectable(true)
        result = Ui.addText(column, "")
        noteIdView = Ui.addText(column, "", 12f)
        noteIdView.setTextIsSelectable(true)
        publish = Ui.addButton(column, getString(R.string.release_publish)) { askToPublish() }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val t = GuardianState.tally()
        val pending = t?.pendingRelease()
        if (t == null || pending == null) {
            title.text = getString(R.string.release_title)
            explainer.text = ""
            confirmedLine.text = ""
            pubView.text = ""
            noteView.text = ""
            noteIdView.text = ""
            result.text = if (t != null && t.released() && GuardianState.sealedKit() == null) {
                getString(R.string.release_needs_kit)
            } else {
                getString(R.string.release_nothing)
            }
            publish.visibility = View.GONE
            return
        }
        val isSafe = pending.action == Action.FOUND_SAFE
        title.text = getString(if (isSafe) R.string.release_found_safe_title else R.string.release_title)
        explainer.text = getString(if (isSafe) R.string.release_safe_explainer else R.string.release_explainer)
        confirmedLine.text = getString(R.string.release_confirmed_line, pending.confirmed, pending.epoch)
        GuardianState.alertPub()?.let { pubView.text = Hex.toHex(it) }
        noteView.text = unsignedNote(isSafe, pending.epoch, pending.confirmed, pending.alert).content
        KitOpen.wipe(pending.alertKey)
        publish.visibility = View.VISIBLE
    }

    private fun unsignedNote(isSafe: Boolean, epoch: Long, confirmed: Int, alert: Alert): NostrEvent =
        if (isSafe) {
            val alertId = GuardianState.publishedAlert()?.second ?: ""
            PublicNote.buildFoundSafe(alert, epoch, alertId, createdSecs)
        } else {
            PublicNote.buildAlert(alert, epoch, confirmed, null, "", createdSecs)
        }

    private fun askToPublish() {
        val t = GuardianState.tally()
        val pending = t?.pendingRelease()
        if (pending == null) {
            refresh()
            return
        }
        val isSafe = pending.action == Action.FOUND_SAFE
        KitOpen.wipe(pending.alertKey)
        AlertDialog.Builder(this)
            .setTitle(if (isSafe) R.string.release_confirm_safe_title else R.string.release_confirm_title)
            .setMessage(if (isSafe) R.string.release_confirm_safe_message else R.string.release_confirm_message)
            .setPositiveButton(R.string.release_publish) { _, _ -> doPublish(isSafe) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doPublish(isSafe: Boolean) {
        if (GuardianState.relays().isEmpty()) {
            result.text = getString(R.string.release_no_relays)
            return
        }
        publish.isEnabled = false
        result.text = ""
        noteIdView.text = ""
        val bg = Thread {
            var failed = false
            var outcome: PublishOutcome? = null
            try {
                val pending = GuardianState.tally()?.pendingRelease()
                if (pending == null) {
                    failed = true
                } else {
                    try {
                        val unsigned = unsignedNote(isSafe, pending.epoch, pending.confirmed, pending.alert)
                        val signed = unsigned.sign(pending.alertKey)
                        val res = NostrService.publishPublic(signed)
                        if (res != null) {
                            outcome = PublishOutcome(res.accepted, res.total, signed.id, pending.epoch)
                        } else {
                            failed = true
                        }
                    } finally {
                        KitOpen.wipe(pending.alertKey)
                    }
                }
            } catch (e: Exception) {
                failed = true
            }
            val done = outcome
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                publish.isEnabled = true
                when {
                    failed || done == null -> {
                        result.text = getString(R.string.unknown_error)
                    }
                    done.accepted >= 1 -> {
                        if (isSafe) {
                            GuardianState.markFoundSafePublished(done.epoch)
                            GuardianState.tally()?.clearPendingRelease()
                        } else {
                            GuardianState.markAlertPublished(done.epoch, done.noteId)
                        }
                        result.text = getString(R.string.release_published, done.accepted, done.total)
                        noteIdView.text = done.noteId
                        refresh()
                    }
                    else -> {
                        result.text = getString(R.string.release_failed)
                    }
                }
            }
        }
        bg.isDaemon = true
        bg.start()
    }

    private data class PublishOutcome(val accepted: Int, val total: Int, val noteId: String, val epoch: Long)
}
