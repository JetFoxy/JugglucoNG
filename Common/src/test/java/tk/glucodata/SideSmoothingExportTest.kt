package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Dev-only: what the phone would hand AAPS for each smoothing configuration of
 * `docs/superpowers/plans/2026-10-22-juggluco-side-smoothing-for-aaps.md`, computed by the
 * production code over the exported traces. Nothing here ships.
 *
 * One CSV per configuration, in the column layout the AAPS harness reads (`calibrated` and `stock`
 * both carry the value the phone sends, `raw` stays the sensor's own):
 *
 *  - `P0` the reading as measured, no smoothing.
 *  - `P1` the averaging window of 3 minutes at the live edge, as `exportExchangeSmoothingForAaps`
 *    does it: one evaluation per reading over the recent history, the point with the live timestamp.
 *  - `P3` [CausalGlucoseFilter] with its production parameters.
 *  - `P4` the same filter with R estimated from the window itself (experiment, not wired in).
 *
 * Env:
 *  - `UKF_DATASETS`  directory with the traces (default `~/Downloads/ukf_datasets`)
 *  - `SIDE_SMOOTHING_OUT`  output directory (default: the same one)
 *  - `SIDE_DATASETS`  comma separated dataset names (default: the six of the plan)
 *
 * The input is `stock`, else `calibrated`, as the other export does. Two sessions that overlap in
 * time keep the first one in file order, as the AAPS loader does, so both sides filter one series.
 */
class SideSmoothingExportTest {

    private val mgdlPerMmol = 18.0182

    private val datasets = System.getenv("SIDE_DATASETS")?.split(',')?.filter { it.isNotBlank() }
        ?: listOf("aidex", "sibionics2", "sibionics_other", "libre2", "ican", "syai_54DC")

    private data class Row(val index: String, val timestampMs: Long, val raw: String, val value: Double)

    private val dataDir = File(
        System.getenv("UKF_DATASETS") ?: (System.getProperty("user.home") + "/Downloads/ukf_datasets")
    )
    private val outDir = System.getenv("SIDE_SMOOTHING_OUT")?.let(::File) ?: dataDir

    private fun load(name: String): List<Row> {
        val file = File(dataDir, "$name.csv")
        if (!file.isFile) return emptyList()
        val lines = file.readLines()
        val header = lines.first().split(',')
        val cIdx = header.indexOf("index")
        val cTs = header.indexOf("timestampMs")
        val cRaw = header.indexOf("raw")
        val cVal = header.indexOf("calibrated")
        val cStock = header.indexOf("stock")
        require(cTs >= 0 && cVal >= 0) { "unexpected columns in ${file.name}: $header" }
        val seen = HashSet<Long>(lines.size)
        val rows = ArrayList<Row>(lines.size)
        for (line in lines.drop(1)) {
            val f = line.split(',')
            val ts = f.getOrNull(cTs)?.toLongOrNull() ?: continue
            if (!seen.add(ts)) continue
            val v = (if (cStock >= 0) f.getOrNull(cStock)?.toDoubleOrNull() else null)
                ?: f.getOrNull(cVal)?.toDoubleOrNull() ?: continue
            if (v <= 0.0) continue
            rows.add(Row(f.getOrNull(cIdx).orEmpty(), ts, f.getOrNull(cRaw).orEmpty(), v))
        }
        return rows.sortedBy { it.timestampMs }
    }

    /** R from the window alone: a robust noise level read off the second differences. */
    private fun adaptiveParams(times: LongArray, mgdl: DoubleArray, at: Int): CausalGlucoseFilter.Params {
        val end = times[at]
        var start = at
        while (start > 0 && times[start - 1] > end - CausalGlucoseFilter.WINDOW_MS) start--
        val second = ArrayList<Double>()
        for (i in start + 2..at) {
            val a = times[i - 1] - times[i - 2]
            val b = times[i] - times[i - 1]
            // Only evenly spaced triplets: a second difference over uneven spacing measures the gap.
            if (abs(a - b) > 30_000L || a > GlucoseChartGap.THRESHOLD_MS) continue
            second.add(mgdl[i] - 2.0 * mgdl[i - 1] + mgdl[i - 2])
        }
        if (second.size < MIN_TRIPLETS) return CausalGlucoseFilter.DEFAULT
        val sorted = second.map { abs(it) }.sorted()
        val mad = sorted[sorted.size / 2]
        // Median |x| of a zero-mean normal is 0.6745 sigma; a second difference has variance 6 sigma^2.
        val sigma2 = (mad / 0.6745) * (mad / 0.6745) / 6.0
        return CausalGlucoseFilter.DEFAULT.copy(r = sigma2.coerceIn(R_MIN, R_MAX))
    }

    private fun liveWindow3(rows: List<Row>): DoubleArray {
        val out = DoubleArray(rows.size)
        val points = ArrayList<GlucosePoint>()
        for ((i, r) in rows.withIndex()) {
            points.add(GlucosePoint(r.timestampMs, r.value.toFloat(), r.value.toFloat()))
            if (points.size > 400) points.removeAt(0)
            val smoothed = DataSmoothing.smoothNativePoints(points, 3, false, r.timestampMs)
            out[i] = smoothed.last { it.timestamp == r.timestampMs }.value.toDouble()
        }
        return out
    }

    private fun write(name: String, id: String, rows: List<Row>, values: DoubleArray) {
        outDir.mkdirs()
        val out = File(outDir, "${name}_$id.csv")
        out.writeText(buildString {
            append("index,timestampMs,raw,calibrated,stock,v2Lower90,v2Upper90\n")
            rows.forEachIndexed { i, r ->
                val v = String.format(Locale.US, "%.4f", values[i])
                append("${r.index},${r.timestampMs},${r.raw},$v,$v,,\n")
            }
        })
        println("SIDE_SMOOTHING $name $id rows=${rows.size} -> $out")
    }

    @Test
    fun exportConfigurations() {
        assumeTrue("dataset directory not present: $dataDir", dataDir.isDirectory)
        for (name in datasets) {
            val rows = load(name)
            if (rows.isEmpty()) continue
            val times = LongArray(rows.size) { rows[it].timestampMs }
            val mgdl = DoubleArray(rows.size) { rows[it].value * mgdlPerMmol }

            val p0 = DoubleArray(rows.size) { rows[it].value }
            val p1 = liveWindow3(rows)
            val p3 = DoubleArray(rows.size) { i ->
                CausalGlucoseFilter.estimateAt(times, mgdl, i)!!.mgdl / mgdlPerMmol
            }
            val p4 = DoubleArray(rows.size) { i ->
                CausalGlucoseFilter.estimateAt(times, mgdl, i, adaptiveParams(times, mgdl, i))!!.mgdl / mgdlPerMmol
            }
            write(name, "P0", rows, p0)
            write(name, "P1", rows, p1)
            write(name, "P3", rows, p3)
            write(name, "P4", rows, p4)

            val gap = max(1, rows.size / 40)
            var worst = 0.0
            for (i in rows.indices step gap) {
                val trailing = rows.subList(max(0, i - 399), i + 1).map {
                    GlucosePoint(it.timestampMs, (it.value).toFloat(), (it.value).toFloat())
                }
                val viaProduction = DataSmoothing.smoothNativePointsCausal(
                    trailing, 5, false, mgdlPerMmol.toFloat(), Long.MAX_VALUE / 2
                ).last().value.toDouble()
                worst = max(worst, abs(viaProduction - p3[i]))
            }
            println(String.format(Locale.US, "SIDE_SMOOTHING %s P3 vs smoothNativePointsCausal: max |diff| %.5f mmol/L", name, worst))
            assertEquals("production path disagrees with the export for $name", 0.0, worst, 1e-3)
        }
    }

    /**
     * Metric 5: the live value against the value after a backfill filled the hole.
     *
     * The live path estimates the sample after a hole from what it has, and a backfill later adds the
     * missed readings, so the same minute is computed twice from different inputs. A hole is cut out
     * of the real trace at evenly spread places and the sample after it is estimated both ways. A
     * hole above [GlucoseChartGap.THRESHOLD_MS] restarts the filter on the live side, which is the
     * worst case and is kept in the numbers.
     */
    @Test
    fun liveVersusBackfilledAfterAHole() {
        assumeTrue("dataset directory not present: $dataDir", dataDir.isDirectory)
        for (name in datasets) {
            val rows = load(name)
            if (rows.size < 500) continue
            val times = LongArray(rows.size) { rows[it].timestampMs }
            val mgdl = DoubleArray(rows.size) { rows[it].value * mgdlPerMmol }
            val line = StringBuilder("SIDE_SMOOTHING_HOLES $name")
            for (spanMin in listOf(5, 10, 15)) {
                val spanMs = spanMin * 60_000L
                val first = ArrayList<Double>()
                val later = ArrayList<Double>()
                var i = 120
                while (i < rows.size - 20) {
                    val holeEnd = times[i] + spanMs
                    var j = i + 1
                    while (j < rows.size && times[j] <= holeEnd) j++
                    // Only holes that really cut samples out of an otherwise continuous stretch.
                    val continuous = times[i + 1] - times[i] <= GlucoseChartGap.THRESHOLD_MS &&
                        times[minOf(rows.size - 1, j + 5)] - times[j] <= 10 * 60_000L
                    if (j > i + 1 && j + 5 < rows.size && continuous) {
                        val keep = (0 until rows.size).filter { it <= i || it >= j }
                        val t2 = LongArray(keep.size) { times[keep[it]] }
                        val v2 = DoubleArray(keep.size) { mgdl[keep[it]] }
                        for ((offset, bucket) in listOf(0 to first, 5 to later)) {
                            val full = CausalGlucoseFilter.estimateAt(times, mgdl, j + offset)!!.mgdl
                            val cut = CausalGlucoseFilter.estimateAt(t2, v2, keep.indexOf(j + offset))!!.mgdl
                            bucket.add(abs(full - cut))
                        }
                    }
                    i += 97
                }
                if (first.isNotEmpty()) {
                    fun q(v: List<Double>, p: Double) = v.sorted()[((v.size - 1) * p).toInt()]
                    line.append(String.format(Locale.US,
                        " | %dmin n=%d next: med %.2f p95 %.2f max %.2f; +5: med %.2f p95 %.2f max %.2f",
                        spanMin, first.size, q(first, .5), q(first, .95), first.max(),
                        q(later, .5), q(later, .95), later.max()))
                }
            }
            println(line)
        }
    }

    private companion object {
        const val MIN_TRIPLETS = 10
        const val R_MIN = 4.0
        const val R_MAX = 100.0
    }
}
