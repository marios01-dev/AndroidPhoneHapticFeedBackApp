package com.example.smartwatchhapticsystem.controller;
import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * BluetoothConnectionManager: Handles Bluetooth  connection with Galaxy Watch 5 Pro.
 */
public class BluetoothConnectionManager {
    private static final String TAG = "BluetoothManager";

    private BluetoothSocket bluetoothSocket;

    private static final UUID APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Standard SPP UUID

    private Handler handler = new Handler(Looper.getMainLooper());

    private final Context context;
    private OnHeartRateReceived listener;

    public BluetoothConnectionManager(Context context) {
        this.context = context;
    }

    /**
     * **Check if Bluetooth permissions are granted (For Android 12+)**
     */
    public boolean hasBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) { // Android 12+
            boolean t =  ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG,t + " I AM IDK");
            return t;
        } else {
            return true; // No runtime permissions needed for Android <12
        }
    }

    /**
     * Attempts to find a paired Bluetooth smartwatch by checking bonded devices.
     *
     * @return A BluetoothDevice object representing the smartwatch, or null if not found or permissions are missing.
     */
    @SuppressLint("MissingPermission") // We manually check permissions below
    public BluetoothDevice getConnectedDevice() {
        // Check if Bluetooth connect permission is granted
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "❌ Missing BLUETOOTH_CONNECT permission!");
            return null;
        }

        try {
            // Get the default Bluetooth adapter
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            // Ensure Bluetooth is supported and currently enabled
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Log.e(TAG, "❌ Bluetooth is not enabled!");
                return null;
            }

            // Get a list of all bonded (paired) Bluetooth devices
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            // Search for a device whose name contains "watch" (case-insensitive)
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName() != null && device.getName().toLowerCase().contains("watch")) {
                    Log.d(TAG, "✅ Found paired smartwatch: " + device.getName());
                    return device; // Return the first matching smartwatch found
                }
            }

        } catch (SecurityException e) {
            // Catch and log if Bluetooth permission was revoked at runtime
            Log.e(TAG, "❌ SecurityException: Missing Bluetooth permissions!", e);
        }

        // If no suitable smartwatch was found, return null
        Log.e(TAG, "❌ No paired smartwatch found!");
        return null;
    }



    /**
     * Establishes a Classic Bluetooth connection to the specified smartwatch device,
     * sends the monitoring type command, and starts listening for incoming data.
     *
     * @param watchDevice       The paired Bluetooth smartwatch device to connect to.
     * @param heartRateListener A callback interface for receiving heart rate data or errors.
     * @param monitoringType    The type of monitoring to activate on the smartwatch (e.g., "HeartRate" or "SunAzimuth").
     */
    public void connectToWatch(BluetoothDevice watchDevice, OnHeartRateReceived heartRateListener, String monitoringType) {
        // Step 1: Validate the device
        if (watchDevice == null) {
            Log.e(TAG, "❌ Watch device is null!");
            return;
        }

        // Step 2: Check Bluetooth permission (only required on Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Missing BLUETOOTH_CONNECT permission!");
                if (heartRateListener != null) {
                    heartRateListener.onError("Missing Bluetooth permission");
                }
                return;
            }
        }

        // Step 3: Save the listener for later use (e.g., for error or data callbacks)
        this.listener = heartRateListener;

        // Step 4: Start a new background thread to avoid blocking the UI
        new Thread(() -> {
            try {
                // Step 5: Create a Bluetooth socket using the app's UUID
                bluetoothSocket = watchDevice.createRfcommSocketToServiceRecord(APP_UUID);

                // Step 6: Connect to the smartwatch
                bluetoothSocket.connect();
                Log.d(TAG, "✅ Connected to smartwatch via Classic Bluetooth!");

                // Step 7: Send the monitoring type to the watch (e.g., "Monitoring:HeartRate")
                OutputStream outputStream = bluetoothSocket.getOutputStream();
                String monitoringCommand = "Monitoring:" + monitoringType;
                outputStream.write((monitoringCommand + "\n").getBytes());  // Send command
                outputStream.flush();
                Log.d(TAG, "📤 Sent monitoring type: " + monitoringType);

                // Step 8: Start listening for incoming data from the watch
                readData(bluetoothSocket.getInputStream(), bluetoothSocket);

            } catch (SecurityException se) {
                // Handle missing permissions error (could happen at runtime if revoked)
                Log.e(TAG, "❌ SecurityException: Missing permission!", se);
                if (listener != null) {
                    handler.post(() -> listener.onError("SecurityException: Bluetooth permission missing"));
                }

            } catch (IOException e) {
                // Handle any IO-related errors during connection or communication
                Log.e(TAG, "❌ Connection failed: " + e.getMessage());
                if (listener != null) {
                    handler.post(() -> listener.onError("❌ Failed to connect: " + e.getMessage()));
                }
            }
        }).start();
    }


    /**
     * Continuously reads data from the smartwatch via Bluetooth input stream.
     * Parses messages formatted as key-value pairs (e.g., MonitoringType:HeartRate, Value:80, ...),
     * validates the data, attempts to recover missing identifiers if needed,
     * and dispatches the result to a listener callback.
     *
     * @param inputStream     The InputStream received from the connected smartwatch.
     * @param bluetoothSocket The active Bluetooth socket (used to extract device alias if needed).
     */
    private void readData(InputStream inputStream, BluetoothSocket bluetoothSocket) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                Log.d(TAG, "📥 Received: " + line);

                if (line.startsWith("MonitoringType:HeartRate")) {
                    handleHeartRateMessage(line, bluetoothSocket); // 👈 Extracted method
                } else {
                    Log.w(TAG, "⚠️ Unrecognized data format: " + line);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ Disconnected while reading: " + e.getMessage());
            if (listener != null) {
                handler.post(() -> listener.onError("❌ Connection lost."));
            }
        }
    }
    /**
     * Handles a Bluetooth message containing heart rate data.
     * Parses the message, attempts to recover unknown identifiers,
     * validates the heart rate value, and notifies the listener.
     */
    private void handleHeartRateMessage(String line, BluetoothSocket bluetoothSocket) {
        Map<String, String> dataMap = new HashMap<>();

        // Step 1: Parse key-value pairs
        String[] parts = line.split(",");
        for (String part : parts) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) {
                dataMap.put(kv[0].trim(), kv[1].trim());
            }
        }

        boolean unknownDetected = false;

        // Recover AndroidID if unknown
        if ("UnknownAndroid".equals(dataMap.get("AndroidID"))) {
            String fallback = Settings.Global.getString(context.getContentResolver(), "device_name");
            if (fallback != null && fallback.matches("^Android-\\d+$")) {
                dataMap.put("AndroidID", fallback.split("-")[1]);
                Log.d(TAG, "✅ Recovered AndroidID: " + fallback);
            } else {
                Log.w(TAG, "❌ Could not recover AndroidID.");
                unknownDetected = true;
            }
        }

        // Recover UserID and WatchID if unknown
        if ("UnknownWatch".equals(dataMap.get("SmartWatchID")) || "UnknownUser".equals(dataMap.get("UserID"))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BluetoothDevice remoteDevice = bluetoothSocket.getRemoteDevice();
                @SuppressLint("MissingPermission") String alias = remoteDevice.getAlias();

                Log.d(TAG, "🔍 Alias: " + alias);

                if (alias != null && alias.matches("^UserID-\\d+-SmartWatchID-\\d+$")) {
                    String[] tokens = alias.split("-");
                    dataMap.put("UserID", tokens[1]);
                    dataMap.put("SmartWatchID", tokens[3]);
                    Log.d(TAG, "✅ Recovered IDs: " + tokens[1] + ", " + tokens[3]);
                } else {
                    Log.w(TAG, "❌ Could not recover Watch/User IDs.");
                    unknownDetected = true;
                }
            } else {
                Log.w(TAG, "❌ Cannot retrieve alias below Android 11.");
                unknownDetected = true;
            }
        }

        if (unknownDetected) {
            if (listener != null) {
                handler.post(() -> listener.onError("❌ Unrecoverable Unknown fields."));
            }
            return;
        }

        // Validate and deliver heart rate value
        if (dataMap.containsKey("Value")) {
            try {
                Integer.parseInt(dataMap.get("Value")); // Validate numeric format

                if (listener != null) {
                    handler.post(() -> listener.onReceived(dataMap));
                }

            } catch (NumberFormatException e) {
                Log.e(TAG, "❌ Invalid heart rate: " + dataMap.get("Value"), e);
                if (listener != null) {
                    handler.post(() -> listener.onError("Invalid heart rate format"));
                }
            }
        } else {
            Log.w(TAG, "⚠️ Missing heart rate value in data: " + line);
        }
    }


    /**
     * **Disconnect from the watch**
     */
    public void disconnect() {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
                Log.d(TAG, "🔌 Bluetooth socket closed.");
            } catch (IOException e) {
                Log.e(TAG, "❌ Failed to close socket", e);
            }
        }
    }

    /**
     * Sends a vibration command to the smartwatch over a Bluetooth Serial Port Profile (SPP) connection.
     * The command includes intensity, pulse count, duration per pulse, and interval between pulses.
     *
     * Format: Vibrate:intensity,pulses,duration,interval
     *
     * Example: "Vibrate:3,5,250,500" means:
     *   → Intensity level 3
     *   → 5 vibration pulses
     *   → Each pulse lasts 250 ms
     *   → 500 ms pause between pulses
     *
     * @param intensity The vibration intensity level (e.g., 1 to 5).
     * @param pulses    The number of vibration pulses to send.
     * @param duration  The duration of each vibration pulse in milliseconds.
     * @param interval  The delay between pulses in milliseconds.
     */
    public void sendVibrationCommand(int intensity, int pulses, int duration, int interval) {
        // Step 1: Ensure that a Bluetooth connection is established
        if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
            Log.e(TAG, "❌ Not connected to watch via BluetoothSocket!");
            Log.e(TAG, "bluetoothSocket == null? " + (bluetoothSocket == null));  // Debug: null-check
            return;
        }

        // Step 2: Build the vibration command string using the expected format
        String command = "Vibrate:" + intensity + "," + pulses + "," + duration + "," + interval;

        try {
            // Step 3: Get the output stream from the Bluetooth socket and send the command
            OutputStream output = bluetoothSocket.getOutputStream();
            output.write((command + "\n").getBytes());  // '\n' marks the end of the command
            output.flush();

            Log.d(TAG, "📤 Sent vibration command: " + command);

        } catch (IOException e) {
            // Step 4: Handle I/O errors, such as a broken connection
            Log.e(TAG, "❌ Failed to send vibration command via SPP", e);
        }
    }



    /**
     * **Interface for heart rate response.**
     */
    public interface OnHeartRateReceived {
        void onReceived(Map<String, String> data);  // Key-value data map
        void onError(String errorMessage);
    }
    public BluetoothSocket getBluetoothSocket() {
        return bluetoothSocket;
    }

}
