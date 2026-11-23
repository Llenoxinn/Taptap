package io.github.llenoxinn.taptap.mesh

// Plaintext layouts that get sealed to the server (M1 spec §5.2, §5.3).

private const val NO_FIX: Int = Int.MIN_VALUE

internal fun encodeSosPayload(req: SosRequest): ByteArray {
    val note = truncateUtf8(req.note, EnvelopeCodec.MAX_NOTE)
    return le(ByteArray(EnvelopeCodec.SOS_FIXED + note.size))
        .putInt(req.lat ?: NO_FIX)
        .putInt(req.lon ?: NO_FIX)
        .putShort(req.accuracyM.coerceIn(0, 0xFFFF).toShort())
        .putShort(req.fixAgeS.coerceIn(0, 0xFFFF).toShort())
        .put(req.people.toByte())
        .put(req.injured.toByte())
        .putShort(req.flags.toShort())
        .put(note)
        .array()
}

internal fun decodeSosPayload(priority: Priority, bytes: ByteArray): SosRequest? {
    if (bytes.size !in EnvelopeCodec.SOS_FIXED..EnvelopeCodec.MAX_PAYLOAD) return null
    val b = le(bytes)
    val lat = b.int
    val lon = b.int
    return SosRequest(
        priority = priority,
        lat = lat.takeIf { it != NO_FIX },
        lon = lon.takeIf { it != NO_FIX },
        accuracyM = b.short.toInt() and 0xFFFF,
        fixAgeS = b.short.toInt() and 0xFFFF,
        people = b.get().u8,
        injured = b.get().u8,
        flags = b.short.toInt() and 0xFFFF,
        note = bytes.copyOfRange(EnvelopeCodec.SOS_FIXED, bytes.size).decodeToString(),
    )
}

internal fun encodeLocation(loc: Location): ByteArray =
    le(ByteArray(EnvelopeCodec.LOCATION_SIZE))
        .putInt(loc.lat ?: NO_FIX)
        .putInt(loc.lon ?: NO_FIX)
        .putShort(loc.accuracyM.coerceIn(0, 0xFFFF).toShort())
        .putShort(loc.fixAgeS.coerceIn(0, 0xFFFF).toShort())
        .array()

internal fun decodeLocation(bytes: ByteArray): Location? {
    if (bytes.size != EnvelopeCodec.LOCATION_SIZE) return null
    val b = le(bytes)
    val lat = b.int
    val lon = b.int
    return Location(
        lat.takeIf { it != NO_FIX },
        lon.takeIf { it != NO_FIX },
        b.short.toInt() and 0xFFFF,
        b.short.toInt() and 0xFFFF,
    )
}

/** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a code point. */
internal fun truncateUtf8(s: String, max: Int): ByteArray {
    val bytes = s.encodeToByteArray()
    if (bytes.size <= max) return bytes
    var end = max
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end-- // back up off continuation bytes
    return bytes.copyOf(end)
}
