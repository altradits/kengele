package com.altradits.kengele.mesh

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.altradits.kengele.AppState
import com.altradits.kengele.R
import com.altradits.kengele.Ui

/**
 * Minimal mesh status: radio state (advertising/scanning), live links and
 * datagrams relayed, plus start/stop and the Bluetooth runtime-permission
 * flow with rationale. Reached from the main screen.
 */
class MeshStatusActivity : AppCompatActivity() {
    private lateinit var stateLine: TextView
    private lateinit var radioLine: TextView
    private lateinit var linksLine: TextView
    private lateinit var relayedLine: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            refreshHandler.postDelayed(this, 2000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            startMesh()
        } else {
            Toast.makeText(this, R.string.mesh_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.mesh_title), 20f)
        stateLine = Ui.addText(column, "")
        radioLine = Ui.addText(column, "")
        linksLine = Ui.addText(column, "")
        relayedLine = Ui.addText(column, "")
        Ui.addButton(column, getString(R.string.mesh_start)) { requestStart() }
        Ui.addButton(column, getString(R.string.mesh_stop)) {
            MeshService.stop(this)
            render()
            Toast.makeText(this, R.string.mesh_stopped, Toast.LENGTH_SHORT).show()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshHandler.post(refresh)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val snap = MeshBoard.snapshot()
        stateLine.text = if (snap.links > 0) {
            getString(R.string.mesh_relaying)
        } else {
            getString(R.string.mesh_no_path)
        }
        val yes = getString(R.string.yes)
        val no = getString(R.string.no)
        radioLine.text = getString(R.string.mesh_advertising, if (snap.advertising) yes else no) +
            "\n" + getString(R.string.mesh_scanning, if (snap.scanning) yes else no)
        linksLine.text = getString(R.string.mesh_links, snap.links)
        relayedLine.text = getString(R.string.mesh_relayed, snap.datagramsRelayed)
    }

    private fun requestStart() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.mesh_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (!MeshService.windowActive(this)) {
            Toast.makeText(this, R.string.mesh_window_inactive, Toast.LENGTH_LONG).show()
            return
        }
        val missing = MeshPermissions.missing(this)
        if (missing.isEmpty()) {
            startMesh()
            return
        }
        if (missing.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mesh_perm_title)
                .setMessage(R.string.mesh_perm_rationale)
                .setPositiveButton(R.string.mesh_perm_allow) { _, _ ->
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .setNegativeButton(R.string.no, null)
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMesh() {
        MeshService.startIfWindowActive(this)
        render()
        Toast.makeText(this, R.string.mesh_running, Toast.LENGTH_SHORT).show()
    }
}
