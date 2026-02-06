package com.example.smartzipper

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Manager class for BLE operations with nRF52820 device
 * Device Address: E3:D4:1E:79:7C:16
 * Service UUID: 0xFF03
 * Characteristic UUID: 0xFF04 (Hall sensor value: "0" or "1")
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        const val DEVICE_ADDRESS = "E3:D4:1E:79:7C:16"
        val SERVICE_UUID: UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FF04-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var hallCharacteristic: BluetoothGattCharacteristic? = null

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onHallValueReceived: ((String) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        READY,
        ERROR
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionStateChanged?.invoke(ConnectionState.DISCOVERING_SERVICES)
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(SERVICE_UUID)
                hallCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                if (hallCharacteristic != null) {
                    // Enable notifications
                    enableNotifications(gatt)
                } else {
                    onConnectionStateChanged?.invoke(ConnectionState.ERROR)
                }
            } else {
                onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHARACTERISTIC_UUID) {
                val hallValue = String(value)
                onHallValueReceived?.invoke(hallValue)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic?.uuid == CHARACTERISTIC_UUID) {
                    val hallValue = characteristic?.value?.let { String(it) } ?: ""
                    onHallValueReceived?.invoke(hallValue)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val hallValue = String(value)
                onHallValueReceived?.invoke(hallValue)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                    val hallValue = characteristic?.value?.let { String(it) } ?: ""
                    onHallValueReceived?.invoke(hallValue)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onConnectionStateChanged?.invoke(ConnectionState.READY)
            } else {
                onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun connect() {
        if (bluetoothAdapter == null) {
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            return
        }

        if (!isBluetoothEnabled()) {
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            return
        }

        onConnectionStateChanged?.invoke(ConnectionState.CONNECTING)

        try {
            val device = bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    fun readHallValue() {
        hallCharacteristic?.let { characteristic ->
            bluetoothGatt?.readCharacteristic(characteristic)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt?) {
        hallCharacteristic?.let { characteristic ->
            gatt?.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt?.writeDescriptor(descriptor)
                }
            } else {
                // If no CCCD, just mark as ready
                onConnectionStateChanged?.invoke(ConnectionState.READY)
            }
        }
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        hallCharacteristic = null
    }

    fun close() {
        disconnect()
        cleanup()
    }
}
