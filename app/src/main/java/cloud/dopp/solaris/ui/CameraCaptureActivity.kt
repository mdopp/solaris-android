package cloud.dopp.solaris.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.ServerStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native camera capture + upload (#36). A tap on the camera trigger widget opens
 * this screen (NOT the PWA): take one photo or a series, name the file (date/time
 * prefilled + editable), optionally combine the series into a single multi-page
 * PDF, and upload to the paired Solaris server via `POST /napi/upload`
 * (server contract solarisbay#826). The upload gracefully reports when the
 * endpoint isn't deployed yet (404) so the capture flow is testable meanwhile.
 */
class CameraCaptureActivity : AppCompatActivity() {

    private val pages = mutableListOf<File>()
    private var pendingCapture: File? = null

    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var filenameField: EditText
    private lateinit var pdfSwitch: Switch
    private lateinit var uploadBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var progress: ProgressBar

    // Largest edge we scale captures to for PDF/upload — keeps size/memory sane
    // while staying legible for documents.
    private val maxEdge = 1600

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingCapture
            pendingCapture = null
            if (ok && f != null && f.exists() && f.length() > 0) {
                pages.add(f)
                refresh()
            } else {
                f?.delete()
            }
        }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCapture()
            else toast(getString(R.string.camera_capture_no_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        countView = findViewById(R.id.cap_count)
        statusView = findViewById(R.id.cap_status)
        filenameField = findViewById(R.id.cap_filename)
        pdfSwitch = findViewById(R.id.cap_pdf)
        uploadBtn = findViewById(R.id.cap_upload)
        clearBtn = findViewById(R.id.cap_clear)
        progress = findViewById(R.id.cap_progress)

        // Prefill the filename with a sortable date/time stamp; the user edits the
        // suffix. e.g. "2026-07-14_1830_".
        filenameField.setText(SimpleDateFormat("yyyy-MM-dd_HHmm_", Locale.GERMANY).format(Date()))
        filenameField.setSelection(filenameField.text.length)

        findViewById<Button>(R.id.cap_take).setOnClickListener { onTake() }
        clearBtn.setOnClickListener { clearPages() }
        uploadBtn.setOnClickListener { onUpload() }

        refresh()
    }

    private fun onTake() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCapture() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun launchCapture() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "page_${System.currentTimeMillis()}.jpg")
        pendingCapture = f
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingCapture = null
            f.delete()
            toast(getString(R.string.camera_capture_no_camera))
        }
    }

    private fun clearPages() {
        pages.forEach { it.delete() }
        pages.clear()
        refresh()
    }

    private fun refresh() {
        val n = pages.size
        countView.text = when (n) {
            0 -> getString(R.string.camera_capture_none)
            1 -> getString(R.string.camera_capture_one)
            else -> getString(R.string.camera_capture_many, n)
        }
        clearBtn.visibility = if (n > 0) Button.VISIBLE else Button.GONE
        uploadBtn.isEnabled = n > 0
        // A PDF only makes sense as a "combine" for the series; still allowed for one.
        pdfSwitch.isEnabled = n > 0
    }

    private fun onUpload() {
        if (pages.isEmpty()) return
        val server = ServerStore.baseUrl(this)
        if (server.isNullOrBlank()) {
            toast(getString(R.string.camera_capture_not_paired))
            return
        }
        val base = sanitize(filenameField.text.toString().ifBlank {
            SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())
        })
        val asPdf = pdfSwitch.isChecked
        setBusy(true)
        statusView.text = getString(R.string.camera_capture_uploading)

        thread {
            val api = ApiClient(applicationContext)
            val result: Int = try {
                if (asPdf) {
                    val pdf = buildPdf(pages)
                    api.uploadFile(pdf, "$base.pdf", "application/pdf")
                } else if (pages.size == 1) {
                    api.uploadFile(readScaledJpeg(pages[0]), "$base.jpg", "image/jpeg")
                } else {
                    // Upload each page as its own image; overall result = worst code.
                    var worst = 200
                    pages.forEachIndexed { i, f ->
                        val code = api.uploadFile(readScaledJpeg(f), "${base}-${i + 1}.jpg", "image/jpeg")
                        if (!(code in 200..299)) worst = code
                    }
                    worst
                }
            } catch (e: Throwable) {
                -1
            }
            runOnUiThread { onUploadDone(result) }
        }
    }

    private fun onUploadDone(code: Int) {
        setBusy(false)
        when {
            code in 200..299 -> {
                statusView.text = getString(R.string.camera_capture_done)
                toast(getString(R.string.camera_capture_done))
                clearPages()
            }
            code == 404 -> statusView.text = getString(R.string.camera_capture_no_endpoint)
            code == 401 || code == 403 -> statusView.text = getString(R.string.camera_capture_auth)
            else -> statusView.text = getString(R.string.camera_capture_failed, code)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        uploadBtn.isEnabled = !busy && pages.isNotEmpty()
        findViewById<Button>(R.id.cap_take).isEnabled = !busy
    }

    /** Decode [f] downsampled so its longer edge is ≤ [maxEdge], re-encoded as JPEG. */
    private fun readScaledJpeg(f: File): ByteArray {
        val bmp = decodeScaled(f) ?: return f.readBytes()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Assemble the captured pages into one multi-page PDF (one page per image). */
    private fun buildPdf(files: List<File>): ByteArray {
        val doc = PdfDocument()
        try {
            files.forEachIndexed { i, f ->
                val bmp = decodeScaled(f) ?: return@forEachIndexed
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun decodeScaled(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longer > 0 && longer / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    /** Keep it filesystem-safe: letters/digits/_-. only; collapse the rest to '_'. */
    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "upload" }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) pages.forEach { it.delete() }
    }
}
