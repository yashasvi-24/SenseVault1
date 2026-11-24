package com.example.sensevault.data

data class SensorSample(
    val sensorId: Int,
    val sensorName: String,
    val sensorType: String,
    val timestampElapsedRealtimeNanos: Long,
    val timestampWallClockMillis: Long,
    val values: List<Float>,
    val accuracy: Int
)

data class SensorSnapshot(
    val sensorId: Int,
    val name: String,
    val type: String,
    val vendor: String,
    val values: List<Float>,
    val unitLabel: String,
    val timestampElapsedRealtimeNanos: Long?,
    val accuracy: Int?,
    val isFresh: Boolean
)

data class SensorRecording(
    val startedAt: Long,
    val stoppedAt: Long,
    val sensors: List<SensorSnapshot>,
    val samples: List<SensorSample>
)

data class VaultEntry(
    val id: String,
    val createdAt: Long,
    val note: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val sensors: List<SensorSnapshot>,
    val samples: List<SensorSample>
)
