package tk.glucodata.drivers.ottai

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiOtaPersistenceTests {
    private lateinit var context: Context
    private val id = "AABBCCDDEEFF"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun nullableFactoryCalibrationSurvivesStorageAndExportWithoutDefaults() {
        val original = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.8.0", 1,
            calibrationValueK = 1.25, calibrationValueB = -0.5)
        assertTrue(OttaiRegistry.saveMaterials(context, id, original))
        assertEquals(1.25, OttaiRegistry.loadMaterials(context, id).calibrationValueK!!, 0.0)
        val export = requireNotNull(OttaiRegistry.exportJson(context, id))
        assertTrue(OttaiRegistry.saveMaterials(context, id, original.copy(calibrationValueK = null, calibrationValueB = null)))
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueK)
        assertEquals(id, OttaiRegistry.importJson(context, export))
        assertEquals(-0.5, OttaiRegistry.loadMaterials(context, id).calibrationValueB!!, 0.0)
        OttaiRegistry.forgetCredentials(context, id)
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueK)
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueB)
    }

    @Test fun otaResponseKeepsMandatoryAndAllowedSeparateAndRejectsMissingFlags() {
        val offer = OttaiCloudClient.parseOtaOffer(JSONObject("""{"data":{"requiredUpgrade":true,"allowUpgrade":false,"downloadUrl":"https://cdn.example/V1.8.0.gbl"}}"""))
        assertTrue(offer.required)
        assertFalse(offer.allowed)
        assertEquals("https://cdn.example/V1.8.0.gbl", offer.downloadUrl)
        assertThrows(IllegalArgumentException::class.java) {
            OttaiCloudClient.parseOtaOffer(JSONObject("""{"data":{"requiredUpgrade":false}}"""))
        }
    }

    @Test fun recoveryLedgerSurvivesInterruptedAtomicWriteAndBlocksForcedActivation() {
        val file = File(context.filesDir, "ottai-ota/$id.bin.bak")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        assertTrue(OttaiFirmwareSession.hasPendingTransfer(context, "AA:BB:CC:DD:EE:FF"))
        val manager = OttaiBleManager(id, 0)
        try {
            assertFalse(manager.requestActivation())
            assertFalse(manager.requestForceActivation())
        } finally {
            manager.close()
            file.delete()
        }
        assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
    }
}
