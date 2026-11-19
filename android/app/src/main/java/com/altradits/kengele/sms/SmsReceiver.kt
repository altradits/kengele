package com.altradits.kengele.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.altradits.kengele.AppState
import com.altradits.kengele.message.KengeleMessage

/**
 * Hand-off for SMS events opened by [SmsReceiver]. The engine (watcher)
 * wiring that consumes these comes later; until then the default listener
 * drops them after reassembly and dedup.
 */
object SmsIncomingHub {
    var listener: (KengeleMessage.Event) -> Unit = {}
}

/**
 * Trial-decodes one 304-character candidate with [KengeleMessage]: strict
 * decode, then open with a watched sender's key. Null when it is not
 * ours; never throws, so a hostile text cannot crash the receiver.
 */
class KengeleSmsDecoder(
    private val recipientPriv: () -> ByteArray,
    private val senders: () -> List<ByteArray>,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) : SmsDecoder {
    override fun tryOpen(text: String): KengeleMessage.Event? = try {
        KengeleMessage.open(KengeleMessage.decodeText(text), recipientPriv(), senders(), nowSecs())
    } catch (e: Exception) {
        null
    }
}

/**
 * Incoming-SMS receiver (manifest-registered for SMS_RECEIVED). Each
 * sender's parts in one broadcast are joined in order — the normal
 * concatenated case (Path A) — and handed once per number to the shared
 * [SmsReassembler], which also covers split delivery (Path B).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        AppState.init(context.applicationContext)
        val reassembler = SmsReceiverState.reassembler()
        val bodies = LinkedHashMap<String, StringBuilder>()
        for (message in messages) {
            val from = message.originatingAddress ?: continue
            val body = message.messageBody ?: continue
            bodies.getOrPut(from) { StringBuilder() }.append(body)
        }
        for ((from, body) in bodies) reassembler.onText(from, body.toString())
    }
}

/**
 * Process-wide reassembler: Path B candidates and the (signer, seq)
 * dedup set must span broadcasts, while receiver instances are transient.
 */
internal object SmsReceiverState {
    private var reassembler: SmsReassembler? = null

    @Synchronized
    fun reassembler(): SmsReassembler =
        reassembler ?: SmsReassembler(
            decoder = KengeleSmsDecoder(
                // Activist-side handset: incoming SMS is an all-clear sealed
                // to the activist key. Guardian-role identity (decode with a
                // guardian key, watch the activist key) lands with
                // guardian-mode storage; until then such texts are ignored.
                recipientPriv = AppState::activistPriv,
                senders = { AppState.guardians.guardians() },
            ),
            onEvent = { event -> SmsIncomingHub.listener(event) },
        ).also { reassembler = it }
}
