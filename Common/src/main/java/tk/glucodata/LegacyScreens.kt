package tk.glucodata

import android.app.Activity
import android.view.View
import android.widget.CheckBox

/**
 * The phone-only legacy View screens that shared code reaches (plan §4, category P).
 *
 * `SetColors`/`LabelsClass` and the other legacy screens live in `src/mobile`; the
 * shared `Settings.java` used to reference `LabelsClass` directly, which is why a
 * stub had to exist in `src/wear`. That is the pattern P2 removes: shared code asks
 * the registry, the phone registers its implementation in `Specific.registerBridges`,
 * and a flavour without the screen registers nothing and the caller does without it.
 *
 * The methods grow one screen at a time; the first batch was the number labels,
 * the second the exchange/meter screens whose watch copies were dead no-ops.
 */
interface LegacyScreens {
    /** The number-labels editor, opened from the settings screen. */
    fun openLabels(activity: MainActivity, parent: View)

    /** The IOB (insulin-on-board) view, opened from the settings screen. */
    fun openIob(activity: MainActivity)

    /** The Bluetooth-meter list. [parent] is null when opened from the sensor-list panel. */
    fun openMeterList(activity: MainActivity, parent: View?)

    /** The Nightscout web-server configuration. */
    fun openNightscout(activity: Activity, parent: View)

    /**
     * The Libreview upload configuration. [noChange] is the caller's re-entrancy
     * latch: the mobile screen clears it once it has applied the new setting.
     */
    fun configureLibreview(
        activity: MainActivity,
        parent: View,
        sendTo: CheckBox,
        noChange: BooleanArray,
    )
}

object LegacyScreensAccess {
    @Volatile
    private var screens: LegacyScreens? = null

    @JvmStatic
    fun register(screens: LegacyScreens) {
        this.screens = screens
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = screens != null

    /** Null on a flavour without the legacy screens (the watch). */
    @JvmStatic
    fun get(): LegacyScreens? = screens
}
