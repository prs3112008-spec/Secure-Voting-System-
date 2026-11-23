package com.example.securevotingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private android.os.CountDownTimer cooldownTimer;

    EditText voterIdInput, passwordInput;
    Button loginButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        voterIdInput = findViewById(R.id.voterIdInput);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);

        loginButton.setOnClickListener(v -> {
            String voterId = voterIdInput.getText().toString();
            String password = passwordInput.getText().toString();

            if (voterId.isEmpty() || password.isEmpty()) {
                Toast.makeText(MainActivity.this, "Please enter all details", Toast.LENGTH_SHORT).show();
            } else {
                loginButton.setEnabled(false);
                loginButton.setText("Authenticating... Please wait");
                loginVoter(voterId, password);
            }
        });

        Button goToRegisterButton = findViewById(R.id.goToRegisterButton);
        goToRegisterButton.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, RegisterActivity.class));
        });
    }

    private void loginVoter(String voterId, String password) {
        String url = "https://secure-voting-system-9rnd.onrender.com/api/auth/login";

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("email", voterId);
            jsonBody.put("password", password);
        } catch (JSONException e) {
            Log.e(TAG, "Error building JSON request body", e);
        }

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                url,
                jsonBody,
                response -> {
                    try {
                        // 1. Grab the token and role from the backend
                        String token = response.getString("token");
                        String role = response.optString("role", "voter");

                        // 2. Save it securely on the phone using EncryptedSharedPreferences
                        SharedPreferences sharedPreferences = SecurePrefs.get(MainActivity.this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString("jwt_token", token);
                        editor.putString("role", role);
                        editor.apply();

                        Toast.makeText(MainActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();

                        // 3. Jump to the new Dashboard screen
                        Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                        startActivity(intent);
                        finish(); // Close login screen

                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing login response", e);
                        if (loginButton != null) {
                            loginButton.setEnabled(true);
                            loginButton.setText("Verify & Authenticate");
                        }
                    }
                },
                error -> {
                    boolean isPendingApproval = false;
                    boolean isRateLimited = false;
                    boolean isUnauthorized = false;
                    int retrySeconds = 15;
                    String displayMsg = "Network error. Please check your connection.";

                    if (error.networkResponse != null) {
                        int statusCode = error.networkResponse.statusCode;

                        if (statusCode == 401) {
                            isUnauthorized = true;
                            displayMsg = "Invalid email or password. Please try again.";
                        } else if (statusCode == 403) {
                            isPendingApproval = true;
                            displayMsg = "Your account is pending Admin approval. Please wait for an administrator to approve your account before logging in.";
                        } else if (statusCode == 429) {
                            isRateLimited = true;
                            displayMsg = "Too many attempts. Please wait a few minutes and try again.";
                        }

                        if (error.networkResponse.data != null) {
                            try {
                                String body = new String(error.networkResponse.data, "UTF-8");
                                JSONObject errObj = new JSONObject(body);
                                String serverMsg = errObj.optString("error", errObj.optString("message", ""));
                                if (!serverMsg.isEmpty()) {
                                    displayMsg = serverMsg;
                                    if (serverMsg.toLowerCase().contains("pending") || serverMsg.toLowerCase().contains("approval") || statusCode == 403) {
                                        isPendingApproval = true;
                                    }
                                }
                                if (errObj.has("retryAfterSeconds")) {
                                    retrySeconds = errObj.optInt("retryAfterSeconds", 15);
                                }
                            } catch (Exception e) {
                                if (statusCode == 403) {
                                    isPendingApproval = true;
                                }
                            }
                        }

                        if (isRateLimited && retrySeconds == 15 && error.networkResponse.headers != null) {
                            String headerVal = error.networkResponse.headers.get("Retry-After");
                            if (headerVal != null) {
                                try {
                                    retrySeconds = Integer.parseInt(headerVal.trim());
                                } catch (Exception ignored) {}
                            }
                        }
                    } else if (error instanceof com.android.volley.TimeoutError) {
                        displayMsg = "Server took too long to respond. Try again.";
                    }

                    if (isUnauthorized) {
                        if (passwordInput != null) {
                            passwordInput.setText("");
                            passwordInput.requestFocus();
                        }
                        if (loginButton != null) {
                            loginButton.setEnabled(true);
                            loginButton.setText("Verify & Authenticate");
                        }
                        Toast.makeText(getApplicationContext(), displayMsg, Toast.LENGTH_LONG).show();
                    } else if (isRateLimited) {
                        if (passwordInput != null) {
                            passwordInput.setText("");
                            passwordInput.requestFocus();
                        }
                        if (loginButton != null) {
                            loginButton.setEnabled(false);
                        }

                        if (cooldownTimer != null) {
                            cooldownTimer.cancel();
                        }

                        final int totalSeconds = Math.max(retrySeconds, 1);
                        String initialTimeText = totalSeconds < 60 ? totalSeconds + "s" : (totalSeconds / 60) + "m " + String.format(java.util.Locale.US, "%02d", totalSeconds % 60) + "s";
                        Toast.makeText(getApplicationContext(), "Too many failed attempts. Try again in " + initialTimeText + ".", Toast.LENGTH_LONG).show();

                        cooldownTimer = new android.os.CountDownTimer(totalSeconds * 1000L, 1000L) {
                            @Override
                            public void onTick(long millisUntilFinished) {
                                long sec = (millisUntilFinished / 1000L) + 1;
                                String cooldownLabel;
                                if (sec < 60) {
                                    cooldownLabel = "Try again in " + sec + "s";
                                } else {
                                    cooldownLabel = "Try again in " + (sec / 60) + "m " + String.format(java.util.Locale.US, "%02d", sec % 60) + "s";
                                }
                                if (loginButton != null) {
                                    loginButton.setText(cooldownLabel);
                                }
                            }

                            @Override
                            public void onFinish() {
                                if (loginButton != null) {
                                    loginButton.setEnabled(true);
                                    loginButton.setText("Verify & Authenticate");
                                }
                            }
                        }.start();
                    } else if (isPendingApproval) {
                        if (loginButton != null) {
                            loginButton.setEnabled(true);
                            loginButton.setText("Verify & Authenticate");
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Account Pending Approval")
                                .setMessage("Your account is pending Admin approval. Please wait for an administrator to approve your account before logging in.")
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        if (loginButton != null) {
                            loginButton.setEnabled(true);
                            loginButton.setText("Verify & Authenticate");
                        }
                        Toast.makeText(getApplicationContext(), displayMsg, Toast.LENGTH_LONG).show();
                    }
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json; charset=utf-8");
                return headers;
            }
        };

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                60000, // Wait up to 60 seconds for Render cold starts
                0,     // 0 retries to prevent duplicate submissions
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(jsonObjectRequest);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cooldownTimer != null) {
            cooldownTimer.cancel();
        }
    }
}