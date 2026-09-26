package tk.glucodata

/**
 * The phone↔watch protocol version (direction.md §6 Q2).
 *
 * The payloads a managed message carries are versioned per payload already ([WearJournalSync],
 * [WearCalibrationPayload]); the display-preferences message is the first to carry the protocol
 * version itself, in a leading `v:<n>` line. It is deliberately a line the old parser skips (no
 * `=`), so a new phone can still talk to an old watch: the rest of the payload arrives unchanged.
 *
 * A receiver that meets a version newer than [VERSION] ignores the whole payload and keeps its
 * current state, rather than applying a shape it does not understand. That is the visible half of
 * "the peer can tell it is talking to a mismatched build"; a peer that never connects at all is a
 * separate diagnostic.
 */
object WearProtocol {
    /** Bumped whenever the meaning of a managed message changes. */
    const val VERSION = 1

    private const val VERSION_PREFIX = "v:"

    fun versionLine(): String = "$VERSION_PREFIX$VERSION"

    /**
     * The version the payload declares, or null when it declares none — a payload from a build
     * before this line existed, i.e. version 1. Only the first non-blank line is inspected.
     */
    fun declaredVersion(payload: String): Int? {
        val first = payload.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        if (!first.startsWith(VERSION_PREFIX)) return null
        return first.removePrefix(VERSION_PREFIX).trim().toIntOrNull()
    }

    /** A payload with no declared version is legacy (1); one from the future is not applied. */
    fun accepts(declaredVersion: Int?): Boolean =
        declaredVersion == null || declaredVersion <= VERSION
}
