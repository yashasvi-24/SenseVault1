package com.example.sensevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sensevault.auth.PinAuth
import com.example.sensevault.camera.SelfieScreen
import com.example.sensevault.data.SecureVault
import com.example.sensevault.data.SensorSnapshot
import com.example.sensevault.data.SensorRecording
import com.example.sensevault.data.VaultEntry
import com.example.sensevault.sensors.SensorMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab(val label: String) {
    SENSORS("Sensors"), ENTRIES("Vault"), SELFIE("Selfie"), SETTINGS("Settings")
}

@Composable
fun AppRoot(sensorMonitor: SensorMonitor) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pinAuth = remember { PinAuth(context) }
    val vault = remember { SecureVault(context) }
    var isConfigured by remember { mutableStateOf(pinAuth.isConfigured()) }
    var authenticated by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) authenticated = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            if (!authenticated) {
                AuthorizationScreen(
                    isConfigured = isConfigured,
                    onCreatePin = { pin ->
                        val saved = pinAuth.createPin(pin)
                        if (saved) {
                            isConfigured = true
                            authenticated = true
                        }
                        saved
                    },
                    onLogin = { pin ->
                        val valid = pinAuth.verifyPin(pin)
                        if (valid) authenticated = true
                        valid
                    }
                )
            } else {
                MainScreen(
                    sensorMonitor = sensorMonitor,
                    vault = vault,
                    pinAuth = pinAuth,
                    onLock = { authenticated = false }
                )
            }
        }
    }
}

@Composable
private fun AuthorizationScreen(
    isConfigured: Boolean,
    onCreatePin: (String) -> Boolean,
    onLogin: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("SENSEVAULT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            if (isConfigured) "Your private sensor journal" else "Create your private vault",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isConfigured) "Enter your six-digit PIN to unlock your saved entries."
            else "Set a six-digit PIN. It will protect access to your local journal.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(26.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6); message = null },
            label = { Text("6-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!isConfigured) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it.filter(Char::isDigit).take(6); message = null },
                label = { Text("Confirm PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length != 6 -> message = "Enter exactly six digits."
                    !isConfigured && confirmation != pin -> message = "The PIN entries do not match."
                    isConfigured && !onLogin(pin) -> message = "Incorrect PIN or temporarily locked. Five failed attempts trigger a 30-second pause."
                    !isConfigured && !onCreatePin(pin) -> message = "Could not create the PIN."
                    else -> message = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isConfigured) "Unlock vault" else "Create PIN and continue")
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Your PIN is stored as a salted password hash. Sensor entries are encrypted on this device; there is no cloud account in this version.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MainScreen(
    sensorMonitor: SensorMonitor,
    vault: SecureVault,
    pinAuth: PinAuth,
    onLock: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppTab.SENSORS) }
    val scope = rememberCoroutineScope()
    var toastMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(sensorMonitor) {
        onDispose { sensorMonitor.cancel() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SenseVault", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Your private device journal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onLock) { Text("Lock") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppTab.entries.forEach { tab ->
                if (tab == selectedTab) {
                    Button(onClick = { selectedTab = tab }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab = tab }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            AppTab.SENSORS -> SensorsPanel(
                sensorMonitor = sensorMonitor,
                onSave = { note, recording ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { vault.save(note, recording) }
                            toastMessage = "Encrypted monitoring session saved to your vault."
                        } catch (error: Exception) {
                            toastMessage = "Could not save session: ${error.localizedMessage ?: "unknown error"}"
                        }
                    }
                },
                statusMessage = toastMessage,
                clearStatus = { toastMessage = null }
            )
            AppTab.ENTRIES -> EntriesPanel(vault)
            AppTab.SELFIE -> SelfieScreen()
            AppTab.SETTINGS -> SettingsPanel(pinAuth, onLock)
        }
    }
}

@Composable
private fun SensorsPanel(
    sensorMonitor: SensorMonitor,
    onSave: (String, SensorRecording) -> Unit,
    statusMessage: String?,
    clearStatus: () -> Unit
) {
    val readings by sensorMonitor.readings.collectAsState()
    val isMonitoring by sensorMonitor.isMonitoring.collectAsState()
    val sampleCount by sensorMonitor.sampleCount.collectAsState()
    var note by remember { mutableStateOf("") }
    var stoppedRecording by remember { mutableStateOf<SensorRecording?>(null) }
    val freshCount = readings.count { it.isFresh }
    val durationText = stoppedRecording?.let { "${((it.stoppedAt - it.startedAt) / 1000).coerceAtLeast(0)} seconds" }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Sensor monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${readings.size} sensors detected · $freshCount with fresh readings", style = MaterialTheme.typography.bodyLarge)
                Text(if (isMonitoring) "Monitoring is running. Every incoming event is timestamped and collected." else "Monitoring is stopped. Start a session to collect new readings; old values are cleared.", style = MaterialTheme.typography.bodySmall)
                Text("Samples collected: $sampleCount", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!isMonitoring) {
            Button(onClick = { stoppedRecording = null; sensorMonitor.start() }, modifier = Modifier.fillMaxWidth()) {
                Text("Start monitoring")
            }
        } else {
            Button(onClick = { stoppedRecording = sensorMonitor.stop() }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop monitoring")
            }
        }
        stoppedRecording?.let { recording ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session ready to save", style = MaterialTheme.typography.titleMedium)
                    Text("Duration: $durationText · Samples: ${recording.samples.size}")
                    Text("Fresh sensors: ${recording.sensors.count { it.isFresh }} / ${recording.sensors.size}")
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Private note (optional)") },
                        minLines = 2,
                        maxLines = 4
                    )
                    Button(
                        onClick = {
                            onSave(note, recording)
                            note = ""
                            stoppedRecording = null
                        },
                        enabled = recording.samples.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Encrypt and save session") }
                    if (recording.samples.isEmpty()) Text("No sensor events were received during this session, so there is nothing to save.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        statusMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = clearStatus) { Text("Dismiss") }
                }
            }
        }
        Text("Detected sensors", style = MaterialTheme.typography.titleLarge)
        readings.forEach { sensor -> SensorCard(sensor) }
        if (readings.isEmpty()) Text("No sensors were reported by Android on this device.")
    }
}

@Composable
private fun SensorCard(sensor: SensorSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sensor.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sensor.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Vendor: ${sensor.vendor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val valueText = if (!sensor.isFresh || sensor.values.isEmpty()) {
                "No fresh reading in this monitoring session"
            } else {
                sensor.values.mapIndexed { index, value ->
                    "${axisName(index)} ${"%.4f".format(Locale.US, value)} ${sensor.unitLabel}"
                }.joinToString("   ·   ")
            }
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
            if (sensor.isFresh) Text("Fresh in this session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun axisName(index: Int): String = when (index) {
    0 -> "X"
    1 -> "Y"
    2 -> "Z"
    else -> "V${index + 1}"
}

@Composable
private fun EntriesPanel(vault: SecureVault) {
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshCount) {
        loading = true
        entries = withContext(Dispatchers.IO) { vault.loadAll() }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Encrypted entries", style = MaterialTheme.typography.titleLarge)
                Text("${entries.size} saved snapshots", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { refreshCount++ }) { Text("Refresh") }
        }
        when {
            loading -> Text("Loading your vault…")
            entries.isEmpty() -> Text("Your vault is empty. Capture a sensor snapshot to create your first entry.")
            else -> entries.forEach { entry ->
                EntryCard(
                    entry = entry,
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            vault.delete(entry.id)
                            withContext(Dispatchers.Main) { refreshCount++ }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EntryCard(entry: VaultEntry, onDelete: () -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(formatDate(entry.createdAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val durationSeconds = ((entry.stoppedAt - entry.startedAt) / 1000).coerceAtLeast(0)
            Text("${entry.sensors.count { it.isFresh }} sensors with readings · ${entry.samples.size} samples · ${durationSeconds}s monitoring", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide readings" else "View readings") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            if (expanded) {
                entry.sensors.forEach { sensor ->
                    val sensorSamples = entry.samples.filter { it.sensorId == sensor.sensorId }
                    Text(sensor.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text("${sensorSamples.size} samples collected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    val values = if (sensor.values.isEmpty()) "No fresh reading recorded" else sensor.values.joinToString(", ") { "%.4f".format(Locale.US, it) } + " ${sensor.unitLabel}"
                    Text("Latest: $values", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(pinAuth: PinAuth, onLock: () -> Unit) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Security settings", style = MaterialTheme.typography.titleLarge)
        Text("Change your local six-digit PIN. Saved sensor records remain encrypted with the Android Keystore key.", style = MaterialTheme.typography.bodyMedium)
        PinField("Current PIN", oldPin) { oldPin = it }
        PinField("New six-digit PIN", newPin) { newPin = it }
        PinField("Confirm new PIN", confirmPin) { confirmPin = it }
        Button(
            onClick = {
                message = when {
                    !pinAuth.verifyPin(oldPin) -> "Current PIN is incorrect."
                    newPin.length != 6 || !newPin.all(Char::isDigit) -> "The new PIN must contain six digits."
                    newPin != confirmPin -> "The new PIN entries do not match."
                    pinAuth.createPin(newPin) -> "PIN updated successfully."
                    else -> "Could not update the PIN."
                }
                oldPin = ""; newPin = ""; confirmPin = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Update PIN") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) { Text("Lock SenseVault now") }
        Text(
            "Privacy: sensor readings and notes stay in the app's private files. Camera frames are processed on-device and temporary selfie files are deleted after analysis. Android sensor availability and update frequency vary by phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(timestamp))
