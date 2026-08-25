package cloud.dopp.solaris

import android.appwidget.AppWidgetManager
import android.content.Intent
import cloud.dopp.solaris.widget.AppShortcuts
import cloud.dopp.solaris.widget.DeviceShortcuts
import cloud.dopp.solaris.widget.PwaTrampolineActivity
import cloud.dopp.solaris.widget.ShortcutAction
import cloud.dopp.solaris.widget.ShortcutDevice
import cloud.dopp.solaris.widget.WidgetActionActivity
import cloud.dopp.solaris.widget.WidgetActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app-icon long-press menu (#97).
 *
 * A long-press menu on the *widget* is the launcher's, not ours (#96), so the
 * device entries live on the app icon. Two things must hold and both are pure
 * JVM logic: **how many** entries appear (the cap is the launcher's answer, not
 * a guessed four) and **what a lock's entry does** (open the #92 chooser, never
 * switch, never reach `lock.open`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShortcutsTest {

    private fun dev(id: Int, entity: String, name: String, domain: String, cls: String? = null) =
        ShortcutDevice(id, entity, name, domain, cls)

    private fun lamps(n: Int) = (1..n).map { dev(it, "light.l$it", "Lampe $it", "light") }

    // --- the count: empty, at the cap, over it ---------------------------------

    @Test fun noDevicesMeansNoEntries() {
        assertTrue(DeviceShortcuts.entries(emptyList(), 4).isEmpty())
    }

    @Test fun everyDeviceFitsBelowTheCap() {
        assertEquals(3, DeviceShortcuts.entries(lamps(3), 4).size)
    }

    @Test fun exactlyAtTheCapNothingIsDropped() {
        val e = DeviceShortcuts.entries(lamps(4), 4)
        assertEquals(4, e.size)
        assertEquals(setOf("light.l1", "light.l2", "light.l3", "light.l4"), e.map { it.device.entityId }.toSet())
    }

    /** Over the cap: the newest widgets win, and the rule is an order, not a truncation. */
    @Test fun overTheCapTheMostRecentlyAddedWin() {
        val e = DeviceShortcuts.entries(lamps(7), 3)
        assertEquals(3, e.size)
        assertEquals(listOf("light.l7", "light.l6", "light.l5"), e.map { it.device.entityId })
    }

    /** The order does not depend on the order the store happened to hand them over. */
    @Test fun theRuleIsDeterministicWhateverTheInputOrder() {
        val a = DeviceShortcuts.entries(lamps(7), 3)
        val b = DeviceShortcuts.entries(lamps(7).shuffled(), 3)
        assertEquals(a.map { it.device.entityId }, b.map { it.device.entityId })
    }

    /** A launcher that allows nothing (or an API < 25 phone, where the compat cap is 0). */
    @Test fun aZeroCapPublishesNothing() {
        for (cap in listOf(0, -1)) assertTrue(DeviceShortcuts.entries(lamps(5), cap).isEmpty())
    }

    @Test fun theSameDeviceOnTwoWidgetsIsOneEntry() {
        val e = DeviceShortcuts.entries(
            listOf(dev(1, "light.flur", "Flur", "light"), dev(9, "light.flur", "Flur", "light")),
            4,
        )
        assertEquals(1, e.size)
        assertEquals(9, e[0].device.appWidgetId) // the newer widget carries it
    }

    @Test fun anUnconfiguredWidgetIsNotADevice() {
        assertTrue(DeviceShortcuts.entries(listOf(dev(1, "", "—", "")), 4).isEmpty())
    }

    @Test fun anUnnamedDeviceStillGetsAReadableEntry() {
        val e = DeviceShortcuts.entries(listOf(dev(1, "switch.x", "", "switch")), 4)
        assertEquals("switch.x", e[0].label)
    }

    // --- the security rules ----------------------------------------------------

    /** The core guard: a lock never gets an entry that switches anything. */
    @Test fun aLockNeverActsDirectly() {
        val e = DeviceShortcuts.entries(listOf(dev(1, "lock.haustuer", "Haustür", "lock")), 4)
        assertEquals(1, e.size)
        assertEquals(ShortcutAction.CHOOSE, e[0].action)
        assertFalse(e[0].actsDirectly)
        assertFalse(DeviceShortcuts.actsDirectly("lock"))
    }

    /** Neither does any other safety-gated device — a garage door opens its card. */
    @Test fun sensitiveDevicesDoNotActEither() {
        val devices = listOf(
            dev(1, "cover.garage", "Garage", "cover", "garage"),
            dev(2, "alarm_control_panel.haus", "Alarm", "alarm_control_panel"),
        )
        for (e in DeviceShortcuts.entries(devices, 4)) {
            assertFalse("${e.device.entityId} acts", e.actsDirectly)
            assertEquals(ShortcutAction.OPEN, e.action)
        }
    }

    /** Light and switch may act — a mistap there is harmless, which is the point. */
    @Test fun harmlessDevicesMayAct() {
        val devices = listOf(dev(1, "light.a", "Licht", "light"), dev(2, "switch.b", "Schalter", "switch"))
        for (e in DeviceShortcuts.entries(devices, 4)) assertTrue(e.actsDirectly)
    }

    /** Mixed household, cap exceeded: whatever survives, no lock is an actor. */
    @Test fun noLockActsWhateverTheMix() {
        val devices = listOf(
            dev(1, "light.a", "Licht", "light"),
            dev(2, "lock.haustuer", "Haustür", "lock"),
            dev(3, "lock.keller", "Keller", "lock"),
            dev(4, "switch.b", "Schalter", "switch"),
            dev(5, "cover.tor", "Tor", "cover", "gate"),
        )
        for (cap in 1..6) {
            for (e in DeviceShortcuts.entries(devices, cap)) {
                if (e.device.domain == "lock") assertFalse("cap $cap", e.actsDirectly)
            }
        }
    }

    // --- what the intents actually carry ---------------------------------------

    /** A lock's entry opens the chooser activity, carries no service, and says so. */
    @Test fun theLockEntryOpensTheChooserAndNothingElse() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(7, "lock.haustuer", "Haustür", "lock")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        // No service rides along — the pick in the chooser names it.
        assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(7, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** No entry, for any device, may name the latch. */
    @Test fun nothingPublishedCanReachLockOpen() {
        val ctx = RuntimeEnvironment.getApplication()
        val devices = listOf(
            dev(1, "lock.haustuer", "Haustür", "lock"),
            dev(2, "cover.garage", "Garage", "cover", "garage"),
            dev(3, "light.a", "Licht", "light"),
        )
        for (e in DeviceShortcuts.entries(devices, 4)) {
            val i = AppShortcuts.intentFor(ctx, e)
            assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
            assertFalse(i.toUri(Intent.URI_INTENT_SCHEME).contains("lock.open"))
        }
    }

    /** A non-sensitive entry lands on the trampoline that asks for the widget's toggle. */
    @Test fun aLampEntryAsksForTheOrdinaryToggle() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(3, "light.flur", "Flur", "light")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
    }

    /** A sensitive non-lock entry only opens a screen. */
    @Test fun aGarageEntryOnlyOpensTheCard() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(4, "cover.garage", "Garage", "cover", "garage")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(PwaTrampolineActivity::class.java.name, i.component?.className)
        assertTrue(
            i.getStringExtra(PwaTrampolineActivity.EXTRA_PATH)?.contains("cover.garage") == true,
        )
    }

    /** Shortcut ids are stable per entity, so a refresh updates instead of churning. */
    @Test fun idsAreStablePerEntity() {
        val once = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        val again = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        assertEquals(once, again)
        assertTrue(once.all { it.startsWith(DeviceShortcuts.ID_PREFIX) })
    }
}
