package com.example.smartwatchhapticsystem.controller;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.example.smartwatchhapticsystem.model.LocationData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * NetworkController: Handles communication with Node-RED
 */
public class NetworkController {
    private final NodeRedApiForSunData api;
    private final RequestQueue requestQueue;
    private final String myIp = "https://b563cf61ebcd.ngrok-free.app";
    private final String NODE_RED_CONFIG_URL =  myIp + "/get-monitoring-config";
    private final String NODE_RED_POST_URL = myIp + "/heartRate";
    private final BluetoothConnectionManager bluetoothConnectionManager;
    private Context context;

    /**
     * Constructor: Initialize Retrofit and Volley
     */

    public NetworkController(Context context, BluetoothConnectionManager bluetoothManager) {
        this.context = context;
        this.bluetoothConnectionManager = bluetoothManager;

        // Initialize Retrofit
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(myIp + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(NodeRedApiForSunData.class);

        // Initialize Volley RequestQueue
        requestQueue = Volley.newRequestQueue(context);

        // Initialize Bluetooth Manager for Watch Communication
    }


    /**
     * Get Monitoring Type ( SunAzimuth or HeartRate) from Node-RED.
     */
    /**
     * Makes an HTTP GET request to the Node-RED backend to fetch the current monitoring type.
     * The expected response format is a JSON object containing a key "monitoringType"
     * with a value like "HeartRate" or "SunAzimuth".
     *
     * If the type is successfully retrieved, the result is passed to the provided listener.
     * In case of errors (network, JSON, unknown value), an appropriate error is reported via the listener.
     *
     * @param listener A callback interface to receive either the valid monitoring type or an error message.
     */
    public void getMonitoringType(OnMonitoringTypeReceived listener) {

        // Step 1: Create a GET request using Volley to the Node-RED configuration endpoint
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.GET,                 // HTTP GET method
                NODE_RED_CONFIG_URL,               // The URL to fetch monitoring type from
                null,                              // No body required for GET request
                new Response.Listener<JSONObject>() {
                    // Called when the server responds with a valid JSON
                    @Override
                    public void onResponse(JSONObject response) {
                        try {
                            // Step 2: Log the full raw JSON response for debugging
                            Log.d("NetworkController", "✅ Full Response: " + response.toString());

                            // Step 3: Extract the "monitoringType" value from the JSON
                            String monitoringType = response.optString("monitoringType", "Unknown");
                            Log.d("NetworkController", "✅ Monitoring Type: " + monitoringType);

                            // Step 4: Check if a valid type was returned
                            if (monitoringType.equals("Unknown")) {
                                listener.onError("❌ Unknown monitoring type!");
                            } else {
                                listener.onReceived(monitoringType); // Success callback
                            }

                        } catch (Exception e) {
                            // Step 5: Catch any exceptions during parsing
                            listener.onError("❌ JSON Parsing Error: " + e.getMessage());
                            Log.e("NetworkController", "❌ JSON Parsing Error: " + e.getMessage());
                        }
                    }
                },
                new Response.ErrorListener() {
                    // Called when the request fails due to network error, timeout, etc.
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        listener.onError("❌ Volley Error: " + error.toString());
                        Log.e("NetworkController", "❌ Volley Error: " + error.toString());

                        // Optionally log the HTTP status code if available
                        if (error.networkResponse != null) {
                            Log.e("NetworkController", "❌ HTTP Status Code: " + error.networkResponse.statusCode);
                        }
                    }
                }
        );

        // Step 6: Configure retry policy in case of timeouts or slow connections
        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                5000, // Timeout in milliseconds
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES, // Default retry attempts
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT // Backoff multiplier for exponential delay
        ));

        // Step 7: Add the request to the Volley request queue for execution
        requestQueue.add(jsonObjectRequest);
    }


    /**
     * Sends location data (latitude, longitude, and device/user IDs) to the Node-RED backend
     * for SunAzimuth monitoring. If valid, triggers vibration feedback based on server response.
     *
     * @param locationData The LocationData object containing coordinates and identifiers.
     * @param context      The Android context used for displaying toasts and logging.
     */
    public void sendLocation(LocationData locationData, Context context, String monitoringType) {
        // Step 1: Validate that all required IDs are present
        if ("UnknownUser".equals(locationData.getUserId()) ||
                "UnknownWatch".equals(locationData.getSmartWatchId()) ||
                "UnknownAndroid".equals(locationData.getAndroidId())) {

            Log.w("NetworkController", "⚠️ Location not sent. One or more IDs are unknown: " +
                    "UserID=" + locationData.getUserId() +
                    ", SmartWatchID=" + locationData.getSmartWatchId() +
                    ", AndroidID=" + locationData.getAndroidId());

            Toast.makeText(context, "⚠️ Cannot send location. IDs are incomplete.", Toast.LENGTH_LONG).show();
            return;
        }

        // Step 2: Send location data based on monitoring type
        Call<JsonObject> call = null;
        if ("SunAzimuth".equals(monitoringType)) {
            call = api.sendSunLocation(locationData);
        } else if ("MoonAzimuth".equals(monitoringType)) {
            call = api.sendMoonLocation(locationData);
        }

        if (call == null) {
            Log.e("NetworkController", "❌ Invalid monitoring type: " + monitoringType);
            Toast.makeText(context, "Invalid monitoring type.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 3: Enqueue the Retrofit call
        call.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull retrofit2.Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject jsonResponse = response.body();
                    Log.d("NetworkController", "✅ Location Sent. Response: " + jsonResponse.toString());

                    String message = jsonResponse.has("message") && !jsonResponse.get("message").isJsonNull()
                            ? jsonResponse.get("message").getAsString()
                            : "No message in response.";

                    int pulses = jsonResponse.has("pulses") ? jsonResponse.get("pulses").getAsInt() : 0;
                    int intensity = jsonResponse.has("intensity") ? jsonResponse.get("intensity").getAsInt() : 0;
                    int duration = jsonResponse.has("duration") ? jsonResponse.get("duration").getAsInt() : 0;
                    int interval = jsonResponse.has("interval") ? jsonResponse.get("interval").getAsInt() : 0;

                    Log.d("NetworkController", "📲 Vibration Parameters: " +
                            "Pulses=" + pulses + ", Intensity=" + intensity +
                            ", Duration=" + duration + ", Interval=" + interval);

                    Toast.makeText(context, "Location Sent: " + message, Toast.LENGTH_SHORT).show();

                    if (pulses > 0) {
                        bluetoothConnectionManager.sendVibrationCommand(intensity, pulses, duration, interval);
                    } else {
                        Log.d("NetworkController", "ℹ️ No vibration needed (pulses=0).");
                    }

                } else {
                    Log.e("NetworkController", "❌ Failed to send location. Response Code: " + response.code());
                    Toast.makeText(context, "Failed to send location.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e("NetworkController", "❌ Network Error: " + t.getMessage());
                Toast.makeText(context, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }



    /**
     * Sends a map of heart rate data to a Node-RED server for processing.
     * Receives feedback parameters (vibration settings) from the server and triggers a vibration
     * command via Bluetooth if the response is valid.
     *
     * @param data A map containing heart rate data (e.g., value, user ID, watch ID, android ID).
     */
    public void sendHeartRateToNodeRed(Map<String, String> data) {
        // Step 1: Ensure the request queue has been initialized
        if (requestQueue == null) {
            Log.e("NetworkController", "❌ RequestQueue is not initialized!");
            return;
        }

        // Step 2: Convert the heart rate data (Map) into a JSON object for POST body
        JSONObject jsonBody = new JSONObject(data);
        Log.d("NetworkController", "📤 Sending to Node-RED: " + jsonBody.toString());

        // Step 3: Prepare a JsonObjectRequest to send the data to Node-RED via HTTP POST
        final JsonObjectRequest[] jsonObjectRequest = new JsonObjectRequest[1]; // Use array to allow inner class reuse

        jsonObjectRequest[0] = new JsonObjectRequest(
                Request.Method.POST,           // HTTP method: POST
                NODE_RED_POST_URL,             // URL to send heart rate data to
                jsonBody,                      // JSON body to send
                response -> {  // Success callback
                    Log.d("NetworkController", "✅ Response from Node-RED: " + response.toString());

                    // Step 4: Extract vibration feedback parameters from the JSON response
                    int intensity = response.optInt("intensity", 0);
                    int pulses = response.optInt("pulses", 0);
                    int duration = response.optInt("duration", 0);
                    int interval = response.optInt("interval", 0);

                    // Step 5: Trigger the smartwatch to vibrate if connection manager is available
                    if (bluetoothConnectionManager != null) {
                        bluetoothConnectionManager.sendVibrationCommand(intensity, pulses, duration, interval);
                    } else {
                        Log.e("NetworkController", "❌ BluetoothConnectionManager is null!");
                    }
                },
                error -> {  // Error callback
                    Log.e("NetworkController", "❌ Error sending to Node-RED: " + error.toString());

                    // Log additional HTTP status if available
                    if (error.networkResponse != null) {
                        Log.e("NetworkController", "❌ HTTP Status Code: " + error.networkResponse.statusCode);
                    }

                    // Retry the request (custom retry method)
                    retryRequest(jsonObjectRequest[0]);
                }
        ) {
            // Step 6: Add custom HTTP headers (e.g., content type)
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };

        // Step 7: Set retry policy for network reliability
        jsonObjectRequest[0].setRetryPolicy(new DefaultRetryPolicy(
                5000,                                       // Timeout in ms
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,    // Max retries
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT    // Backoff multiplier
        ));

        // Step 8: Add the request to the Volley queue to send it
        requestQueue.add(jsonObjectRequest[0]);
    }




    /**
     * Helper method to retry request after a delay
     */
    private void retryRequest(final JsonObjectRequest request) {
        Log.d("NetworkController", "🔄 Retrying request in 3 seconds...");
        new Handler().postDelayed(() -> requestQueue.add(request), 3000);
    }


    /**
     * Listener Interface for Monitoring Type
     */
    public interface OnMonitoringTypeReceived {
        void onReceived(String monitoringType);
        void onError(String errorMessage);
    }
}
