package tk.glucodata.drivers.icanhealth

/** Only the captured 13-character factory identity can build the regular CE/RU list. */
internal object ICanHealthProvisioningPolicy {
    enum class Eligibility { UNKNOWN_SENSOR, INSTALLED_TABLE, INVALID_FACTORY_SERIAL, FRESH }

    fun factoryCode(serial: String?): Float? {
        if (serial == null || !serial.matches(Regex("[0-9A-Za-z]{13}")) ||
            !serial.substring(9, 11).all { it in '0'..'9' }) return null
        return serial.substring(9, 11).toInt() / 100f
    }

    fun eligibility(marker: Int?, serial: String?): Eligibility = when {
        marker == null -> Eligibility.UNKNOWN_SENSOR
        marker != 0xffff -> Eligibility.INSTALLED_TABLE
        factoryCode(serial) == null -> Eligibility.INVALID_FACTORY_SERIAL
        else -> Eligibility.FRESH
    }

    /** Where a fresh sensor sits relative to the vendor's provisioning window. */
    enum class WriteWindow { ACTIVATION, ALREADY_RUNNING, UNKNOWN_STATE }

    /**
     * The vendor writes the calibration table only in the activation window: `launcherState == 0`
     * on a sensor that has no session yet, or right after its own `1A` start command. A sensor
     * that is already running is never provisioned mid-session — the 2026-10-27 trace (issue
     * #744) has the three `F2` chunks landing two seconds before a live iCGM-t3 stopped
     * advancing its `0x2AA9` counter.
     *
     * `sessionStartReadPending` keeps the zero-session branch honest: `0x2AA9` is read before
     * `0x2AAA`, and `launcherState == 0` is also what a live iCGM-t6 reports, so an unread
     * session start must not be mistaken for "no session".
     */
    fun writeWindow(
        launcherState: Int,
        sessionStartEpochMs: Long,
        startCommandIssuedThisConnection: Boolean,
        sessionStartReadPending: Boolean = false,
    ): WriteWindow = when {
        startCommandIssuedThisConnection -> WriteWindow.ACTIVATION
        sessionStartReadPending -> WriteWindow.UNKNOWN_STATE
        sessionStartEpochMs > 0L -> WriteWindow.ALREADY_RUNNING
        launcherState == ICanHealthConstants.LAUNCHER_STATE_IDLE -> WriteWindow.ACTIVATION
        launcherState < 0 -> WriteWindow.UNKNOWN_STATE
        else -> WriteWindow.ALREADY_RUNNING
    }

    // Known server fallback for unknown identities. It is reference data, never provisioning data.
    val defaultTable: Map<String, Float> = mapOf(
        "x1" to 0.015f,
        "x2" to 32.0f,
        "x3" to 25.0f,
        "x4" to 1.0f,
        "x5" to 0.8f,
        "x6" to 0.0f,
        "x7" to 40.0f,
        "x8" to 0.3f,
        "x9" to 1.0f,
        "x10" to 1.2f,
        "x11" to 13.0f,
        "x12" to 1.5f,
        "x13" to 0.4f,
        "x14" to 3.5f,
        "x15" to 15.0f,
        "x16" to 15.0f,
        "x17" to 0.1f,
        "x18" to 0.6f,
        "x19" to 0.2f,
        "x20" to 0.6f,
        "x21" to 0.1f,
        "x22" to 16.0f,
        "x23" to 1.0f,
        "x24" to 2.1f,
        "x25" to 2.2f,
    )

    /** Compare every coefficient we write, ignoring the box-derived x13. */
    fun isGenericTable(table: Map<String, Float>): Boolean =
        (1..15).filter { it != 13 }.all { table["x$it"] == defaultTable["x$it"] }
}

/** A canceled lookup cannot commit or schedule retries after removal or a positive F6. */
internal class ICanHealthCloudFetchOwnership {
    class Ticket(val serial: String)
    private val active = HashMap<String, Ticket>()

    @Synchronized
    fun begin(serial: String): Ticket = active.getOrPut(serial) { Ticket(serial) }

    @Synchronized
    fun current(ticket: Ticket): Boolean = active[ticket.serial] === ticket

    @Synchronized
    fun commitIfCurrent(ticket: Ticket, commit: () -> Boolean): Boolean =
        active[ticket.serial] === ticket && commit()

    @Synchronized
    fun cancel(serial: String) { active.remove(serial) }
}
