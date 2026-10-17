package tk.glucodata.drivers.medtrum

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

class MedtrumSensorLifeTest {

    private val hour = 3_600_000L

    @Test
    fun startIsNowMinusTheElapsedSecondsTheVendorAppCountsFrom() {
        // Captured: elapsed=38629s; the vendor app showed 5 h 56 min elapsed 4 h 47 min earlier.
        val now = 1_792_263_327_000L
        assertEquals(now - 38_629_000L, MedtrumSensorLife.startFromElapsed(now, 38_629L))
    }

    @Test
    fun theEndIsFourteenDaysAfterTheStart() {
        val start = 1_000_000_000_000L
        assertEquals(start + 14 * 24 * hour, MedtrumSensorLife.endFromStart(start))
    }

    @Test
    fun remainingTimeMatchesTheVendorCountdown() {
        // The vendor app showed 13 d 18 h 4 min left at 5 h 56 min elapsed: 330 whole hours.
        val now = 2_000_000_000_000L
        val start = now - (5 * hour + 56 * 60_000L)
        assertEquals(330, MedtrumSensorLife.remainingHours(start, now))
        assertEquals(5, MedtrumSensorLife.ageHours(start, now))
    }

    @Test
    fun anUnknownStartHasNoDatesAndNoAge() {
        assertEquals(0L, MedtrumSensorLife.startFromElapsed(1_000L, 0L))
        assertEquals(0L, MedtrumSensorLife.endFromStart(0L))
        assertEquals(-1, MedtrumSensorLife.ageHours(0L, 1_000L))
        assertEquals(-1, MedtrumSensorLife.remainingHours(0L, 1_000L))
    }

    @Test
    fun anExpiredPatchHasZeroHoursLeft() {
        val start = 1_000_000_000_000L
        assertEquals(0, MedtrumSensorLife.remainingHours(start, start + 15 * 24 * hour))
    }

    @Test
    fun jitterDoesNotRewriteTheStoredStartButARealMoveDoes() {
        val stored = 1_000_000_000_000L
        assertTrue(MedtrumSensorLife.shouldReplaceStart(0L, stored))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, stored + 5_000L))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, stored - 90_000L))
        assertTrue(MedtrumSensorLife.shouldReplaceStart(stored, stored + 3 * 60_000L))
        assertFalse(MedtrumSensorLife.shouldReplaceStart(stored, 0L))
    }
}

class MedtrumSerialAliasTest {

    @Test
    fun theNativeShortNameOfTheCapturedSerialIsAnAlias() {
        assertTrue(MedtrumSerialAlias.isShortForm("95446", "2329895446"))
    }

    @Test
    fun onlyAnEndingOfTheSerialCounts() {
        assertFalse(MedtrumSerialAlias.isShortForm("23298", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("95447", "2329895446"))
    }

    @Test
    fun theSerialItselfAndTooShortOrNonNumericNamesAreNotAliases() {
        assertFalse(MedtrumSerialAlias.isShortForm("2329895446", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("5446", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("3MH01M0MWYR", "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm(null, "2329895446"))
        assertFalse(MedtrumSerialAlias.isShortForm("95446", null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MedtrumTestApplication::class, shadows = [MedtrumTestNatives::class])
class MedtrumIdentityAliasTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        MedtrumPersistence.clear(context)
        MedtrumPersistence.setSensorId(context, "2329895446")
    }

    @Test
    fun theShortNativeNameIsClaimedSoNoLibreCallbackIsBuiltForIt() {
        assertTrue(MedtrumIdentityAdapter.hasPersistedManagedRecord("95446"))
        assertTrue(MedtrumIdentityAdapter.hasPersistedManagedRecord("2329895446"))
        assertFalse(MedtrumIdentityAdapter.hasPersistedManagedRecord("3MH01M0MWYR"))
    }

    @Test
    fun claimingTheAliasCreatesNoCallbackOfItsOwn() {
        assertNull(MedtrumIdentityAdapter.createManagedCallback(context, "95446", 0L))
    }

    @Test
    fun callbackIdMatchingSeesTheAliasAsTheSamePatch() {
        assertTrue(MedtrumIdentityAdapter.matchesCallbackId("95446", "2329895446"))
        assertTrue(MedtrumIdentityAdapter.matchesCallbackId("2329895446", "95446"))
        assertFalse(MedtrumIdentityAdapter.matchesCallbackId("3MH01M0MWYR", "2329895446"))
    }

    @Test
    fun aDriverReportsItsDatesOnceReadStateHasToldItWhenThePatchStarted() {
        val start = System.currentTimeMillis() - 38_629_000L
        MedtrumPersistence.setSensorStartMs(context, start)
        val driver = MedtrumBleManager("2329895446", 0L)
        try {
            assertEquals(start, driver.getStartTimeMs())
            assertEquals(start + MedtrumSensorLife.LIFETIME_MS, driver.getExpectedEndMs())
            val snapshot = driver.getManagedUiSnapshot("2329895446")!!
            assertEquals(start, snapshot.startTimeMs)
            assertEquals(start + MedtrumSensorLife.LIFETIME_MS, snapshot.expectedEndMs)
            assertEquals(10, snapshot.sensorAgeHours)
            assertFalse(snapshot.isSensorExpired)
        } finally {
            driver.terminateManagedSensor(wipeData = true)
        }
    }

    @Test
    fun aDriverWithoutAKnownStartHasNoDates() {
        val driver = MedtrumBleManager("2329895446", 0L)
        try {
            assertEquals(0L, driver.getStartTimeMs())
            assertEquals(0L, driver.getManagedUiSnapshot("2329895446")!!.expectedEndMs)
        } finally {
            driver.terminateManagedSensor(wipeData = true)
        }
    }
}
