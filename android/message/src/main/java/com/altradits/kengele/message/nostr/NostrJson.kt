package com.altradits.kengele.message.nostr

/** Every failure in the Nostr transport code that lives in the message module. */
class NostrException(message: String, val notForUs: Boolean = false) : Exception(message)

/**
 * Minimal JSON reader and writer for the fixed Nostr shapes (events,
 * envelopes, filters). Hand-rolled because `org.json` is not on the unit-test
 * classpath; it only handles what the transport needs.
 */
object NostrJson {
    sealed interface Value
    data class Str(val v: String) : Value
    data class Num(val raw: String) : Value {
        fun long(): Long = raw.toLongOrNull() ?: throw NostrException("bad number $raw")
        fun int(): Int = raw.toIntOrNull() ?: throw NostrException("bad number $raw")
    }
    data class Bool(val v: Boolean) : Value
    object Null : Value
    data class Arr(val items: List<Value>) : Value
    data class Obj(val fields: Map<String, Value>) : Value {
        fun str(key: String): String = (fields[key] as? Str)?.v ?: ""
        fun num(key: String): Long = (fields[key] as? Num)?.long() ?: 0L
        fun opt(key: String): Value? = fields[key]
    }

    /** Parses one complete JSON value; trailing garbage is an error. */
    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.value()
        p.spaces()
        if (!p.end()) throw NostrException("trailing JSON")
        return v
    }

    /** Quotes a string; escapes only what JSON requires. */
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\u000C' -> out.append("\\f")
                '\r' -> out.append("\\r")
                else -> if (c < '\u0020') {
                    out.append("\\u00")
                    out.append(Character.forDigit(c.code shr 4, 16))
                    out.append(Character.forDigit(c.code and 0xF, 16))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun end(): Boolean = i >= s.length

        fun spaces() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Value {
            spaces()
            if (i >= s.length) throw NostrException("short JSON")
            return when (s[i]) {
                '"' -> Str(string())
                '{' -> obj()
                '[' -> arr()
                't' -> {
                    expect("true")
                    Bool(true)
                }
                'f' -> {
                    expect("false")
                    Bool(false)
                }
                'n' -> {
                    expect("null")
                    Null
                }
                else -> number()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw NostrException("bad JSON literal")
            i += word.length
        }

        private fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw NostrException("short JSON string")
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) throw NostrException("short JSON escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            't' -> out.append('\t')
                            'n' -> out.append('\n')
                            'f' -> out.append('\u000C')
                            'r' -> out.append('\r')
                            'u' -> {
                                if (i + 4 > s.length) throw NostrException("short \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw NostrException("bad \\u escape")
                                i += 4
                                out.append(code.toChar())
                            }
                            else -> throw NostrException("bad JSON escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun obj(): Obj {
            i++ // {
            val fields = LinkedHashMap<String, Value>()
            spaces()
            if (i < s.length && s[i] == '}') {
                i++
                return Obj(fields)
            }
            while (true) {
                spaces()
                if (i >= s.length || s[i] != '"') throw NostrException("bad JSON object")
                val key = string()
                spaces()
                if (i >= s.length || s[i] != ':') throw NostrException("bad JSON object")
                i++
                fields[key] = value()
                spaces()
                if (i >= s.length) throw NostrException("short JSON object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return Obj(fields)
                    else -> throw NostrException("bad JSON object")
                }
            }
        }

        private fun arr(): Arr {
            i++ // [
            val items = ArrayList<Value>()
            spaces()
            if (i < s.length && s[i] == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                items.add(value())
                spaces()
                if (i >= s.length) throw NostrException("short JSON array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return Arr(items)
                    else -> throw NostrException("bad JSON array")
                }
            }
        }

        private fun number(): Num {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            if (start == i) throw NostrException("bad JSON value")
            return Num(s.substring(start, i))
        }
    }
}
