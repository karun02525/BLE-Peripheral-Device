package com.peri

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


@Composable
fun BleScreen(
    viewModel: BleViewModel,
    paddingValues: PaddingValues,
    onScanClick: () -> Unit,
    onShowError: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage.isNotEmpty()) {
            onShowError(uiState.errorMessage)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (uiState.isConnected) {
            // Connected device info
            DeviceConnectedCard(
                deviceName = uiState.deviceName,
                batteryLevel = uiState.batteryLevel,
                onDisconnect = { viewModel.disconnect() }
            )
        } else {
            // Scan button and device list
            Button(
                onClick = onScanClick,
                enabled = !uiState.isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Scan"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isScanning) "Scanning..." else "Scan for Devices")
            }
            
            if (uiState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            if (uiState.discoveredDevices.isNotEmpty()) {
                Text(
                    text = "Available Devices",
                    style = MaterialTheme.typography.titleMedium
                )
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(uiState.discoveredDevices) { device ->
                        DeviceListItem(
                            device = device,
                            onClick = { viewModel.connectToDevice(device.device) }
                        )
                    }
                }
            } else if (!uiState.isScanning) {
                Text(
                    text = "No devices found. Try scanning again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DeviceConnectedCard(
    deviceName: String,
    batteryLevel: Int,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Connected device"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Battery level"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Battery Level: $batteryLevel%")
                
                // Battery indicator
                LinearProgressIndicator(
                    progress = { batteryLevel / 100f },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(8.dp)
                        .weight(1f)
                )
            }
            
            Button(
                onClick = onDisconnect,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Disconnect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListItem(
    device: BleDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}