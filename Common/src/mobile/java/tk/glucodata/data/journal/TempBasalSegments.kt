package tk.glucodata.data.journal

/**
 * When each AAPS temp basal actually stopped running. A new temp basal replaces the running
 * one, and the replaced record does not always come back shortened, so a segment ends at its
 * own duration or at the next temp basal's start, whichever is first.
 *
 * Works over every temp basal, not just the ones in view: a replacement scrolled out of the
 * chart window still cuts the segment it replaced. Keyed by entry id; temp basals without a
 * duration are left out.
 *
 * The same basal can arrive twice: from the local AAPS status line, kept as one row per run,
 * and from Nightscout, as one row per treatment. Where the status line has a segment, it
 * wins: a Nightscout copy starting inside it is left out, so it can neither cut the status
 * line segment short nor draw a second bar over it. One that started earlier keeps its
 * history and is cut where the status line segment starts, like any replaced temp basal.
 */
fun tempBasalEndsMillis(entries: List<JournalEntry>): Map<Long, Long> {
    val all = entries.filter { it.type == JournalEntryType.TEMP_BASAL }
    val statusLine = all.filter { AapsStatusLine.isStatusLineRecord(it.sourceRecordId) }
    val tempBasals = all
        .filter { entry ->
            AapsStatusLine.isStatusLineRecord(entry.sourceRecordId) ||
                statusLine.none { entry.timestamp in it.timestamp until it.ownEndMillis() }
        }
        .sortedWith(compareBy({ it.timestamp }, { it.id }))
    return buildMap {
        tempBasals.forEachIndexed { index, entry ->
            if (entry.durationMinutes == null) return@forEachIndexed
            val nextStart = tempBasals.getOrNull(index + 1)?.timestamp ?: Long.MAX_VALUE
            put(entry.id, minOf(entry.ownEndMillis(), nextStart))
        }
    }
}

private fun JournalEntry.ownEndMillis(): Long =
    timestamp + (durationMinutes ?: 0).coerceAtLeast(1) * 60_000L
