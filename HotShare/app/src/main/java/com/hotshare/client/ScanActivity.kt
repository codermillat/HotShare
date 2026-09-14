package com.hotshare.client

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** QR scanner for hotshare://join payloads (CameraX + zxing). */
class ScanActivity : ComponentActivity() {

    private val reader = MultiFormatReader()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) showCamera() else finishWith(null) }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Scan the HotShare QR", style = MaterialTheme.typography.titleLarge)
                        Text("Point the camera at the QR shown on the host phone.")
                        Button(onClick = { finishWith(null) }) { Text("Cancel") }
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            showCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCamera() {
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx) },
                        modifier = Modifier.fillMaxSize()
                    ) { view ->
                        startCamera(view)
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                        Button(onClick = { finishWith(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    private fun startCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { img ->
                    if (done) { img.close(); return@setAnalyzer }
                    val text = try { decode(img) } catch (_: Exception) { null }
                    img.close()
                    if (text != null && text.startsWith("hotshare://")) {
                        done = true
                        runOnUiThread { finishWith(text) }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy): String? {
        val y = image.planes[0]
        val width = image.width
        val height = image.height
        val buffer = y.buffer
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        val data: ByteArray = if (pixelStride == 1 && rowStride == width) {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } else {
            // Compact rows (rowStride/pixelStride padding).
            val out = ByteArray(width * height)
            var pos = 0
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                if (pixelStride == 1) {
                    buffer.get(out, pos, width); pos += width
                } else {
                    for (col in 0 until width) {
                        out[pos++] = buffer.get(row * rowStride + col * pixelStride)
                    }
                }
            }
            out
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun finishWith(text: String?) {
        setResult(if (text != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().putExtra("qr", text ?: ""))
        finish()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}