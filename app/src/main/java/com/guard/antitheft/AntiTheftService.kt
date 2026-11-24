package com.guard.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-reliability Anti-Theft Siren Background Service.
 * Features:
 *  - Motion detection (Flat surface tamper)
 *  - Pocket snatch detection (Proximity state shift)
 *  - Charger disconnect detector
 *  - 100% Volume sweeping siren synthesis (AudioTrack bypasses silent mode)
 *  - Flashlight strobe loop (CameraManager)
 *  - Biometric disarm protocol
 *  - 100% Free of Accessibility permissions!
 */
class AntiTheftService : Service(), SensorEventListener {

    companion object {
        const val TAG = "AntiTheftService"
        const val CHANNEL_ID = "antitheft_guard_channel"
        const val NOTIFICATION_ID = 7001

        const val ACTION_ARM = "com.guard.antitheft.ACTION_ARM"
        const val ACTION_DISARM = "com.guard.antitheft.ACTION_DISARM"
        const val ACTION_ALARM_TRIGGERED = "com.guard.antitheft.ACTION_ALARM_TRIGGERED"
        const val ACTION_STATUS_UPDATE = "com.guard.antitheft.ACTION_STATUS_UPDATE"

        const val EXTRA_IS_ARMED = "extra_is_armed"
        const val EXTRA_IS_ALARMING = "extra_is_alarming"

        var isServiceArmed = false
            private set
        var isAlarmActive = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null
    private lateinit var audioManager: AudioManager
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var armTimestamp: Long = 0
    private var baselineMagnitude: Float = 9.8f
    private var isCalibrated = false
    private var isPocketModeArmed = false

    private var audioTrack: AudioTrack? = null
    private var isSirenPlaying = false
    private var sirenThread: Thread? = null
    private var isStrobeActive = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED && isServiceArmed && !isAlarmActive) {
                val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("mode_charger", true)) {
                    Log.i(TAG, "Charger disconnected while armed! Sounding siren.")
                    triggerAlarm("Charger Disconnected!")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Torch not available: ${e.message}")
        }

        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        createNotificationChannel()
        Log.i(TAG, "AntiTheftService created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISARM -> {
                Log.i(TAG, "Disarm action received")
                disarmService()
                return START_NOT_STICKY
            }
            ACTION_ARM -> {
                armService()
            }
        }

        val notification = buildNotification(
            if (isAlarmActive) "⚠️ THEFT ALARM TRIGGERED!" else "Anti-Theft: ARMED & GUARDING",
            if (isAlarmActive) "Emergency Siren Active! Tap to Disarm." else "Sensors armed. Motion, pocket & charger protected."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        broadcastStatus()
        return START_STICKY
    }

    private fun armService() {
        isServiceArmed = true
        isAlarmActive = false
        isCalibrated = false
        armTimestamp = System.currentTimeMillis()

        // 5-second grace countdown before motion triggers alarm (allows user to set phone down)
        mainHandler.postDelayed({
            if (isServiceArmed && !isAlarmActive) {
                isCalibrated = true
                Log.i(TAG, "Anti-Theft armed and calibrated on surface (Baseline: $baselineMagnitude)")
            }
        }, 5000L)

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        updateNotification("Anti-Theft: ARMED (5s grace)", "Place phone down or slip into pocket...")
        broadcastStatus()
    }

    fun disarmService() {
        isServiceArmed = false
        isCalibrated = false
        stopAlarm()
        sensorManager.unregisterListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus()
        Log.i(TAG, "Anti-Theft completely disarmed.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isServiceArmed || isAlarmActive || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

                if (!isCalibrated) {
                    baselineMagnitude = mag
                    return
                }

                val delta = kotlin.math.abs(mag - baselineMagnitude)
                val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)

                // Mode 1: Motion detection ("Don't Touch My Phone")
                if (prefs.getBoolean("mode_motion", true) && delta > 1.85f) {
                    Log.i(TAG, "Unauthorized motion detected! Delta: $delta m/s²")
                    triggerAlarm("Motion Detected! Phone Tampered.")
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val dist = event.values[0]
                val maxRange = event.sensor.maximumRange
                val isNear = dist < 3.5f && dist < maxRange
                val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)

                if (!prefs.getBoolean("mode_pocket", true)) return

                if (isNear) {
                    isPocketModeArmed = true
                } else if (isPocketModeArmed && isCalibrated) {
                    // Pulled out of pocket/bag
                    Log.i(TAG, "Pocket snatch detected: proximity Far transition!")
                    triggerAlarm("Pocket Snatch Detected!")
                }
            }
        }
    }

    private fun triggerAlarm(reason: String) {
        if (isAlarmActive) return
        isAlarmActive = true

        Log.i(TAG, "SOUNDING EMERGENCY ANTI-THEFT ALARM: $reason")
        startSirenAudio()
        startFlashlightStrobe()
        startVibrationAlert()

        updateNotification("⚠️ THEFT ALARM: $reason", "SIREN ACTIVE! Open app to Disarm with Biometrics.")

        // Launch full-screen alarm activity
        val alarmIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_IS_ALARMING, true)
        }
        startActivity(alarmIntent)

        broadcastStatus()
    }

    private fun startSirenAudio() {
        if (isSirenPlaying) return
        isSirenPlaying = true

        // Maximize alarm stream volume
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (_: Exception) {}

        sirenThread = Thread {
            val sampleRate = 44100
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()

            audioTrack?.play()

            val buffer = ShortArray(1024)
            var phase = 0.0
            var currentFreq = 850.0
            var ascending = true

            while (isSirenPlaying) {
                // Sweeping police siren: 850Hz to 2400Hz
                if (ascending) {
                    currentFreq += 15.0
                    if (currentFreq >= 2400.0) ascending = false
                } else {
                    currentFreq -= 15.0
                    if (currentFreq <= 850.0) ascending = true
                }

                val phaseInc = 2.0 * PI * currentFreq / sampleRate
                for (i in buffer.indices) {
                    buffer[i] = (sin(phase) * 32767).toInt().toShort()
                    phase += phaseInc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }.also { it.start() }
    }

    private fun startFlashlightStrobe() {
        val cm = cameraManager ?: return
        val camId = cameraId ?: return
        isStrobeActive = true

        Thread {
            var state = false
            while (isStrobeActive) {
                try {
                    state = !state
                    cm.setTorchMode(camId, state)
                    Thread.sleep(70L) // Fast 14Hz emergency strobe
                } catch (e: Exception) {
                    break
                }
            }
            try {
                cm.setTorchMode(camId, false)
            } catch (_: Exception) {}
        }.start()
    }

    private fun startVibrationAlert() {
        val pattern = longArrayOf(0, 300, 150, 300, 150)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        isAlarmActive = false
        isSirenPlaying = false
        isStrobeActive = false
        sirenThread?.interrupt()
        sirenThread = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.cancel()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Anti-Theft Armed Protection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows live anti-theft armed status and disarm button"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disarmIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                putExtra("trigger_disarm_prompt", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_lock_power_off, "🛑 DISARM (BIOMETRIC)", disarmIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_ARMED, isServiceArmed)
            putExtra(EXTRA_IS_ALARMING, isAlarmActive)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        disarmService()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        Log.i(TAG, "AntiTheftService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
