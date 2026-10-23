package tk.glucodata.drivers.sibionics

import java.io.File
import org.junit.Test

/**
 * Dev-only export: replays the recorded 11-day trace through the real Sibionics
 * pipeline and writes the aligned observation CSV (calibratedMmol + uncertainty +
 * stock comparison) so the AAPS side can feed it into its own estimator.
 *
 * Fixture ingestion mirrors [SibionicsRealTraceEvaluationTest] exactly, so the numbers
 * match what that test reports. Output path via SIBIONICS_EXPORT_OUT.
 */
class SibionicsObservationExportTest {

    private data class Row(val index: Int, val rawMmol: Float, val temperatureC: Float)

    private fun trace(): List<Row> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sibionics_exact_v115g_replay.csv")) {
            "missing replay fixture"
        }.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.split(',')
                if (fields.size < 3) return@mapNotNull null
                val index = fields[0].toIntOrNull() ?: return@mapNotNull null
                val raw = fields[1].toFloatOrNull() ?: return@mapNotNull null
                val temperature = fields[2].toFloatOrNull() ?: return@mapNotNull null
                Row(index, raw, temperature)
            }

    private fun samples(): List<SibionicsSourceSample> = trace().map {
        SibionicsSourceSample(
            index = it.index,
            timestampMs = it.index * 60_000L,
            rawMmol = it.rawMmol,
            temperatureC = it.temperatureC,
            // The fixture predates impedance capture; a constant keeps telemetry quiet.
            impedance = 2_900f,
            variantId = 0,
        )
    }

    @Test
    fun exportObservationCsv() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
        val out = File(
            System.getenv("SIBIONICS_EXPORT_OUT")
                ?: "build/reports/sibionics_observation_export.csv"
        ).absoluteFile
        out.parentFile?.mkdirs()
        out.writeText(SibionicsReplayHarness.toCsv(rows))
        println("SIBIONICS_EXPORT rows=${rows.size} -> $out")
    }

    /**
     * Replays the same raw trace through the real Sibionics pipeline and writes two AAPS-format
     * CSVs: one whose `calibrated` column is the vendor observation (what Adaptive V2 consumes) and
     * one whose `calibrated` column is Adaptive V2's own output. The AAPS harness can then run the
     * same matrix on both, and the difference is exactly what V2 does to AutoISF's inputs.
     * Temperature and impedance are constant because the AAPS export does not carry them; both
     * files share that input, so the comparison stays differential.
     */
    @Test
    fun exportAapsComparisonForSibionics2() {
        val src = File(System.getenv("SIBIONICS_AAPS_IN") ?: error("SIBIONICS_AAPS_IN not set"))
        val lines = src.readLines()
        val header = lines.first().split(',')
        val cIdx = header.indexOf("index")
        val cTs = header.indexOf("timestampMs")
        val cRaw = header.indexOf("raw")
        require(cTs >= 0 && cRaw >= 0) { "unexpected columns: $header" }
        val samples = lines.drop(1).mapNotNull { line ->
            val f = line.split(',')
            val ts = f.getOrNull(cTs)?.toLongOrNull() ?: return@mapNotNull null
            val raw = f.getOrNull(cRaw)?.toFloatOrNull() ?: return@mapNotNull null
            if (raw <= 0f) return@mapNotNull null
            SibionicsSourceSample(
                index = f.getOrNull(cIdx)?.toIntOrNull() ?: (ts / 60_000L).toInt(),
                timestampMs = ts,
                rawMmol = raw,
                temperatureC = 33.9f,
                impedance = 2_900f,
                variantId = 0,
            )
        }
        val rows = SibionicsReplayHarness.replay(samples = samples, sensitivity = 1.4f)
        fun write(name: String, value: (SibionicsReplayHarness.Row) -> Float) {
            val out = File(src.parentFile, name)
            out.writeText(buildString {
                append("index,timestampMs,raw,calibrated,stock,v2Lower90,v2Upper90\n")
                rows.forEach { r ->
                    append("${r.index},${r.timestampMs},${r.rawMmol},${value(r)},${r.stockMmol},,\n")
                }
            })
            println("AAPS_EXPORT rows=${rows.size} -> $out")
        }
        write("sibionics2_replay_cal.csv") { it.calibratedMmol }
        write("sibionics2_replay_v2.csv") { it.adaptiveV2Mmol }
    }

    /**
     * Dev-only: what the xDrip broadcast carries, computed by the real exchange path.
     *
     * The phone resolves the exchange snapshot through `CurrentDisplaySource.exchangeSmoothingMode`
     * (smoothing on, `collapseChunks = false` - "collapse into chunks" is only the exchange rate
     * limit) and sends the point that carries the *live* timestamp. So the value of a reading is the
     * one-phase-edge result of `smoothNativePoints` over the recent history, evaluated with the
     * real code, not with a description of it. The AAPS harness uses this file to check its port.
     */
    @Test
    fun exportExchangeSmoothingForAaps() {
        val src = File(System.getenv("SIBIONICS_AAPS_IN") ?: error("SIBIONICS_AAPS_IN not set"))
        val lines = src.readLines()
        val header = lines.first().split(',')
        val cTs = header.indexOf("timestampMs")
        val cVal = header.indexOf("calibrated")
        val cStock = header.indexOf("stock")
        require(cTs >= 0 && cVal >= 0) { "unexpected columns: $header" }
        val trace = lines.drop(1).mapNotNull { line ->
            val f = line.split(',')
            val ts = f.getOrNull(cTs)?.toLongOrNull() ?: return@mapNotNull null
            val v = (if (cStock >= 0) f.getOrNull(cStock)?.toDoubleOrNull() else null)
                ?: f.getOrNull(cVal)?.toDoubleOrNull() ?: return@mapNotNull null
            if (v <= 0.0) return@mapNotNull null
            ts to v.toFloat()
        }.sortedBy { it.first }

        val out = File(src.parentFile, "sibionics2_exchange_smoothing.csv")
        out.writeText(buildString {
            append("timestampMs,windowMin,value\n")
            for (window in listOf(0, 2, 3, 4, 5, 7)) {
                val points = ArrayList<tk.glucodata.GlucosePoint>()
                for ((ts, v) in trace) {
                    points.add(tk.glucodata.GlucosePoint(ts, v, v))
                    if (points.size > 400) points.removeAt(0)
                    if (window == 0) {
                        append("$ts,$window,$v\n")
                        continue
                    }
                    val smoothed = tk.glucodata.DataSmoothing.smoothNativePoints(points, window, false, ts)
                    val at = smoothed.last { it.timestamp == ts }
                    append("$ts,$window,${at.value}\n")
                }
            }
        })
        println("EXCHANGE_SMOOTHING rows=${trace.size} -> $out")
    }
}
