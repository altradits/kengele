package com.altradits.kengele.mesh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-free policy pins for the mesh carry layer: the Doze-aware scan
 * duty cycle, the API-gated permission map, the 180-second re-arm backstop
 * and the manifest/strings contract (EN + SW together).
 */
class MeshPolicyTest {
    // -- Scan duty cycle: bursts, then sleep; longer sleep while dozing. --

    @Test
    fun dutyCycleScansFirstThenSleeps() {
        assertTrue(ScanDutyCycle.shouldScan(false, 0))
        assertTrue(ScanDutyCycle.shouldScan(false, ScanDutyCycle.NORMAL_SCAN_MS - 1))
        assertFalse(ScanDutyCycle.shouldScan(false, ScanDutyCycle.NORMAL_SCAN_MS))
        assertFalse(
            ScanDutyCycle.shouldScan(
                false,
                ScanDutyCycle.NORMAL_SCAN_MS + ScanDutyCycle.NORMAL_REST_MS - 1,
            ),
        )
        assertTrue(
            ScanDutyCycle.shouldScan(
                false,
                ScanDutyCycle.NORMAL_SCAN_MS + ScanDutyCycle.NORMAL_REST_MS,
            ),
        )
    }

    @Test
    fun dutyCycleSleepsLongerWhileDozing() {
        assertTrue(ScanDutyCycle.shouldScan(true, 0))
        assertTrue(ScanDutyCycle.shouldScan(true, ScanDutyCycle.DOZE_SCAN_MS - 1))
        assertFalse(ScanDutyCycle.shouldScan(true, ScanDutyCycle.DOZE_SCAN_MS))
        // A full awake cycle fits inside one doze rest: the phone sleeps
        // through Doze instead of churning the radio.
        assertTrue(ScanDutyCycle.DOZE_REST_MS > ScanDutyCycle.NORMAL_REST_MS)
        val dozeCycle = ScanDutyCycle.DOZE_SCAN_MS + ScanDutyCycle.DOZE_REST_MS
        assertFalse(ScanDutyCycle.shouldScan(true, dozeCycle - 1))
        assertTrue(ScanDutyCycle.shouldScan(true, dozeCycle))
    }

    // -- Permissions: BT trio on 31+, OS-minimum location below. --

    @Test
    fun permissionMapFollowsTheOsMinimum() {
        val trio = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
        )
        assertEquals(trio, MeshPermissions.requiredPermissions(31))
        assertEquals(trio, MeshPermissions.requiredPermissions(34))
        // Below 31 the OS forces a location permission for BLE scanning;
        // Kengele still calls no location API anywhere (see
        // MeshCarryTest.carryPathIsMemoryOnlyAndNeverTouchesGps).
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION"),
            MeshPermissions.requiredPermissions(30),
        )
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION"),
            MeshPermissions.requiredPermissions(26),
        )
    }

    @Test
    fun rearmBackstopIs180Seconds() {
        assertEquals(180L, MeshService.REARM_SECS)
    }

    // -- Manifest + strings contract, read from the android/app sources. --

    private fun appFile(vararg tails: String): File {
        val roots = listOf(
            File("src/main"),
            File("app/src/main"),
            File("../app/src/main"),
            File("../../android/app/src/main"),
        )
        for (root in roots) {
            for (tail in tails) {
                val f = File(root, tail)
                if (f.isFile) return f
            }
        }
        throw AssertionError("not found under android/app: ${tails.toList()}")
    }

    @Test
    fun manifestDeclaresMeshCarryAndNothingBeyondOsMinimum() {
        val manifest = appFile("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("neverForLocation"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(manifest.contains("foregroundServiceType=\"connectedDevice\""))
        assertTrue(manifest.contains(".mesh.MeshService"))
        assertTrue(manifest.contains(".mesh.MeshRearmReceiver"))
        assertTrue(manifest.contains(".mesh.MeshStatusActivity"))
        // No location permission beyond what the OS forces below API 31,
        // and that one is capped with maxSdkVersion.
        assertFalse(manifest.contains("ACCESS_COARSE_LOCATION"))
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        val fine = manifest.lines().filter { it.contains("ACCESS_FINE_LOCATION") }
        assertEquals(1, fine.size)
        assertTrue(manifest.contains("ACCESS_FINE_LOCATION\"\n") || manifest.contains("ACCESS_FINE_LOCATION"))
        val fineBlock = manifest.substring(
            manifest.indexOf("ACCESS_FINE_LOCATION") - 200,
            manifest.indexOf("ACCESS_FINE_LOCATION") + 200,
        )
        assertTrue(fineBlock.contains("maxSdkVersion=\"30\""))
    }

    @Test
    fun meshStringsShipInEnglishAndSwahiliTogether() {
        val en = appFile("res/values/strings.xml").readText()
        val sw = appFile("res/values-sw/strings.xml").readText()
        // Spec-pinned strings (docs/offgrid-comms.md section 4), exact text.
        assertTrue(en.contains(">Relaying blindly<"))
        assertTrue(sw.contains(">Inasambaza bila kusoma<"))
        assertTrue(en.contains(">No path — silence fails safe<"))
        assertTrue(sw.contains(">Hakuna njia — kimya ni salama<"))
        // Every mesh key exists in both languages.
        val keys = listOf(
            "menu_mesh", "mesh_title", "mesh_relaying", "mesh_no_path",
            "mesh_advertising", "mesh_scanning", "mesh_links", "mesh_relayed",
            "mesh_start", "mesh_stop", "mesh_running", "mesh_stopped",
            "mesh_perm_title", "mesh_perm_rationale", "mesh_perm_allow",
            "mesh_perm_denied", "mesh_service_name", "mesh_service_text",
            "mesh_window_inactive", "mesh_unavailable",
        )
        for (key in keys) {
            assertTrue("EN missing $key", en.contains("name=\"$key\""))
            assertTrue("SW missing $key", sw.contains("name=\"$key\""))
        }
    }
}
