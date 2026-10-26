package tk.glucodata.drivers.ottai

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiFirmwareShutdownTests {
    private fun session(): Pair<OttaiFirmwareSession, ExecutorService> {
        // Construct the real session without starting BLE or making any cloud requests.
        val constructor = OttaiFirmwareSession::class.java.getDeclaredConstructor(
            Context::class.java, String::class.java, String::class.java,
            OttaiRegistry.DeviceMaterials::class.java, OttaiFirmwareMode::class.java,
            kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }
        val publish: (OttaiFirmwareState) -> Unit = {}
        val session = constructor.newInstance(RuntimeEnvironment.getApplication(), "AABBCCDDEEFF",
            "AA:BB:CC:DD:EE:FF", OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1),
            OttaiFirmwareMode.STARTED_SENSOR, publish) as OttaiFirmwareSession
        val worker = OttaiFirmwareSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(session) as ExecutorService
        return session to worker
    }

    @Test fun successfulCloseDrainsAQueuedReportWithoutBlockingTheCaller() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            worker.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val reported = AtomicBoolean(false)
            val report = worker.submit { reported.set(true) }
            session.close(completed = true)
            assertTrue(worker.isShutdown)
            assertFalse(reported.get())
            // Disposal after completion must not turn graceful shutdown into interruption.
            session.close(completed = false)
            release.countDown()
            report.get(2, TimeUnit.SECONDS)
            assertTrue(reported.get())
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }

    @Test fun successfulCloseDoesNotInterruptARunningReport() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val reported = AtomicBoolean(false)
            val report = worker.submit { entered.countDown(); release.await(); reported.set(true) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.close(completed = true)
            release.countDown()
            report.get(2, TimeUnit.SECONDS)
            assertTrue(reported.get())
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }

    @Test fun cancelledCloseStillInterruptsWorkAndDiscardsQueuedRequests() {
        val (session, worker) = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val reported = AtomicBoolean(false)
        try {
            worker.submit {
                entered.countDown()
                try { release.await() } catch (_: InterruptedException) { interrupted.set(true) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit { reported.set(true) }
            session.close(completed = false)
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(interrupted.get())
            assertFalse(reported.get())
        } finally {
            release.countDown()
            session.close(completed = false)
            worker.shutdownNow()
        }
    }
}
