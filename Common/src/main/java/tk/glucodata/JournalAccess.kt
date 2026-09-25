package tk.glucodata

/**
 * Bridge from src/main to the phone's journal, which lives in the mobile source
 * set only (plan P1/Q1).
 *
 * The phone registers its [JournalBridge] from [Specific.registerBridges]; the
 * watch registers nothing, and the shared caller sees the absence as
 * null/false (P2).
 *
 * This used to resolve `tk.glucodata.data.journal.WearJournalBridge` by name.
 * R8 renames those members in release builds, so the watch journal silently did
 * nothing there; explicit registration leaves ordinary interface calls behind.
 */
object JournalAccess {
    @Volatile
    private var bridge: JournalBridge? = null

    /** Registered at startup, before the watch can ask for the journal. */
    @JvmStatic
    fun register(bridge: JournalBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** Encoded journal payload, or null when there is no journal to serve. */
    @JvmStatic
    fun serveEntries(fromMs: Long): ByteArray? = bridge?.serveEntries(fromMs)

    @JvmStatic
    fun applyCommand(data: ByteArray): Boolean = bridge?.applyCommand(data) ?: false

    @JvmStatic
    fun isJournalEnabled(): Boolean = bridge?.isJournalEnabled() ?: false
}
