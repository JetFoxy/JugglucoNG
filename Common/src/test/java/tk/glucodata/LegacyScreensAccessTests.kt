package tk.glucodata

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy-screen registry seam (plan §4, category P). The phone registers its
 * implementation; the watch registers nothing and [LegacyScreensAccess.get] returns null,
 * which the shared caller treats as "no such screen".
 */
class LegacyScreensAccessTests {
    private object Fake : LegacyScreens {
        override fun openLabels(activity: MainActivity, parent: View) {}
        override fun openIob(activity: MainActivity) {}
        override fun openMeterList(activity: MainActivity, parent: View?) {}
        override fun openNightscout(activity: Activity, parent: View) {}
        override fun configureLibreview(
            activity: MainActivity,
            parent: View,
            sendTo: CheckBox,
            noChange: BooleanArray,
        ) {
        }

        override fun openExport(activity: MainActivity, width: Int, height: Int, parent: View?) {}
        override fun setExportStatus(text: CharSequence) {}
        override fun setExportStatus(resId: Int) {}
    }

    @Test
    fun aRegisteredFlavourIsHandedBack() {
        LegacyScreensAccess.register(Fake)

        assertTrue(LegacyScreensAccess.isRegistered())
        assertSame(Fake, LegacyScreensAccess.get())
    }
}
