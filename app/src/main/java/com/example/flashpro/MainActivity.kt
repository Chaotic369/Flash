package com.example.flashpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class FlashPattern(val label: String) {
    STEADY("Steady"),
    BLINK("Blink"),
    SOS("SOS"),
    STROBE("Strobe"),
    HEARTBEAT("Heartbeat")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FlashScreen()
                }
            }
        }
    }
}

fun findFlashCameraId(cm: CameraManager): String? {
    for (id in cm.cameraIdList) {
        val ch = cm.getCameraCharacteristics(id)
        val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val facing = ch.get(CameraCharacteristics.LENS_FACING)
        if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) return id
    }
    return null
}

fun setTorch(cm: CameraManager, camId: String, on: Boolean, level: Int, maxLevel: Int) {
    try {
        if (on && Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
            cm.turnOnTorchWithStrengthLevel(camId, level.coerceIn(1, maxLevel))
        } else {
            cm.setTorchMode(camId, on)
        }
    } catch (e: Exception) {
        // ignore camera errors (e.g. camera in use by another app)
    }
}

@Composable
fun FlashScreen() {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val camId = remember { findFlashCameraId(cm) }
    val maxLevel = remember {
        if (camId != null && Build.VERSION.SDK_INT >= 33) {
            cm.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } else 1
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var isOn by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(FlashPattern.STEADY) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(0.5f) }

    val level = (1 + intensity * (maxLevel - 1)).roundToInt().coerceIn(1, maxLevel)

    LaunchedEffect(isOn, pattern, level, speed, camId, hasPermission) {
        if (camId == null || !hasPermission) return@LaunchedEffect
        if (!isOn) {
            setTorch(cm, camId, false, level, maxLevel)
            return@LaunchedEffect
        }
        val unit = (600 - speed * 540).toLong().coerceAtLeast(40L)
        when (pattern) {
            FlashPattern.STEADY -> setTorch(cm, camId, true, level, maxLevel)
            FlashPattern.BLINK -> while (true) {
                setTorch(cm, camId, true, level, maxLevel); delay(unit)
                setTorch(cm, camId, false, level, maxLevel); delay(unit)
            }
            FlashPattern.STROBE -> while (true) {
                setTorch(cm, camId, true, level, maxLevel); delay(40)
                setTorch(cm, camId, false, level, maxLevel); delay((unit / 3).coerceAtLeast(30L))
            }
            FlashPattern.SOS -> while (true) {
                repeat(3) {
                    setTorch(cm, camId, true, level, maxLevel); delay(unit)
                    setTorch(cm, camId, false, level, maxLevel); delay(unit)
                }
                repeat(3) {
                    setTorch(cm, camId, true, level, maxLevel); delay(unit * 3)
                    setTorch(cm, camId, false, level, maxLevel); delay(unit)
                }
                repeat(3) {
                    setTorch(cm, camId, true, level, maxLevel); delay(unit)
                    setTorch(cm, camId, false, level, maxLevel); delay(unit)
                }
                delay(unit * 6)
            }
            FlashPattern.HEARTBEAT -> while (true) {
                setTorch(cm, camId, true, level, maxLevel); delay(unit / 2)
                setTorch(cm, camId, false, level, maxLevel); delay(unit / 2)
                setTorch(cm, camId, true, level, maxLevel); delay(unit / 2)
                setTorch(cm, camId, false, level, maxLevel); delay(unit * 3)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (camId != null) {
                try { cm.setTorchMode(camId, false) } catch (e: Exception) { }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("FlashPro", style = MaterialTheme.typography.headlineLarge)

        if (camId == null) {
            Text("No flashlight found on this device.")
        } else if (!hasPermission) {
            Text("Camera permission is required to control the flash.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
        }

        Button(
            onClick = { isOn = !isOn },
            enabled = camId != null && hasPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            Text(if (isOn) "TURN OFF" else "TURN ON", style = MaterialTheme.typography.titleLarge)
        }

        Text("Pattern", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlashPattern.values().take(3).forEach { p ->
                FilterChip(
                    selected = pattern == p,
                    onClick = { pattern = p },
                    label = { Text(p.label) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlashPattern.values().drop(3).forEach { p ->
                FilterChip(
                    selected = pattern == p,
                    onClick = { pattern = p },
                    label = { Text(p.label) }
                )
            }
        }

        Text(
            if (maxLevel > 1) "Intensity: level $level of $maxLevel"
            else "Intensity: not supported on this device",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = intensity,
            onValueChange = { intensity = it },
            enabled = maxLevel > 1
        )

        if (pattern != FlashPattern.STEADY) {
            Text("Speed", style = MaterialTheme.typography.titleMedium)
            Slider(value = speed, onValueChange = { speed = it })
        }
    }
}
