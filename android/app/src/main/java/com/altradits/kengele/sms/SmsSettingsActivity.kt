package com.altradits.kengele.sms

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
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
import com.altradits.kengele.guardian.InMemoryGuardianStore

/**
 * Per-guardian SMS fallback opt-in (docs/sms-transport.md, section 1).
 *
 * SMS stays off by default: the section 5 warning sits at the top of this
 * screen, above any control that can enable it, and enabling a guardian
 * asks for confirmation with the same warning again. Numbers and profiles
 * live in the encrypted [SmsStore]; removing a guardian also removes its
 * SMS entry.
 */
class SmsSettingsActivity : AppCompatActivity() {
    private lateinit var permLine: TextView
    private lateinit var list: LinearLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            Toast.makeText(this, R.string.sms_perm_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.sms_perm_denied, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.sms_title), 20f)
        Ui.addText(column, getString(R.string.sms_warning), 14f)
        permLine = Ui.addText(column, "")
        Ui.addButton(column, getString(R.string.sms_perm_button)) { requestPermissions() }
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        column.addView(list)
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        permLine.text = if (SmsPermissions.hasAll(this)) {
            getString(R.string.sms_perm_granted)
        } else {
            getString(R.string.sms_perm_missing)
        }
        val keys = AppState.guardians.guardians().map { InMemoryGuardianStore.bytesToHex(it) }
        list.removeAllViews()
        if (keys.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.sms_no_guardians)
            empty.textSize = 14f
            list.addView(empty)
            return
        }
        for ((index, key) in keys.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            val pad = (8 * resources.displayMetrics.density).toInt()
            row.setPadding(0, pad, 0, pad)
            val entry = AppState.sms.get(key)
            val label = TextView(this)
            val profileText = profileLabel(entry?.profile ?: SmsProfile.OFF)
            label.text = if (entry == null) {
                getString(R.string.sms_guardian_n, index + 1) +
                    " · " + shortKey(key) + "\n" + profileText
            } else {
                getString(R.string.sms_guardian_n, index + 1) +
                    " · " + shortKey(key) + "\n" + profileText + " · " + entry.number
            }
            label.textSize = 14f
            row.addView(label)
            val edit = android.widget.Button(this)
            edit.text = getString(R.string.sms_edit_title)
            edit.setOnClickListener { editGuardian(key, entry) }
            row.addView(edit)
            list.addView(row)
        }
    }

    private fun editGuardian(key: String, current: SmsEntry?) {
        val profiles = listOf(SmsProfile.OFF, SmsProfile.ALARM_ONLY, SmsProfile.SHUTDOWN_CHECKINS)
        val labels = profiles.map { profileLabel(it) }.toTypedArray()
        val checked = intArrayOf(maxOf(profiles.indexOf(current?.profile ?: SmsProfile.OFF), 0))
        val column = Ui.column(this)
        val number = Ui.addEdit(
            column,
            getString(R.string.sms_number_hint),
            InputType.TYPE_CLASS_PHONE,
        )
        number.setText(current?.number ?: "")
        AlertDialog.Builder(this)
            .setTitle(R.string.sms_edit_title)
            .setView(column)
            .setSingleChoiceItems(labels, checked[0]) { _, which -> checked[0] = which }
            .setPositiveButton(R.string.save) { _, _ ->
                saveGuardian(key, current, profiles[checked[0]], number.text.toString())
            }
            .setNegativeButton(R.string.sms_cancel, null)
            .show()
    }

    private fun saveGuardian(key: String, current: SmsEntry?, profile: SmsProfile, number: String) {
        if (profile == SmsProfile.OFF) {
            AppState.sms.set(key, "", SmsProfile.OFF)
            Toast.makeText(this, R.string.sms_saved, Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        val norm = try {
            SmsNumbers.checkNumber(number)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, R.string.sms_invalid_number, Toast.LENGTH_LONG).show()
            return
        }
        if (current == null) {
            // First enable for this guardian: the section 5 warning again,
            // with an explicit turn-on.
            AlertDialog.Builder(this)
                .setTitle(R.string.sms_enable_title)
                .setMessage(R.string.sms_warning)
                .setPositiveButton(R.string.sms_enable_allow) { _, _ ->
                    AppState.sms.set(key, norm, profile)
                    Toast.makeText(this, R.string.sms_saved, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.sms_keep_off, null)
                .show()
        } else {
            AppState.sms.set(key, norm, profile)
            Toast.makeText(this, R.string.sms_saved, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun requestPermissions() {
        val missing = SmsPermissions.missing(this)
        val rationale = missing.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        when (SmsPermissions.state(missing, rationale)) {
            SmsPermissionState.GRANTED -> {
                Toast.makeText(this, R.string.sms_perm_granted, Toast.LENGTH_SHORT).show()
            }
            SmsPermissionState.NEEDS_RATIONALE -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.sms_perm_title)
                    .setMessage(R.string.sms_perm_rationale)
                    .setPositiveButton(R.string.sms_perm_allow) { _, _ ->
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                    .setNegativeButton(R.string.sms_cancel, null)
                    .show()
            }
            SmsPermissionState.NEEDS_REQUEST -> {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun profileLabel(profile: SmsProfile): String = when (profile) {
        SmsProfile.OFF -> getString(R.string.sms_profile_off)
        SmsProfile.ALARM_ONLY -> getString(R.string.sms_profile_alarm_only)
        SmsProfile.SHUTDOWN_CHECKINS -> getString(R.string.sms_profile_shutdown_checkins)
    }

    private fun shortKey(key: String): String = key.take(12) + "…" + key.takeLast(8)
}
