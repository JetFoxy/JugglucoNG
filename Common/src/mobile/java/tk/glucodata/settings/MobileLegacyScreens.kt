package tk.glucodata.settings

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import tk.glucodata.IOB
import tk.glucodata.LegacyScreens
import tk.glucodata.Libreview
import tk.glucodata.MainActivity
import tk.glucodata.MeterList
import tk.glucodata.Nightscout

/**
 * The phone's legacy View screens (plan §4, category P). Registered from the mobile
 * `Specific.registerBridges`; shared `Settings.java` reaches them through
 * [tk.glucodata.LegacyScreensAccess] instead of naming `LabelsClass` directly, so the
 * watch no longer needs a stub.
 */
object MobileLegacyScreens : LegacyScreens {
    override fun openLabels(activity: MainActivity, parent: View) {
        LabelsClass(activity).mklabellayout(parent)
    }

    override fun openIob(activity: MainActivity) {
        IOB.mkview(activity)
    }

    override fun openMeterList(activity: MainActivity, parent: View?) {
        MeterList.show(activity, parent)
    }

    override fun openNightscout(activity: Activity, parent: View) {
        Nightscout.show(activity, parent)
    }

    override fun configureLibreview(
        activity: MainActivity,
        parent: View,
        sendTo: CheckBox,
        noChange: BooleanArray,
    ) {
        Libreview.config(activity, parent, sendTo, noChange)
    }
}
