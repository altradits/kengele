package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.nostr.NostrEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Every failure to build a public alert or found-safe note. */
class PublicNoteException(message: String) : Exception(message)

/**
 * Kotlin port of `internal/confirm` note building (`note.go`): the public
 * kind 1 Nostr notes for a release and a found-safe update, signed by the
 * activist's alert key. The content is Swahili and English, byte-identical
 * to the Go side, pinned by the note vectors in
 * `src/test/resources/kit-open-vectors.json`.
 *
 * The guardian app has no watcher status, so it builds the alert note with
 * no last check-in and no place: the note then says "has not been heard
 * from since their watch window began" and names no place. Only words the
 * activist typed may ever appear; anything that looks like coordinates is
 * refused. Android-free so plain JUnit covers it.
 */
object PublicNote {
    const val KIND_TEXT_NOTE = 1

    /**
     * The unsigned alert note for a release: the name, the last signed
     * check-in ([lastCheckInSecs], null when unknown), the place the
     * activist typed ([place], empty when unknown), the lawyer, the photo
     * link, the activist's own words and how many guardians confirmed.
     */
    fun buildAlert(
        alert: Alert,
        epoch: Long,
        confirmed: Int,
        lastCheckInSecs: Long?,
        place: String,
        nowSecs: Long,
    ): NostrEvent {
        if (epoch < 1) throw PublicNoteException("invalid epoch")
        if (looksLikeLocation(place)) {
            throw PublicNoteException("the place looks like coordinates; Kengele never publishes a location trail")
        }
        return NostrEvent(
            createdAt = nowSecs,
            kind = KIND_TEXT_NOTE,
            tags = alertTags(epoch),
            content = alertContent(alert, lastCheckInSecs, place, confirmed),
        )
    }

    /** The unsigned found-safe note; [alertId] links the alert note when known. */
    fun buildFoundSafe(alert: Alert, epoch: Long, alertId: String, nowSecs: Long): NostrEvent {
        if (epoch < 1) throw PublicNoteException("invalid epoch")
        return NostrEvent(
            createdAt = nowSecs,
            kind = KIND_TEXT_NOTE,
            tags = foundSafeTags(epoch, alertId),
            content = foundSafeContent(alert),
        )
    }

    fun alertTags(epoch: Long): List<List<String>> =
        listOf(listOf("t", "kengele"), listOf("kengele", "alert", epoch.toString()))

    fun foundSafeTags(epoch: Long, alertId: String): List<List<String>> {
        val tags = mutableListOf(listOf("t", "kengele"), listOf("kengele", "found-safe", epoch.toString()))
        if (alertId != "") {
            if (!HEX64.matches(alertId)) throw PublicNoteException("alert id must be 64 hex characters")
            tags.add(listOf("e", alertId.lowercase()))
        }
        return tags
    }

    fun alertContent(alert: Alert, lastCheckInSecs: Long?, place: String, confirmed: Int): String {
        val b = StringBuilder()
        b.append("KENGELE: TAHADHARI / ALERT\n\n")
        b.append(alert.name)
        b.append(" hajasikika tangu / has not been heard from since ")
        b.append(whenSeen(lastCheckInSecs))
        b.append(".\n")
        val p = goTrim(place)
        if (p != "") {
            b.append("Mahali pa mwisho (aliandika) / Last place (typed by them): ")
            b.append(p)
            b.append("\n")
        }
        b.append("Wakili / Lawyer: ")
        b.append(alert.lawyer)
        b.append("\n")
        if (alert.photo != "") {
            b.append("Picha / Photo: ")
            b.append(alert.photo)
            b.append("\n")
        }
        val t = goTrim(alert.text)
        if (t != "") {
            b.append("\n")
            b.append(t)
            b.append("\n")
        }
        b.append("\nImethibitishwa na walinzi ")
        b.append(confirmed)
        b.append(" / Confirmed by ")
        b.append(confirmed)
        b.append(" of the guardians they chose. ")
        b.append("Check this note is signed by the alert key on their profile. #kengele")
        return b.toString()
    }

    fun foundSafeContent(alert: Alert): String =
        "KENGELE: YUKO SALAMA / FOUND SAFE\n\n" + alert.name +
            " amepatikana salama / has been found safe. Asanteni. " +
            "Thank you to everyone who shared the alert. #kengele"

    private fun whenSeen(lastCheckInSecs: Long?): String {
        if (lastCheckInSecs == null || lastCheckInSecs <= 0) return "their watch window began"
        return Instant.ofEpochSecond(lastCheckInSecs).atZone(ZoneOffset.UTC).format(WHEN_FORMAT)
    }

    /** Go's strings.TrimSpace: unicode spaces, including NBSP and NEL. */
    private fun goTrim(s: String): String = s.trim { it == '\u00A0' || it == '\u0085' || Character.isWhitespace(it) }

    private val WHEN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
}
