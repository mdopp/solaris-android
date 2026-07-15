package cloud.dopp.solaris

import cloud.dopp.solaris.data.SbService
import cloud.dopp.solaris.widget.SbRender
import cloud.dopp.solaris.widget.SbRender.Health
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic check for the ServiceBay service-status bucketing (#44): each service
 * maps to healthy / warning / failure, and a list rolls up to the three counts the
 * summary widget shows.
 */
class SbRenderTest {

    private fun svc(active: String, sub: String = "", health: String = "") =
        SbService(name = "s", activeState = active, subState = sub, health = health)

    @Test fun activeRunningIsHealthy() {
        assertEquals(Health.HEALTHY, SbRender.bucket(svc("active", "running")))
        assertEquals(Health.HEALTHY, SbRender.bucket(svc("active", "running", "healthy")))
    }

    @Test fun failedIsFailure() {
        assertEquals(Health.FAILURE, SbRender.bucket(svc("failed")))
        assertEquals(Health.FAILURE, SbRender.bucket(svc("active", "running", "unhealthy")))
    }

    @Test fun inactiveOrDegradedIsWarning() {
        assertEquals(Health.WARNING, SbRender.bucket(svc("inactive")))
        assertEquals(Health.WARNING, SbRender.bucket(svc("activating")))
        assertEquals(Health.WARNING, SbRender.bucket(svc("active", "running", "degraded")))
    }

    @Test fun countsRollUp() {
        val list = listOf(
            svc("active", "running"),
            svc("active", "running", "healthy"),
            svc("failed"),
            svc("inactive"),
        )
        assertEquals(Triple(2, 1, 1), SbRender.counts(list))
    }
}
