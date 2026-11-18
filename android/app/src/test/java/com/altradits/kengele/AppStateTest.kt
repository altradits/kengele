package com.altradits.kengele

import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Reinstall rule: a restored activist key without its seq counter is
 * dropped and replaced with a fresh key (guardian re-enrollment); every
 * fresh key resets the seq counter. Runs on the Android-free seam so plain
 * local unit tests cover it; all keys are made up.
 */
class AppStateTest {
    private class MemoryKeys(var priv: ByteArray? = null, var seq: Long? = null) :
        AppState.KeySeqStore {
        override fun loadPriv(): ByteArray? = priv
        override fun storePriv(fresh: ByteArray) {
            priv = fresh
        }
        override fun clearPriv() {
            priv = null
        }
        override fun hasSeq(): Boolean = seq != null
        override fun resetSeq() {
            seq = null
        }
    }

    @Test
    fun keepsKeyWhenSeqPresent() {
        val stored = Vectors.testPriv("reinstall-kept")
        val store = MemoryKeys(priv = stored, seq = 7)
        val got = AppState.activistPriv(store) { error("must not regenerate") }
        assertArrayEquals(stored, got)
        assertArrayEquals(stored, store.loadPriv())
        assertEquals(7L, store.seq)
    }

    @Test
    fun dropsRestoredKeyWithoutSeqAndGeneratesFresh() {
        val old = Vectors.testPriv("reinstall-old")
        val fresh = Vectors.testPriv("reinstall-fresh")
        val store = MemoryKeys(priv = old, seq = null)
        val got = AppState.activistPriv(store) { fresh }
        assertArrayEquals(fresh, got)
        assertFalse(old.contentEquals(store.loadPriv()))
        assertArrayEquals(fresh, store.loadPriv())
        assertFalse(store.hasSeq())
        // The fresh key is a valid signing key the activist re-enrolls with.
        Secp256k1.publicKey(got)
    }

    @Test
    fun freshInstallGeneratesKeyAndResetsSeq() {
        val fresh = Vectors.testPriv("reinstall-install")
        val store = MemoryKeys()
        val got = AppState.activistPriv(store) { fresh }
        assertArrayEquals(fresh, got)
        assertArrayEquals(fresh, store.loadPriv())
        assertFalse(store.hasSeq())
    }

    @Test
    fun staleSeqWithoutKeyIsResetOnGeneration() {
        val fresh = Vectors.testPriv("reinstall-stale-seq")
        val store = MemoryKeys(priv = null, seq = 41)
        val got = AppState.activistPriv(store) { fresh }
        assertArrayEquals(fresh, got)
        assertFalse(store.hasSeq())
    }
}
