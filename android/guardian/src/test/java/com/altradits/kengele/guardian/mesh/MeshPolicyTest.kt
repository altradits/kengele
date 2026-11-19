package com.altradits.kengele.guardian.mesh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest + strings contract for mesh intake, read from the
 * android/guardian sources: the Bluetooth permission minimum and the
 * EN+SW strings the inbox status needs.
 */
class MeshPolicyTest {
    private fun guardianFile(vararg tails: String): File {
        val roots = listOf(
            File("src/main"),
            File("guardian/src/main"),
            File("../guardian/src/main"),
            File("../../android/guardian/src/main"),
        )
        for (root in roots) {
            for (tail in tails) {
                val f = File(root, tail)
                if (f.isFile) return f
            }
        }
        throw AssertionError("not found under android/guardian: ${tails.toList()}")
    }

    @Test
    fun manifestDeclaresMeshIntakeAndNothingBeyondOsMinimum() {
        val manifest = guardianFile("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("neverForLocation"))
        assertTrue(manifest.contains("android.hardware.bluetooth_le"))
        // No location permission beyond what the OS forces below API 31,
        // and that one is capped with maxSdkVersion.
        assertFalse(manifest.contains("ACCESS_COARSE_LOCATION"))
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        val fine = manifest.lines().filter { it.contains("ACCESS_FINE_LOCATION") }
        assertEquals(1, fine.size)
        val fineBlock = manifest.substring(
            manifest.indexOf("ACCESS_FINE_LOCATION") - 200,
            manifest.indexOf("ACCESS_FINE_LOCATION") + 200,
        )
        assertTrue(fineBlock.contains("maxSdkVersion=\"30\""))
    }

    @Test
    fun meshStringsShipInEnglishAndSwahiliTogether() {
        val en = guardianFile("res/values/strings.xml").readText()
        val sw = guardianFile("res/values-sw/strings.xml").readText()
        // Mesh-bearer wording, exact text in both languages.
        assertTrue(en.contains(">Live links: %1\$d<"))
        assertTrue(sw.contains(">Viungo hai: %1\$d<"))
        assertTrue(en.contains(">Over mesh. %1\$s<"))
        assertTrue(sw.contains(">Kupitia mtandao. %1\$s<"))
        // Every mesh key exists in both languages.
        val keys = listOf(
            "mesh_links", "mesh_relayed", "mesh_off", "mesh_unavailable",
            "mesh_from_mesh", "mesh_perm_title", "mesh_perm_rationale",
            "mesh_perm_allow", "mesh_perm_denied",
        )
        for (key in keys) {
            assertTrue("EN missing $key", en.contains("name=\"$key\""))
            assertTrue("SW missing $key", sw.contains("name=\"$key\""))
        }
    }
}
