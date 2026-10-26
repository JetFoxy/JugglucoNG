package tk.glucodata.data.journal

/**
 * When each AAPS temp basal actually stopped running. A new temp basal replaces the running
 * one, and the replaced record does not always come back shortened, so a segment ends at its
 * own duration or at the next temp basal's start, whichever is first.
 *
 * Works over every temp basal, not just the ones in view: a replacement scrolled out of the
 * chart window still cuts the segment it replaced. Keyed by entry id; temp basals without a
 * duration are left out.
 */
fun tempBasalEndsMillis(entries: List<JournalEntry>): Map<Long, Long> {
    val tempBasals = entries
        .filter { it.type == JournalEntryType.TEMP_BASAL }
        .sortedWith(compareBy({ it.timestamp }, { it.id }))
    return buildMap {
        tempBasals.forEachIndexed { index, entry ->
            val durationMinutes = entry.durationMinutes ?: return@forEachIndexed
            val ownEnd = entry.timestamp + durationMinutes.coerceAtLeast(1) * 60_000L
            val nextStart = tempBasals.getOrNull(index + 1)?.timestamp ?: Long.MAX_VALUE
            put(entry.id, minOf(ownEnd, nextStart))
        }
    }
}
