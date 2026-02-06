package com.example.smartzipper

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleViewModel(application: Application) : AndroidViewModel(application) {

    private var bleService: BleService? = null
    private var isBound = false

    private val _connectionState = MutableStateFlow(BleService.BleUiState())
    val connectionState: StateFlow<BleService.BleUiState> = _connectionState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isBound = true

            // Observar el estado del servicio
            viewModelScope.launch {
                bleService?.connectionState?.collect { state ->
                    _connectionState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    init {
        startAndBindService()
    }

    private fun startAndBindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, BleService::class.java)

        // Iniciar como foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Vincular para comunicación
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun connect() {
        bleService?.connect()
    }

    fun disconnect() {
        bleService?.disconnect()
    }

    fun clearError() {
        bleService?.clearError()
    }

    fun testAlert() {
        bleService?.testAlert()
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
