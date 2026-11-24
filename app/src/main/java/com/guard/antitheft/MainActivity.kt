package com.guard.antitheft

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.guard.antitheft.databinding.ActivityMainBinding
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val TAG = "AntiTheftMainActivity"
        const val PREFS_NAME = "antitheft_prefs"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var proximity: Sensor? = null

    private var isCurrentlyArmed = false
    private var isCurrentlyAlarming = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AntiTheftService.ACTION_STATUS_UPDATE) {
                isCurrentlyArmed = intent.getBooleanExtra(AntiTheftService.EXTRA_IS_ARMED, false)
                isCurrentlyAlarming = intent.getBooleanExtra(AntiTheftService.EXTRA_IS_ALARMING, false)
                updateUIState()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val notifGranted = results[Manifest.permission.POST_NOTIFICATIONS] ?: true
        val cameraGranted = results[Manifest.permission.CAMERA] ?: true
        Log.i(TAG, "Permissions updated: notif=$notifGranted, camera=$cameraGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        requestNeededPermissions()
        loadPreferences()
        setupListeners()

        isCurrentlyArmed = AntiTheftService.isServiceArmed
        isCurrentlyAlarming = AntiTheftService.isAlarmActive
        updateUIState()

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val isAlarming = intent.getBooleanExtra(AntiTheftService.EXTRA_IS_ALARMING, false)
        val triggerDisarm = intent.getBooleanExtra("trigger_disarm_prompt", false)

        if (isAlarming || triggerDisarm || isCurrentlyAlarming) {
            promptBiometricAuthentication {
                disarmSystem()
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchMotion.isChecked = prefs.getBoolean("mode_motion", true)
        binding.switchPocket.isChecked = prefs.getBoolean("mode_pocket", true)
        binding.switchCharger.isChecked = prefs.getBoolean("mode_charger", true)
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.switchMotion.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mode_motion", isChecked).apply()
        }
        binding.switchPocket.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mode_pocket", isChecked).apply()
        }
        binding.switchCharger.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mode_charger", isChecked).apply()
        }

        binding.btnMasterAction.setOnClickListener {
            if (isCurrentlyAlarming || isCurrentlyArmed) {
                // Disarming requires biometric authentication
                promptBiometricAuthentication {
                    disarmSystem()
                }
            } else {
                armSystem()
            }
        }
    }

    private fun armSystem() {
        val intent = Intent(this, AntiTheftService::class.java).apply {
            action = AntiTheftService.ACTION_ARM
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isCurrentlyArmed = true
        isCurrentlyAlarming = false
        updateUIState()
        Toast.makeText(this, "Protection Armed! 5-second grace period started.", Toast.LENGTH_SHORT).show()
    }

    private fun disarmSystem() {
        val intent = Intent(this, AntiTheftService::class.java).apply {
            action = AntiTheftService.ACTION_DISARM
        }
        startService(intent)

        isCurrentlyArmed = false
        isCurrentlyAlarming = false
        updateUIState()
        Toast.makeText(this, "System Disarmed Successfully.", Toast.LENGTH_SHORT).show()
    }

    private fun promptBiometricAuthentication(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.i(TAG, "Biometric authentication verified")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.w(TAG, "Biometric error $errorCode: $errString")
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@MainActivity, "Biometrics not recognized!", Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Anti-Theft Disarm")
            .setSubtitle("Confirm your fingerprint or screen lock PIN to disarm")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun updateUIState() {
        when {
            isCurrentlyAlarming -> {
                binding.cardStatus.setStrokeColor(Color.parseColor("#EF4444"))
                binding.tvStatusIcon.text = "🚨"
                binding.tvStatusTitle.text = "ALARM ACTIVE!"
                binding.tvStatusTitle.setTextColor(Color.parseColor("#EF4444"))
                binding.tvStatusDetail.text = "THEFT DETECTED! Emergency siren and strobe flashing. Tap Disarm below to cancel."

                binding.btnMasterAction.text = "🛑 DISARM WITH FINGERPRINT"
                binding.btnMasterAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            }
            isCurrentlyArmed -> {
                binding.cardStatus.setStrokeColor(Color.parseColor("#10B981"))
                binding.tvStatusIcon.text = "🛡️"
                binding.tvStatusTitle.text = "SYSTEM ARMED & GUARDING"
                binding.tvStatusTitle.setTextColor(Color.parseColor("#10B981"))
                binding.tvStatusDetail.text = "Phone is armed. Moving it, pulling from pocket, or unplugging charger sounds the siren."

                binding.btnMasterAction.text = "DISARM SYSTEM"
                binding.btnMasterAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            }
            else -> {
                binding.cardStatus.setStrokeColor(Color.parseColor("#1F293D"))
                binding.tvStatusIcon.text = "🔒"
                binding.tvStatusTitle.text = "SYSTEM DISARMED"
                binding.tvStatusTitle.setTextColor(Color.parseColor("#94A3B8"))
                binding.tvStatusDetail.text = "Sensors idle. Arm to guard against snatching or movement."

                binding.btnMasterAction.text = "ARM PROTECTION"
                binding.btnMasterAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AntiTheftService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        proximity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        isCurrentlyArmed = AntiTheftService.isServiceArmed
        isCurrentlyAlarming = AntiTheftService.isAlarmActive
        updateUIState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt((x * x + y * y + z * z).toDouble())
            binding.tvLiveSensor.text = String.format("Live G-Force: %.2f m/s² | System: %s", mag, if (isCurrentlyArmed) "ARMED" else "STANDBY")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
