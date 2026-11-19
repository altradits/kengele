package com.altradits.kengele.sms

/**
 * One SMS part's send result (docs/sms-transport.md, section 2 table),
 * plus [NO_PERMISSION] for a missing Android SEND_SMS grant.
 */
enum class SendResult {
    OK,
    NO_SERVICE,
    RADIO_OFF,
    NO_PERMISSION,
    FAILURE,
}

/**
 * Android-free seam over SmsManager: split a 304-character text into
 * segments, then send them as one concatenated SMS. Results arrive
 * asynchronously, one per part, through [SmsDeliveryHub.deliver].
 */
interface SmsGateway {
    /** Segments [text]; a 304-character GSM-7 text gives two segments. */
    fun divide(text: String): List<String>

    /** Sends [parts] as a single concatenated SMS to [number]. */
    fun send(requestId: Long, number: String, parts: List<String>)
}
