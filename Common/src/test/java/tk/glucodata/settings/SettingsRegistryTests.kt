package tk.glucodata.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the one place a mirrored setting is declared (plan §2.4), so these pin the
 * properties [tk.glucodata.WearPrefsSync] and the readers depend on, and the aliases that used to
 * be hand-kept copies of the same default.
 */
class SettingsRegistryTests {

    @Test
    fun everyDeclaredKeyIsUniqueAndFindable() {
        val keys = SettingsRegistry.definitions.map { it.key }
        assertEquals("keys must not repeat", keys.size, keys.distinct().size)
        SettingsRegistry.definitions.forEach { definition ->
            assertSame(definition, SettingsRegistry.find(definition.key))
        }
    }

    @Test
    fun everyDefaultMatchesItsTypeAndRange() {
        SettingsRegistry.definitions.forEach { definition ->
            val matches = when (definition.type) {
                SettingType.INT -> definition.defaultValue is Int
                SettingType.BOOL -> definition.defaultValue is Boolean
                SettingType.FLOAT -> definition.defaultValue is Float
                SettingType.STRING -> definition.defaultValue is String
            }
            assertTrue("${definition.key} default type", matches)
            val min = definition.minValue
            val max = definition.maxValue
            if (min != null && max != null) {
                assertTrue("${definition.key} range", min <= max)
                val value = when (val default = definition.defaultValue) {
                    is Int -> default.toFloat()
                    is Float -> default
                    else -> return@forEach
                }
                assertTrue("${definition.key} default is inside its range", value in min..max)
            }
        }
    }

    @Test
    fun thePilotMirrorIsExactlyThePredictionAndSmoothingSettings() {
        assertEquals(
            setOf(
                KEY_SMOOTHING_MINUTES,
                KEY_SMOOTHING_GRAPH_ONLY,
                KEY_SMOOTHING_COLLAPSE_CHUNKS,
                KEY_SMOOTHING_EXCHANGE_OUTPUTS_ONLY,
                KEY_PREDICTION_ENABLED,
                KEY_PREDICTION_TREND_MOMENTUM,
                KEY_PREDICTION_CARB_RATIO,
                KEY_PREDICTION_INSULIN_SENSITIVITY,
                KEY_PREDICTION_CARB_ABSORPTION,
                KEY_PREDICTION_HORIZON,
                KEY_SENSOR_SELECTION,
                KEY_SENSOR_COLORS,
            ),
            SettingsRegistry.mirrored.map { it.key }.toSet(),
        )
        assertTrue("mirrored definitions carry the mirrored scope", SettingsRegistry.mirrored.all { it.scope == SettingScope.MIRRORED })
    }

    @Test
    fun keyConstantsAgreeWithTheirDefinitions() {
        assertEquals(KEY_SMOOTHING_MINUTES, SettingsRegistry.SMOOTHING_MINUTES.key)
        assertEquals(KEY_SMOOTHING_GRAPH_ONLY, SettingsRegistry.SMOOTHING_GRAPH_ONLY.key)
        assertEquals(KEY_SMOOTHING_COLLAPSE_CHUNKS, SettingsRegistry.SMOOTHING_COLLAPSE_CHUNKS.key)
        assertEquals(KEY_SMOOTHING_EXCHANGE_OUTPUTS_ONLY, SettingsRegistry.SMOOTHING_EXCHANGE_OUTPUTS_ONLY.key)
        assertEquals(KEY_PREDICTION_ENABLED, SettingsRegistry.PREDICTION_ENABLED.key)
        assertEquals(KEY_PREDICTION_TREND_MOMENTUM, SettingsRegistry.PREDICTION_TREND_MOMENTUM.key)
        assertEquals(KEY_PREDICTION_CARB_RATIO, SettingsRegistry.PREDICTION_CARB_RATIO.key)
        assertEquals(KEY_PREDICTION_INSULIN_SENSITIVITY, SettingsRegistry.PREDICTION_INSULIN_SENSITIVITY.key)
        assertEquals(KEY_PREDICTION_CARB_ABSORPTION, SettingsRegistry.PREDICTION_CARB_ABSORPTION.key)
        assertEquals(KEY_PREDICTION_HORIZON, SettingsRegistry.PREDICTION_HORIZON.key)
        assertEquals(KEY_SENSOR_SELECTION, SettingsRegistry.SENSOR_SELECTION.key)
        assertEquals(KEY_SENSOR_COLORS, SettingsRegistry.SENSOR_COLORS.key)
    }

    @Test
    fun theOldDefaultCopiesPointAtTheRegistry() {
        // These were the same literal declared separately in four or five files; if one is ever
        // given its own value again, this fails.
        assertEquals(
            DEFAULT_PREDICTION_HORIZON_MINUTES,
            tk.glucodata.settings.SettingsRegistry.PREDICTION_HORIZON.defaultValue as Int,
        )
        assertEquals(
            DEFAULT_PREDICTION_CARB_ABSORPTION_G_PER_H.toDouble(),
            tk.glucodata.data.prediction.PredictionModelProfile.DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR.toDouble(),
            0.0,
        )
        assertEquals(
            DEFAULT_PREDICTION_CARB_RATIO_G_PER_U.toDouble(),
            tk.glucodata.data.prediction.PredictionModelProfileStore.DEFAULT_CARB_RATIO_GRAMS_PER_UNIT.toDouble(),
            0.0,
        )
        assertEquals(
            DEFAULT_PREDICTION_INSULIN_SENSITIVITY_MGDL_PER_U.toDouble(),
            tk.glucodata.data.prediction.PredictionModelProfileStore.DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT.toDouble(),
            0.0,
        )
    }
}
