package com.example.sensevault.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SelfieScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    var expressionResult by remember { mutableStateOf<String?>(null) }
    var analysisBusy by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (!granted) cameraMessage = "Camera permission was not granted. You can enable it in Android settings."
    }

    LaunchedEffect(Unit) {
        if (!cameraAllowed) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(cameraAllowed, lifecycleOwner, previewView) {
        var providerForCleanup: ProcessCameraProvider? = null
        var disposed = false
        if (cameraAllowed) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    if (disposed) {
                        provider.unbindAll()
                    } else {
                        providerForCleanup = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            capture
                        )
                        imageCapture = capture
                        cameraMessage = null
                    }
                } catch (error: Exception) {
                    cameraMessage = "Could not start the front camera: ${error.localizedMessage ?: "camera unavailable"}"
                }
            }, mainExecutor)
        }
        onDispose {
            disposed = true
            providerForCleanup?.unbindAll()
            imageCapture = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Selfie expression check", style = MaterialTheme.typography.titleLarge)
        Text(
            "Take a selfie to check for visible facial-expression signals. The estimate is not a reliable measure of your mood or mental health.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (cameraAllowed) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(22.dp))
            )
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Camera access is needed for a selfie check.")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow camera")
                    }
                }
            }
        }

        Button(
            onClick = {
                val capture = imageCapture
                if (capture == null) {
                    cameraMessage = "Camera is still starting. Please try again."
                } else {
                    analysisBusy = true
                    expressionResult = null
                    val photo = File(context.cacheDir, "sensevault_selfie_${System.currentTimeMillis()}.jpg")
                    val output = OutputFileOptions.Builder(photo).build()
                    capture.takePicture(output, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            analyzeExpression(context, photo) { result ->
                                expressionResult = result
                                analysisBusy = false
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            photo.delete()
                            cameraMessage = "Selfie capture failed: ${exception.localizedMessage ?: "unknown error"}"
                            analysisBusy = false
                        }
                    })
                }
            },
            enabled = cameraAllowed && imageCapture != null && !analysisBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (analysisBusy) "Checking expression…" else "Capture selfie and check")
        }

        cameraMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        expressionResult?.let { result ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("On-device result", style = MaterialTheme.typography.titleMedium)
                    Text(result, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Only a smile/eye-expression signal is checked. This cannot tell how you truly feel. The temporary selfie is deleted after analysis and is not added to your journal.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { expressionResult = null; cameraMessage = null },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear result")
        }
    }
}

private fun analyzeExpression(context: android.content.Context, photo: File, onComplete: (String) -> Unit) {
    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()
    val detector = FaceDetection.getClient(options)
    try {
        val input = InputImage.fromFilePath(context, Uri.fromFile(photo))
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val smile = face?.smilingProbability
                val eyes = listOfNotNull(face?.leftEyeOpenProbability, face?.rightEyeOpenProbability).average()
                val result = when {
                    face == null -> "No face was detected. Try a brighter, front-facing selfie."
                    smile == null -> "A face was detected, but the expression signal was unavailable."
                    smile >= 0.70f -> "A smiling expression may be present. Smile signal: ${formatPercent(smile)}."
                    smile >= 0.35f -> "The smile signal is unclear. Smile signal: ${formatPercent(smile)}."
                    else -> "No clear smile signal was detected. Smile signal: ${formatPercent(smile)}."
                } + if (eyes.isFinite()) " Eye-open signal: ${formatPercent(eyes.toFloat())}." else ""
                photo.delete()
                detector.close()
                onComplete(result)
            }
            .addOnFailureListener { error ->
                photo.delete()
                detector.close()
                onComplete("Expression check failed: ${error.localizedMessage ?: "please try again"}")
            }
    } catch (error: Exception) {
        photo.delete()
        detector.close()
        onComplete("Could not analyse the selfie: ${error.localizedMessage ?: "please try again"}")
    }
}

private fun formatPercent(value: Float): String =
    NumberFormat.getPercentInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(value.coerceIn(0f, 1f))
