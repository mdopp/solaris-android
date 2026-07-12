package cloud.dopp.solaris.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.core.content.ContextCompat
import cloud.dopp.solaris.R

/**
 * Invisible trampoline for the voice widget (#17). Mirrors [PwaTrampolineActivity]:
 * a no-UI translucent shim launched from a widget tap. It
 *  1. ensures RECORD_AUDIO (requests it once; on denial toasts a German hint),
 *  2. launches the system speech recognizer (German, free-form dictation),
 *  3. on a non-blank result opens a NEW household chat via the PWA `?ask=` route
 *     ([PwaLauncher.Routes.ask]) so the server auto-sends the spoken text, and
 *  4. always [finish]es — empty/cancelled results just close silently.
 *
 * The recognizer runs the same STT engine the Solaris web app reaches via the Web
 * Speech API, so this is "wie auf Solaris" without any Whisper/token round-trip.
 */
class VoiceTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMicPermission()) {
            startRecognition()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSION)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecognition()
        } else {
            toast(R.string.voice_perm_denied)
            finish()
        }
    }

    /** Fire the free-form German speech recognizer, or bail with a hint if none. */
    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: Exception) {
            // No recognizer app installed/available on this device.
            toast(R.string.voice_no_recognizer)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                PwaLauncher.open(this, PwaLauncher.Routes.ask(text))
            }
        }
        // Empty / cancelled → close silently. Trampoline never lingers.
        finish()
    }

    private fun toast(resId: Int) =
        Toast.makeText(applicationContext, getString(resId), Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMISSION = 1
        private const val REQ_SPEECH = 2
    }
}
