package com.example.securevotingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.securevotingapp.network.ApiClient;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

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

        android.widget.TextView forgotPasswordText = findViewById(R.id.forgotPasswordText);
        if (forgotPasswordText != null) {
            forgotPasswordText.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, ForgotPasswordActivity.class));
            });
        }
    }

    private void loginVoter(String voterId, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("email", voterId);
        body.addProperty("password", password);

        ApiClient.getApiService(this).login(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String token = respObj.has("token") ? respObj.get("token").getAsString() : "";
                    String role = respObj.has("role") ? respObj.get("role").getAsString() : "voter";

                    JsonObject permissionsObj = null;
                    if (respObj.has("permissions") && respObj.get("permissions").isJsonObject()) {
                        permissionsObj = respObj.getAsJsonObject("permissions");
                    }

                    SharedPreferences sharedPreferences = SecurePrefs.get(MainActivity.this);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString("jwt_token", token);
                    editor.putString("role", role);
                    if (permissionsObj != null) {
                        editor.putString("permissions_json", permissionsObj.toString());
                    } else {
                        editor.remove("permissions_json");
                    }
                    editor.apply();

                    Toast.makeText(MainActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                    startActivity(intent);
                    finish();
                } else {
                    int statusCode = response.code();
                    boolean isUnauthorized = (statusCode == 401);
                    boolean isPendingApproval = (statusCode == 403);
                    boolean isRateLimited = (statusCode == 429);
                    int retrySeconds = 15;

                    String displayMsg = ApiClient.parseErrorMessage(response, "Login failed. Please check credentials.");

                    if (response.headers().get("Retry-After") != null) {
                        try {
                            retrySeconds = Integer.parseInt(response.headers().get("Retry-After").trim());
                        } catch (Exception ignored) {}
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
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (loginButton != null) {
                    loginButton.setEnabled(true);
                    loginButton.setText("Verify & Authenticate");
                }
                boolean isTimeout = (t instanceof java.net.SocketTimeoutException) ||
                                    (t instanceof java.io.InterruptedIOException) ||
                                    (t.getMessage() != null && t.getMessage().toLowerCase().contains("timeout"));

                String errorMsg = isTimeout
                        ? "Election server is waking up, please tap Sign In again in a few seconds."
                        : "Network error. Please check connection.";

                Toast.makeText(getApplicationContext(), errorMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cooldownTimer != null) {
            cooldownTimer.cancel();
        }
    }
}