package com.peri

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class BleDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

data class BleUiState(
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val batteryLevel: Int = 0,
    val deviceName: String = "",
    val errorMessage: String = "",
    val discoveredDevices: List<BleDevice> = emptyList()
)

class BleViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val SCAN_PERIOD: Long = 10000 // 10 seconds
    private val CONNECTION_TIMEOUT: Long = 10000 // 10 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: android.bluetooth.BluetoothAdapter? = null
    private val connectionTimeoutRunnable = Runnable {
        viewModelScope.launch {
            if (!_uiState.value.isConnected) {
                disconnect()
                _uiState.update { state ->
                    state.copy(errorMessage = "Connection timeout. Try again or select a different device.")
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = gatt.device?.name ?: "Unknown Device"
            
            // Remove timeout since we got a connection response
            handler.removeCallbacks(connectionTimeoutRunnable)
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    // Add a small delay before discovering services to allow connection to stabilize
                    handler.postDelayed({
                        try {
                            gatt.discoverServices()
                        } catch (e: Exception) {
                            viewModelScope.launch {
                                _uiState.update { state ->
                                    state.copy(errorMessage = "Failed to discover services: ${e.message}")
                                }
                            }
                        }
                    }, 600) // 600ms delay often helps with audio devices
                    
                    viewModelScope.launch {
                        _uiState.update { state ->
                            state.copy(
                                isConnected = true,
                                deviceName = deviceName
                            )
                        }
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    viewModelScope.launch {
                        _uiState.update { state ->
                            state.copy(
                                isConnected = false,
                                deviceName = ""
                            )
                        }
                    }
                    gatt.close()
                }
            } else { 
                // Handle specific error cases for better user feedback
                val errorMessage = when (status) {
                    8 -> "Connection rejected (error 8) - Make sure the device is in pairing mode"
                    19 -> "Connection timed out (error 19) - Device may be out of range"
                    22 -> "Connection failed (error 22) - Try restarting the Bluetooth device"
                    133 -> "Connection failed (error 133) - Device might be connected to another phone"
                    else -> "Connection error: status $status"
                }
                
                viewModelScope.launch {
                    _uiState.update { state ->
                        state.copy(
                            isConnected = false,
                            errorMessage = errorMessage
                        )
                    }
                }
                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                if (batteryService != null) {
                    val batteryChar = batteryService.getCharacteristic(
                        BATTERY_LEVEL_CHARACTERISTIC_UUID)
                    if (batteryChar != null) {
                        gatt.readCharacteristic(batteryChar)
                    }
                }
            }
        }

        @SuppressLint("NewApi")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, 
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    BATTERY_LEVEL_CHARACTERISTIC_UUID -> {
                        val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            characteristic.value?.get(0)?.toInt() ?: 0
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: 0
                        }
                        
                        viewModelScope.launch {
                            _uiState.update { state -> 
                                state.copy(batteryLevel = value) 
                            }
                        }
                    }
                }
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Many audio devices like Boat speakers might have name in scan record even if device.name is null
            val scanRecord = result.scanRecord
            val deviceName = device.name 
                ?: scanRecord?.deviceName
                ?: if (device.address.startsWith("F0:") || device.address.contains(":EB:")) "Possible Boat Device" 
                else "Unknown Device"
            val deviceAddress = device.address
            val rssi = result.rssi
            
            // Include all devices - some audio devices might not have visible names
            val bleDevice = BleDevice(device, deviceName, deviceAddress, rssi)
            
            viewModelScope.launch {
                val currentDevices = _uiState.value.discoveredDevices
                if (!currentDevices.any { it.address == deviceAddress }) {
                    _uiState.update { state ->
                        state.copy(
                            discoveredDevices = currentDevices + bleDevice
                        )
                    }
                }
           }
        }

        override fun onScanFailed(errorCode: Int) {
            viewModelScope.launch {
                stopScanning()
                _uiState.update { state ->
                    state.copy(
                        errorMessage = "Scan failed with error: $errorCode",
                        isScanning = false
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context) {
        if (isScanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            
        if (scanner == null) {
            _uiState.update { state -> 
                state.copy(errorMessage = "Bluetooth LE scanner not available") 
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { state -> 
                state.copy(
                    isScanning = true, 
                    discoveredDevices = emptyList(),
                    errorMessage = ""
                ) 
            }
        }
        
        // Stop scanning after a predefined scan period
        handler.postDelayed({ stopScanning() }, SCAN_PERIOD)
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        // Removing service UUID filter to discover more devices
        // Some audio speakers like Boat might not advertise standard service UUIDs
       
        try {
            isScanning = true
            scanner.startScan(null, scanSettings, leScanCallback)
        } catch (e: Exception) {
            viewModelScope.launch {
                _uiState.update { state -> 
                    state.copy(
                        errorMessage = "Error starting scan: ${e.message}",
                        isScanning = false
                    ) 
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
           
           if (scanner != null) {
               scanner.stopScan(leScanCallback)
           }
        } catch (e: Exception) {
            // Handle potential errors when stopping scanning
            viewModelScope.launch {
                _uiState.update { state -> 
                    state.copy(errorMessage = "Error stopping scan: ${e.message}") 
                }
            }
        } finally {
            isScanning = false
            viewModelScope.launch {
                _uiState.update { state -> state.copy(isScanning = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        // Disconnect if already connected to clean up resources
       bluetoothGatt?.close()
        // Remove any pending timeouts
        handler.removeCallbacks(connectionTimeoutRunnable)
       
       viewModelScope.launch {
           _uiState.update { state ->
               state.copy(
                    errorMessage = "",
                    isConnected = false
               )
           }
       }
       
        // Set a timeout for the connection attempt
        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT)
        
       // Use TRANSPORT_LE parameter for better connection to modern audio devices
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           bluetoothGatt = device.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
       } else {
           bluetoothGatt = device.connectGatt(null, false, gattCallback)
       }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        handler.removeCallbacks(connectionTimeoutRunnable)
       bluetoothGatt?.disconnect()
       bluetoothGatt?.close()
       bluetoothGatt = null
        
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isConnected = false,
                    deviceName = "",
                    batteryLevel = 0
                )
            }
        }
    }

    fun updateError(message: String) {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(errorMessage = message) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
        super.onCleared()
        bluetoothGatt?.close()
    }

    companion object {
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
        
        // Common UUIDs for audio services
        private val A2DP_SINK_SERVICE_UUID = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB")
        private val AVRCP_SERVICE_UUID = UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB")
        private val AUDIO_SOURCE_SERVICE_UUID = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB")
        private val HANDSFREE_SERVICE_UUID = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB")
    }
}