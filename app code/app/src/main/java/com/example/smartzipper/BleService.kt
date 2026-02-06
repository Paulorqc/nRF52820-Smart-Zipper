package com.example.smartzipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleService : Service() {

    private val binder = LocalBinder()
    private var bleManager: BleManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Estado para monitoreo
    private var lastValue: String = ""
    private var zeroStartTime: Long = 0L  // Tiempo cuando empezó a ser "0"
    private var monitorJob: Job? = null
    private var alertSent = false

    // Estados expuestos
    private val _connectionState = MutableStateFlow(BleUiState())
    val connectionState: StateFlow<BleUiState> = _connectionState.asStateFlow()

    data class BleUiState(
        val status: String = "Disconnected",
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val hallValue: String = "-",
        val errorMessage: String? = null
    )

    companion object {
        private const val TAG = "BleService"
        const val CHANNEL_ID_SERVICE = "ble_service_channel"
        const val CHANNEL_ID_ALERT = "ble_alert_channel"
        const val NOTIFICATION_ID_SERVICE = 1
        const val NOTIFICATION_ID_ALERT = 2
        const val ALERT_TIMEOUT_MS = 360_000L // 6 minutes
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        bleManager = BleManager(applicationContext)
        setupBleCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification())
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Canal para el servicio en primer plano
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "SmartZipper BLE Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE connection active in background"
                setShowBadge(false)
            }

            // Canal para alertas - IMPORTANCE_HIGH para que aparezca como heads-up
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "SmartZipper Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Zipper open alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                enableLights(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)

            Log.d(TAG, "Canales de notificación creados")
        }
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notificación mínima requerida para el Foreground Service (silenciosa y discreta)
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("SmartZipper")
            .setContentText("BLE service active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateServiceNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_SERVICE, createServiceNotification())
    }

    private fun showAlertNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("⚠️ ALARM SmartZipper")
            .setContentText("ALARM! Zipper open for more than 6 minutes")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // Se cierra al tocarla
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_ALERT, notification)
        Log.d(TAG, ">>> Notificación de ALARMA mostrada <<<")
    }

    private fun hideAlertNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID_ALERT)
        Log.d(TAG, ">>> Notificación de ALARMA ocultada <<<")
    }

    private fun setupBleCallbacks() {
        bleManager?.onConnectionStateChanged = { state ->
            serviceScope.launch {
                when (state) {
                    BleManager.ConnectionState.DISCONNECTED -> {
                        _connectionState.value = BleUiState(
                            status = "Disconnected",
                            isConnected = false,
                            isConnecting = false,
                            hallValue = "-"
                        )
                        stopMonitoring()
                    }
                    BleManager.ConnectionState.CONNECTING -> {
                        _connectionState.value = _connectionState.value.copy(
                            status = "Connecting...",
                            isConnecting = true,
                            isConnected = false
                        )
                    }
                    BleManager.ConnectionState.CONNECTED -> {
                        _connectionState.value = _connectionState.value.copy(
                            status = "Connected",
                            isConnecting = true,
                            isConnected = false
                        )
                    }
                    BleManager.ConnectionState.DISCOVERING_SERVICES -> {
                        _connectionState.value = _connectionState.value.copy(
                            status = "Discovering services...",
                            isConnecting = true
                        )
                    }
                    BleManager.ConnectionState.READY -> {
                        _connectionState.value = _connectionState.value.copy(
                            status = "Ready",
                            isConnected = true,
                            isConnecting = false
                        )
                        startMonitoring()
                    }
                    BleManager.ConnectionState.ERROR -> {
                        _connectionState.value = _connectionState.value.copy(
                            status = "Error",
                            isConnected = false,
                            isConnecting = false,
                            errorMessage = "BLE connection error"
                        )
                        stopMonitoring()
                    }
                }
                updateServiceNotification()
            }
        }

        bleManager?.onHallValueReceived = { value ->
            serviceScope.launch {
                val trimmedValue = value.trim()
                Log.d(TAG, "Valor recibido: '$trimmedValue', anterior: '$lastValue', zeroStartTime: $zeroStartTime, alertSent: $alertSent")

                val valueChanged = trimmedValue != lastValue
                lastValue = trimmedValue

                when (trimmedValue) {
                    "0" -> {
                        // Si es "0" y no hemos iniciado el contador, iniciarlo
                        if (zeroStartTime == 0L) {
                            zeroStartTime = System.currentTimeMillis()
                            alertSent = false
                            Log.d(TAG, "Iniciando contador de 0. zeroStartTime: $zeroStartTime")
                        }
                    }
                    else -> {
                        // Si cambia a cualquier otro valor, resetear y ocultar alarma
                        if (valueChanged) {
                            zeroStartTime = 0L
                            alertSent = false
                            hideAlertNotification()  // Ocultar alarma al detectar imán
                            Log.d(TAG, "Sensor cambió a $trimmedValue, reseteando contador y ocultando alarma")
                        }
                    }
                }

                val displayValue = when (trimmedValue) {
                    "1" -> "Zipper Closed"
                    "0" -> "Zipper Open"
                    else -> value
                }

                _connectionState.value = _connectionState.value.copy(
                    hallValue = displayValue
                )

                // Verificar alerta inmediatamente después de recibir valor
                checkForAlert()
            }
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        alertSent = false

        // Si el valor actual ya es "0", iniciar el contador
        if (lastValue == "0") {
            zeroStartTime = System.currentTimeMillis()
        }

        Log.d(TAG, "Monitoreo iniciado. Valor actual: $lastValue")

        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(5000) // Verificar cada 5 segundos
                checkForAlert()
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun checkForAlert() {
        // Solo verificar si el valor es "0" y tenemos un tiempo de inicio
        if (lastValue == "0" && zeroStartTime > 0 && !alertSent) {
            val currentTime = System.currentTimeMillis()
            val timeInZero = currentTime - zeroStartTime

            Log.d(TAG, "Verificando alerta: valor=$lastValue, tiempo en 0=${timeInZero}ms, timeout=${ALERT_TIMEOUT_MS}ms")

            if (timeInZero >= ALERT_TIMEOUT_MS) {
                Log.d(TAG, "¡ENVIANDO ALERTA! Tiempo en 0: ${timeInZero}ms")
                sendAlertNotification()
                vibrate()
                alertSent = true
            }
        }
    }

    private fun sendAlertNotification() {
        Log.d(TAG, ">>> sendAlertNotification() LLAMADO <<<")
        showAlertNotification()
    }

    private fun vibrate() {
        Log.d(TAG, ">>> vibrate() LLAMADO <<<")
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            // Patrón más largo y fuerte: vibrar 1s, pausa 0.5s, vibrar 1s, pausa 0.5s, vibrar 1s
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Usar amplitud máxima para vibración más fuerte
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
            Log.d(TAG, ">>> Vibración ejecutada <<<")
        } catch (e: Exception) {
            Log.e(TAG, "Error al vibrar: ${e.message}", e)
        }
    }

    fun connect() {
        if (bleManager?.isBluetoothEnabled() != true) {
            _connectionState.value = _connectionState.value.copy(
                status = "Bluetooth disabled",
                errorMessage = "Please enable Bluetooth"
            )
            return
        }
        bleManager?.connect()
    }

    fun disconnect() {
        bleManager?.disconnect()
    }

    fun clearError() {
        _connectionState.value = _connectionState.value.copy(errorMessage = null)
    }

    // Función para probar notificación manualmente
    fun testAlert() {
        Log.d(TAG, ">>> testAlert() LLAMADO <<<")
        showAlertNotification()
        vibrate()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        serviceScope.cancel()
        bleManager?.close()
    }
}
