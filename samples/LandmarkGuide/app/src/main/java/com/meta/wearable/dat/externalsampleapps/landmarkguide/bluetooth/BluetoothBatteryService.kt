/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BluetoothBatteryService - Reads battery level from connected Bluetooth devices
 * 
 * Uses Android's ACTION_BATTERY_LEVEL_CHANGED broadcast to get battery updates
 * for connected devices that support battery reporting (like Ray-Ban Meta glasses).
 */
class BluetoothBatteryService(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothBatteryService"
        
        // Action for battery level changed (hidden API, but works)
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
    
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    private var isRegistered = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    
                    if (level >= 0 && device != null) {
                        val deviceName = try {
                            if (hasBluetoothPermission()) device.name else "Unknown"
                        } catch (e: SecurityException) {
                            "Unknown"
                        }
                        Log.d(TAG, "Battery level changed: $deviceName -> $level%")
                        _batteryLevel.value = level
                        _connectedDeviceName.value = deviceName
                    }
                }
                
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        val deviceName = try {
                            if (hasBluetoothPermission()) it.name else "Unknown"
                        } catch (e: SecurityException) {
                            "Unknown"
                        }
                        Log.d(TAG, "Device connected: $deviceName")
                        _connectedDeviceName.value = deviceName
                    }
                }
                
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        val deviceName = try {
                            if (hasBluetoothPermission()) it.name else "Unknown"
                        } catch (e: SecurityException) {
                            "Unknown"
                        }
                        Log.d(TAG, "Device disconnected: $deviceName")
                        if (_connectedDeviceName.value == deviceName) {
                            _batteryLevel.value = null
                            _connectedDeviceName.value = null
                        }
                    }
                }
            }
        }
    }
    
    fun start() {
        if (isRegistered) return
        
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        
        try {
            // Must use RECEIVER_EXPORTED for system broadcasts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(batteryReceiver, filter)
            }
            isRegistered = true
            Log.d(TAG, "Battery receiver registered (EXPORTED)")
            
            // Get initial battery level from already connected devices
            queryConnectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }
    }
    
    fun stop() {
        if (!isRegistered) return
        
        try {
            context.unregisterReceiver(batteryReceiver)
            isRegistered = false
            Log.d(TAG, "Battery receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun queryConnectedDevices() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Missing Bluetooth permission")
            return
        }
        
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            
            adapter?.bondedDevices?.forEach { device ->
                Log.d(TAG, "Bonded device: ${device.name}")
                // Ray-Ban Meta glasses usually contain "Ray-Ban" in name
                if (device.name?.contains("Ray-Ban", ignoreCase = true) == true ||
                    device.name?.contains("Meta", ignoreCase = true) == true) {
                    _connectedDeviceName.value = device.name
                    Log.d(TAG, "Found Ray-Ban/Meta device: ${device.name}")
                    
                    // Try to get battery level using hidden API
                    val battery = getBatteryLevel(device)
                    if (battery >= 0) {
                        Log.d(TAG, "Battery level for ${device.name}: $battery%")
                        _batteryLevel.value = battery
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query connected devices", e)
        }
    }
    
    /**
     * Manually refresh battery level - call this when user taps the battery box
     */
    fun refreshBattery() {
        Log.d(TAG, "Manual battery refresh requested")
        queryConnectedDevices()
    }
    
    /**
     * Get battery level using BluetoothDevice.getBatteryLevel() hidden API
     * Returns -1 if not available
     */
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun getBatteryLevel(device: BluetoothDevice): Int {
        return try {
            // BluetoothDevice.getBatteryLevel() is a hidden API but accessible via reflection
            val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            method.invoke(device) as? Int ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "getBatteryLevel not available: ${e.message}")
            -1
        }
    }
    
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
