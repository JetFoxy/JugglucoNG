package tk.glucodata.drivers.sibionics

import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Dev-only: which decoded sensitivity makes the stock algorithm reproduce a recorded `stock`
 * column. The AAPS export carries no temperature or impedance, so both are constants here and
 * the fit is only as good as that assumption; the spread it leaves is the part they would explain.
 *
 * Env: SIBIONICS_FIT_IN (a trace with index,timestampMs,raw,stock), SIBIONICS_FIT_ROWS (default 30000).
 */
class SibionicsSensitivityFitTest {

    @Test
    fun fitSensitivityAgainstRecordedStock() {
        val path = System.getenv("SIBIONICS_FIT_IN")
        assumeTrue("SIBIONICS_FIT_IN not set", path != null)
        val maxRows = System.getenv("SIBIONICS_FIT_ROWS")?.toIntOrNull() ?: 30_000
        val lines = File(path!!).readLines()
        val header = lines.first().split(',')
        val cIdx = header.indexOf("index")
        val cTs = header.indexOf("timestampMs")
        val cRaw = header.indexOf("raw")
        val cStock = header.indexOf("stock")
        val rows = lines.drop(1).mapNotNull { line ->
            val f = line.split(',')
            val ts = f.getOrNull(cTs)?.toLongOrNull() ?: return@mapNotNull null
            val raw = f.getOrNull(cRaw)?.toFloatOrNull() ?: return@mapNotNull null
            val stock = f.getOrNull(cStock)?.toFloatOrNull() ?: return@mapNotNull null
            if (raw <= 0f) return@mapNotNull null
            Triple(
                SibionicsSourceSample(
                    index = f.getOrNull(cIdx)?.toIntOrNull() ?: (ts / 60_000L).toInt(),
                    timestampMs = ts, rawMmol = raw, temperatureC = 33.9f, impedance = 2_900f, variantId = 0,
                ),
                stock, 0
            )
        }.take(maxRows)
        for (s in listOf(0.9f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.27f, 1.3f, 1.4f, 1.5f)) {
            val ctx = SibionicsAlgorithmContext("fit-$s").apply {
                configure("", s, SibionicsConstants.Variant.CHINESE, SibionicsAlgorithmSelection.STOCK)
            }
            val ratios = ArrayList<Double>()
            var within = 0
            var counted = 0
            for ((sample, recorded, _) in rows) {
                val v = ctx.process(
                    rawMmol = sample.rawMmol, temperatureC = sample.temperatureC, index = sample.index,
                    mode = SibionicsAlgorithmMode.REPLAY, impedance = sample.impedance,
                    eventTimeMs = sample.timestampMs,
                )
                if (counted >= 200 && v > 1f && recorded > 1f) {
                    ratios.add((recorded / v).toDouble())
                    if (kotlin.math.abs(recorded - v) <= 0.1f) within++
                }
                counted++
            }
            ratios.sort()
            println(
                String.format(
                    Locale.US, "SIBIONICS_FIT s=%.2f n=%d median(recorded/replay)=%.3f p10=%.3f p90=%.3f within0.1=%.1f%%",
                    s, ratios.size, ratios[ratios.size / 2], ratios[ratios.size / 10], ratios[ratios.size * 9 / 10],
                    100.0 * within / ratios.size
                )
            )
        }
    }
}
