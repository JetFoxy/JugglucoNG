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
}
