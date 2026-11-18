package com.altradits.kengele.sms

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Where the SMS permission request stands. Pure, so unit tests cover the
 * flow states while the activity supplies the Android answers.
 */
enum class SmsPermissionState {
    /** Nothing missing: SMS can send and receive. */
    GRANTED,

    /** Permissions missing and the user has not denied before: ask directly. */
    NEEDS_REQUEST,

    /** A previous denial means the rationale comes first. */
    NEEDS_RATIONALE,
}

/**
 * SMS runtime permissions (docs/sms-transport.md, section 8): SEND_SMS to
 * text guardians, RECEIVE_SMS to read incoming sealed replies such as an
 * all-clear. The Play build cannot hold RECEIVE_SMS at all, so full SMS
 * fallback ships through F-Droid and a signed APK.
 */
object SmsPermissions {
    // Literal permission strings (not Manifest.permission constants) so the
    // mapping stays Android-free and unit-testable on the JVM.
    const val SEND = "android.permission.SEND_SMS"
    const val RECEIVE = "android.permission.RECEIVE_SMS"

    /** Runtime permissions SMS fallback needs. */
    fun requiredPermissions(): List<String> = listOf(SEND, RECEIVE)

    /** Which of [requiredPermissions] are not granted in [grants]. Pure. */
    fun missing(grants: Map<String, Boolean>): List<String> =
        requiredPermissions().filter { grants[it] != true }

    /** Which of [requiredPermissions] the user has not granted yet. */
    fun missing(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Whether both SMS permissions are already granted. */
    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    /**
     * The flow state for [missing] permissions. [shouldShowRationale] is
     * true when the OS says a previous denial wants an explanation first
     * (ActivityCompat.shouldShowRequestPermissionRationale on any of them).
     */
    fun state(missing: List<String>, shouldShowRationale: Boolean): SmsPermissionState = when {
        missing.isEmpty() -> SmsPermissionState.GRANTED
        shouldShowRationale -> SmsPermissionState.NEEDS_RATIONALE
        else -> SmsPermissionState.NEEDS_REQUEST
    }
}
