package com.altradits.kengele.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMS runtime-permission flow states, without Android. */
class SmsPermissionsTest {
    @Test
    fun requiresBothSendAndReceive() {
        assertEquals(
            listOf("android.permission.SEND_SMS", "android.permission.RECEIVE_SMS"),
            SmsPermissions.requiredPermissions(),
        )
    }

    @Test
    fun missingFiltersTheGrantMap() {
        val all = mapOf(SmsPermissions.SEND to true, SmsPermissions.RECEIVE to true)
        assertTrue(SmsPermissions.missing(all).isEmpty())
        assertEquals(
            listOf(SmsPermissions.RECEIVE),
            SmsPermissions.missing(mapOf(SmsPermissions.SEND to true, SmsPermissions.RECEIVE to false)),
        )
        assertEquals(
            listOf(SmsPermissions.SEND),
            SmsPermissions.missing(mapOf(SmsPermissions.RECEIVE to true)),
        )
        assertEquals(
            SmsPermissions.requiredPermissions(),
            SmsPermissions.missing(emptyMap()),
        )
    }

    @Test
    fun grantedWhenNothingIsMissing() {
        assertEquals(SmsPermissionState.GRANTED, SmsPermissions.state(emptyList(), false))
        assertEquals(SmsPermissionState.GRANTED, SmsPermissions.state(emptyList(), true))
    }

    @Test
    fun asksDirectlyOnFirstRequest() {
        assertEquals(
            SmsPermissionState.NEEDS_REQUEST,
            SmsPermissions.state(listOf(SmsPermissions.SEND, SmsPermissions.RECEIVE), false),
        )
        assertEquals(
            SmsPermissionState.NEEDS_REQUEST,
            SmsPermissions.state(listOf(SmsPermissions.SEND), false),
        )
    }

    @Test
    fun showsRationaleAfterADenial() {
        assertEquals(
            SmsPermissionState.NEEDS_RATIONALE,
            SmsPermissions.state(listOf(SmsPermissions.SEND, SmsPermissions.RECEIVE), true),
        )
        assertEquals(
            SmsPermissionState.NEEDS_RATIONALE,
            SmsPermissions.state(listOf(SmsPermissions.RECEIVE), true),
        )
    }
}
