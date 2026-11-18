package com.altradits.kengele.guardian

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.confirm.Enrollment
import com.altradits.kengele.guardian.confirm.Hex

/**
 * Kit enrollment: the activist's public key, the guardian public keys, the
 * threshold k and this guardian's share. Keys only, never names or numbers.
 * This phone must be one of the enrolled guardians.
 */
class EnrollActivity : AppCompatActivity() {
    private val keys = LinkedHashSet<String>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)
        keys.addAll(GuardianState.guardians().map { Hex.toHex(it) })

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.enroll_title), 20f)
        Ui.addText(column, getString(R.string.no_names_note), 14f)
        Ui.addText(column, getString(R.string.this_device_key), 14f)
        val own = Ui.addText(column, Hex.toHex(GuardianState.ownPub()), 12f)
        own.setTextIsSelectable(true)

        val activistSaved = GuardianState.activist()?.let { Hex.toHex(it) } ?: ""
        val activistInput = Ui.addEdit(column, getString(R.string.activist_key_hint), InputType.TYPE_CLASS_TEXT)
        activistInput.setText(activistSaved)

        count = Ui.addText(column, "")
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        column.addView(list)
        val input = Ui.addEdit(column, getString(R.string.guardian_key_hint), InputType.TYPE_CLASS_TEXT)
        Ui.addButton(column, getString(R.string.add)) {
            try {
                val norm = Enrollment.checkKey(input.text.toString().trim())
                if (!keys.add(norm)) throw IllegalArgumentException("already added")
                input.setText("")
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.invalid_key, Toast.LENGTH_LONG).show()
            }
            refresh()
        }

        val thresholdInput = Ui.addEdit(column, getString(R.string.threshold_hint), InputType.TYPE_CLASS_NUMBER)
        val savedK = GuardianState.threshold()
        if (savedK > 0) thresholdInput.setText(savedK.toString())

        val shareInput = Ui.addEdit(column, getString(R.string.share_hint), InputType.TYPE_CLASS_TEXT)
        GuardianState.share()?.let { shareInput.setText(Hex.toHex(it)) }

        Ui.addButton(column, getString(R.string.save)) {
            try {
                val activistHex = Enrollment.checkKey(activistInput.text.toString().trim())
                val k = thresholdInput.text.toString().trim().toIntOrNull()
                    ?: throw IllegalArgumentException("bad threshold")
                val shareText = shareInput.text.toString().trim()
                val shareHex = if (shareText.isEmpty()) {
                    null
                } else {
                    try {
                        Enrollment.checkShare(shareText)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(this, R.string.invalid_share, Toast.LENGTH_LONG).show()
                        return@addButton
                    }
                    shareText.lowercase()
                }
                val activist = Hex.fromHex(activistHex)
                val guardians = keys.map { Hex.fromHex(it) }
                Enrollment.checkEnrollment(activist, guardians, k)
                if (!keys.contains(Hex.toHex(GuardianState.ownPub()))) {
                    throw IllegalArgumentException("this phone is not enrolled")
                }
                GuardianState.enroll(activistHex, keys.toList(), k, shareHex)
                Toast.makeText(this, R.string.enrolled, Toast.LENGTH_LONG).show()
                finish()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.invalid_enrollment, Toast.LENGTH_LONG).show()
            }
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        count.text = getString(R.string.guardians_count, keys.size)
        list.removeAllViews()
        for (key in keys) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val label = TextView(this)
            label.text = Hex.short(key)
            label.textSize = 14f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label)
            val remove = android.widget.Button(this)
            remove.text = getString(R.string.remove)
            remove.setOnClickListener {
                keys.remove(key)
                refresh()
            }
            row.addView(remove)
            list.addView(row)
        }
    }
}
