package cloud.dopp.solaris

import android.content.Intent
import android.net.Uri
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduces the pairing-return path on the JVM (Robolectric — no emulator/VM):
 * the server redirects the Custom Tab to `cloud.dopp.solaris://pair#token=…`, which
 * launches [OnboardingHomeActivity]. This drives the full lifecycle with that
 * intent so a crash in onCreate/onNewIntent/deep-link handling surfaces here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingDeepLinkTest {

    private fun pairIntent(): Intent {
        val uri = Uri.parse("cloud.dopp.solaris://pair#token=sol_device_TESTTOKEN123&id=abc")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    @Test
    fun launchWithPairDeepLink_doesNotCrash() {
        Robolectric.buildActivity(OnboardingHomeActivity::class.java, pairIntent()).setup()
    }

    @Test
    fun deliverPairDeepLinkViaOnNewIntent_doesNotCrash() {
        val controller = Robolectric.buildActivity(OnboardingHomeActivity::class.java).setup()
        controller.newIntent(pairIntent())
    }
}
