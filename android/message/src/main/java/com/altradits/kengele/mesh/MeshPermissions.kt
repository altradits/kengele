package com.altradits.kengele.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Bluetooth runtime permissions for the mesh carry layer (API 31+):
 * BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT. On older
 * releases the OS forces a location permission for BLE scanning, so the
 * manifest declares ACCESS_FINE_LOCATION with maxSdkVersion 30 and this
 * object requests it only there. Kengele never touches GPS: no location
 * API is called anywhere in the mesh package.
 */
object MeshPermissions {
    // Literal permission strings (not Manifest.permission constants) so the
    // mapping stays Android-free and unit-testable on the JVM.
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val LOCATION_BELOW_31 = "android.permission.ACCESS_FINE_LOCATION"

    /** Runtime permissions the OS demands on [sdkInt]. */
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(SCAN, ADVERTISE, CONNECT)
        } else {
            // BLUETOOTH / BLUETOOTH_ADMIN are manifest-only below 31; only
            // the location permission needs a runtime grant.
            listOf(LOCATION_BELOW_31)
        }

    /** Runtime permissions from [requiredPermissions] the user has not granted yet. */
    fun missing(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        // Framework call, not ContextCompat: minSdk 26 is past the compat
        // shim's API 23 floor, so both read the same grant.
        requiredPermissions(sdkInt).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /** Whether every runtime permission the OS demands is already granted. */
    fun hasAll(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        missing(context, sdkInt).isEmpty()
}
