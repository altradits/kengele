package com.altradits.kengele.guardian

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.altradits.kengele.guardian.confirm.Action
import com.altradits.kengele.guardian.confirm.KitOpen
import com.altradits.kengele.guardian.confirm.Outcome
import com.altradits.kengele.guardian.confirm.TallyException
import com.altradits.kengele.guardian.mesh.MeshIntake
import com.altradits.kengele.guardian.nostr.NostrIntake
import com.altradits.kengele.guardian.nostr.NostrService
import com.altradits.kengele.mesh.MeshBoard
import com.altradits.kengele.mesh.MeshPermissions
import com.altradits.kengele.mesh.MeshRadio
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException

/**
 * Alarm inbox: events arrive live over Nostr when relays are configured,
 * over the mesh from nearby phones, or as pasted 304-character text as a
 * fallback. Each opened event feeds the tally; the screen shows the
 * threshold progress.
 */
class InboxActivity : AppCompatActivity() {
    private lateinit var progress: TextView
    private lateinit var live: TextView
    private lateinit var meshStatus: TextView
    private lateinit var result: TextView
    private var meshRadio: MeshRadio? = null
    private var meshUnsupported = false
    private var meshPermRequested = false
    private val meshRefreshHandler = Handler(Looper.getMainLooper())
    private val meshRefresh = object : Runnable {
        override fun run() {
            refreshMesh()
            meshRefreshHandler.postDelayed(this, 2000)
        }
    }

    private val meshPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            startMesh()
        } else {
            Toast.makeText(this, R.string.mesh_perm_denied, Toast.LENGTH_LONG).show()
            refreshMesh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.inbox_title), 20f)
        Ui.addText(column, getString(R.string.transport_note), 14f)
        live = Ui.addText(column, "", 14f)
        meshStatus = Ui.addText(column, "", 14f)
        Ui.addButton(column, getString(R.string.menu_settings)) {
            startActivity(Intent(this, RelaySettingsActivity::class.java))
        }
        progress = Ui.addText(column, "")
        val input = Ui.addEdit(column, getString(R.string.inbox_hint), InputType.TYPE_CLASS_TEXT)
        input.isSingleLine = false
        input.minLines = 4
        result = Ui.addText(column, "")
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
                GuardianState.saveTally()
                result.text = getString(R.string.inbox_result_count, describe(outcome.action))
                input.setText("")
                maybePublish(outcome)
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

    override fun onStart() {
        super.onStart()
        NostrService.statusListener = { runOnUiThread { refreshLive() } }
        NostrService.startReceiver { delivery ->
            val t = NostrService.tally() ?: return@startReceiver
            val now = GuardianState.nowSecs()
            when (val r = NostrIntake.intake(delivery, t, now)) {
                is NostrIntake.Result.Counted -> runOnUiThread {
                    GuardianState.saveTally()
                    result.text = getString(
                        R.string.nostr_from_net,
                        getString(R.string.inbox_result_count, describe(r.outcome.action)),
                    )
                    refresh()
                    maybePublish(r.outcome)
                }
                is NostrIntake.Result.Ignored -> runOnUiThread {
                    result.text = getString(
                        R.string.nostr_from_net,
                        getString(R.string.inbox_ignored, r.message ?: ""),
                    )
                    refresh()
                }
            }
        }
        refreshLive()
        maybeStartMesh()
    }

    override fun onStop() {
        stopMesh()
        NostrService.stopReceiver()
        NostrService.statusListener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshLive()
        refreshMesh()
        meshRefreshHandler.post(meshRefresh)
    }

    override fun onPause() {
        meshRefreshHandler.removeCallbacks(meshRefresh)
        super.onPause()
    }

    private fun refresh() {
        progress.text = statusLine()
    }

    private fun refreshLive() {
        val relays = GuardianState.relays().size
        if (GuardianState.tally() == null || relays == 0) {
            live.text = getString(R.string.nostr_none)
            return
        }
        val s = NostrService.status
        live.text = getString(R.string.nostr_listening, s?.reachable ?: 0, relays)
    }

    // -- Mesh intake: completed datagrams open against the enrolled kit and
    // feed the live tally with the same discipline as the Nostr path. --

    private fun maybeStartMesh() {
        if (GuardianState.tally() == null) {
            // No kit, no senders to open with: the mesh stays off.
            refreshMesh()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            meshUnsupported = true
            refreshMesh()
            return
        }
        val missing = MeshPermissions.missing(this)
        if (missing.isEmpty()) {
            startMesh()
            return
        }
        // Ask once per screen: after a deny the mesh stays off until the
        // user grants in Settings and returns (a grant starts it above).
        if (meshPermRequested) {
            refreshMesh()
            return
        }
        meshPermRequested = true
        if (missing.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mesh_perm_title)
                .setMessage(R.string.mesh_perm_rationale)
                .setPositiveButton(R.string.mesh_perm_allow) { _, _ ->
                    meshPermissionLauncher.launch(missing.toTypedArray())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            meshPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMesh() {
        if (meshRadio != null) {
            refreshMesh()
            return
        }
        val radio = MeshRadio(this, onDatagram = ::onMeshDatagram)
        if (!radio.start()) {
            // Bluetooth off or radio refused: stays off; Nostr and paste
            // still work.
            refreshMesh()
            return
        }
        meshRadio = radio
        refreshMesh()
    }

    private fun stopMesh() {
        meshRadio?.stop()
        meshRadio = null
    }

    private fun onMeshDatagram(datagram: ByteArray) {
        val t = try {
            GuardianState.tally()
        } catch (e: Exception) {
            null
        } ?: return
        val own = try {
            GuardianState.ownPriv()
        } catch (e: Exception) {
            return
        }
        when (val r = MeshIntake.intake(datagram, own, t.senders(), t, GuardianState.nowSecs())) {
            is MeshIntake.Result.Counted -> runOnUiThread {
                GuardianState.saveTally()
                result.text = getString(
                    R.string.mesh_from_mesh,
                    getString(R.string.inbox_result_count, describe(r.outcome.action)),
                )
                refresh()
                refreshMesh()
                maybePublish(r.outcome)
            }
            is MeshIntake.Result.Ignored -> {
                // Crowd traffic for someone else is dropped silently, like
                // the Nostr transport drops non-sender wraps; only this
                // phone's ignored events are shown.
                if (r.reason == null) {
                    runOnUiThread { refreshMesh() }
                    return
                }
                runOnUiThread {
                    result.text = getString(
                        R.string.mesh_from_mesh,
                        getString(R.string.inbox_ignored, r.message ?: ""),
                    )
                    refresh()
                    refreshMesh()
                }
            }
        }
    }

    private fun refreshMesh() {
        if (meshUnsupported) {
            meshStatus.text = getString(R.string.mesh_unavailable)
            return
        }
        if (meshRadio == null) {
            meshStatus.text = getString(R.string.mesh_off)
            return
        }
        val snap = MeshBoard.snapshot()
        meshStatus.text = getString(R.string.mesh_links, snap.links) +
            "\n" + getString(R.string.mesh_relayed, snap.datagramsRelayed)
    }

    private fun statusLine(): String {
        val t = GuardianState.tally() ?: return getString(R.string.need_enrollment)
        val now = GuardianState.nowSecs()
        val c = t.confirmStatus(now)
        val s = t.clearStatus(now)
        return getString(R.string.status_line, c.epoch, c.confirms, c.needed, s.guardianClears)
    }

    /**
     * Opens the release screen when an event released the alert or ended the
     * alarm after a release. The screen reads its own copy from the tally;
     * this outcome's key copies are wiped.
     */
    private fun maybePublish(outcome: Outcome) {
        if (outcome.action == Action.RELEASE || outcome.action == Action.FOUND_SAFE) {
            outcome.alertKey?.let { KitOpen.wipe(it) }
            outcome.key?.let { KitOpen.wipe(it) }
            startActivity(Intent(this, ReleaseActivity::class.java))
        }
    }

    private fun describe(action: Action): String = when (action) {
        Action.NONE -> statusLine()
        Action.RELEASE -> getString(R.string.status_released, GuardianState.tally()?.confirmStatus(GuardianState.nowSecs())?.confirms ?: 0)
        Action.STAND_DOWN -> getString(R.string.clear_done, statusLine())
        Action.FOUND_SAFE -> getString(R.string.clear_done, statusLine())
    }
}
