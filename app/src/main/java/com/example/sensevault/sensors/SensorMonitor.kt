package com.example.sensevault.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock
import com.example.sensevault.data.SensorRecording
import com.example.sensevault.data.SensorSample
import com.example.sensevault.data.SensorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.IdentityHashMap

/** Records only readings received during the current monitoring session. */
class SensorMonitor(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val allSensors = manager.getSensorList(Sensor.TYPE_ALL)

    // Do not key readings by Sensor.id: some device HALs report duplicate/default IDs.
    // Assign each discovered Sensor object its own stable index for this monitor instance.
    private val sensorIndexes = IdentityHashMap<Sensor, Int>().apply {
        allSensors.forEachIndexed { index, sensor -> put(sensor, index) }
    }
    private val latestValues = ConcurrentHashMap<Int, SensorSample>()
    private val samples = mutableListOf<SensorSample>()
    private var startedAtWallClock = 0L
    private var startedAtElapsedNanos = 0L

    private val _readings = MutableStateFlow(makeSnapshot())
    val readings: StateFlow<List<SensorSnapshot>> = _readings
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring
    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (!_isMonitoring.value) return
            record(event.sensor, event.values, event.timestamp, -1)
            try { manager.requestTriggerSensor(this, event.sensor) } catch (_: Exception) { }
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (_isMonitoring.value) return false
        latestValues.clear()
        samples.clear()
        _sampleCount.value = 0
        startedAtWallClock = System.currentTimeMillis()
        startedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        _readings.value = makeSnapshot()
        _isMonitoring.value = true
        allSensors.forEach { sensor ->
            try {
                if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT ||
                    sensor.reportingMode == Sensor.REPORTING_MODE_SPECIAL_TRIGGER
                ) {
                    manager.requestTriggerSensor(triggerListener, sensor)
                } else {
                    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } catch (_: Exception) {
                // Keep monitoring the sensors the device allows us to access.
            }
        }
        return true
    }

    @Synchronized
    fun stop(): SensorRecording? {
        if (!_isMonitoring.value) return null
        _isMonitoring.value = false
        manager.unregisterListener(this)
        allSensors.filter { it.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT ||
            it.reportingMode == Sensor.REPORTING_MODE_SPECIAL_TRIGGER
        }.forEach { sensor ->
            try { manager.cancelTriggerSensor(triggerListener, sensor) } catch (_: Exception) { }
        }
        _readings.value = makeSnapshot()
        return SensorRecording(
            startedAt = startedAtWallClock,
            stoppedAt = System.currentTimeMillis(),
            sensors = _readings.value,
            samples = synchronized(samples) { samples.toList() }
        )
    }

    @Synchronized
    fun cancel() {
        if (_isMonitoring.value) {
            _isMonitoring.value = false
            manager.unregisterListener(this)
            allSensors.forEach { try { manager.cancelTriggerSensor(triggerListener, it) } catch (_: Exception) { } }
        }
        synchronized(samples) { samples.clear() }
        latestValues.clear()
        _sampleCount.value = 0
        _readings.value = makeSnapshot()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (_isMonitoring.value) record(event.sensor, event.values, event.timestamp, event.accuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun record(sensor: Sensor, values: FloatArray, timestampNanos: Long, accuracy: Int) {
        val index = sensorIndex(sensor)
        if (index < 0) return
        val sample = SensorSample(
            sensorId = sensor.id,
            sensorName = sensor.name,
            sensorType = sensor.stringType,
            timestampElapsedRealtimeNanos = timestampNanos,
            timestampWallClockMillis = System.currentTimeMillis(),
            values = values.toList(),
            accuracy = accuracy
        )
        latestValues[index] = sample
        synchronized(samples) {
            // Bound memory usage during very long sessions; retain the most recent 30,000 samples.
            if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
            samples.add(sample)
            _sampleCount.value = samples.size
        }
        _readings.value = makeSnapshot()
    }

    private fun sensorIndex(sensor: Sensor): Int {
        sensorIndexes[sensor]?.let { return it }
        // Defensive fallback in case Android supplies a different Sensor wrapper instance.
        return allSensors.indexOfFirst {
            it.type == sensor.type &&
                it.name == sensor.name &&
                it.vendor == sensor.vendor &&
                it.version == sensor.version &&
                it.id == sensor.id
        }
    }

    private fun makeSnapshot(): List<SensorSnapshot> = allSensors.mapIndexed { index, sensor ->
        val sample = latestValues[index]
        val fresh = sample != null && sample.timestampElapsedRealtimeNanos >= startedAtElapsedNanos && startedAtElapsedNanos != 0L
        SensorSnapshot(
            sensorId = sensor.id,
            name = sensor.name,
            type = sensor.stringType.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            vendor = sensor.vendor,
            values = if (fresh) sample!!.values else emptyList(),
            unitLabel = unitFor(sensor.type),
            timestampElapsedRealtimeNanos = if (fresh) sample!!.timestampElapsedRealtimeNanos else null,
            accuracy = if (fresh && sample!!.accuracy >= 0) sample.accuracy else null,
            isFresh = fresh
        )
    }

    private fun unitFor(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> "m/s²"
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_HEART_RATE -> "bpm"
        else -> "device units"
    }

    private companion object { const val MAX_SAMPLES = 30_000 }
}
