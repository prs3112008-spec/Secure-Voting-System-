package com.example.securevotingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.securevotingapp.network.ApiClient;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RegisterActivity extends AppCompatActivity {

    EditText regFullName, regEmail, regMobile, regPassword;
    MaterialCheckBox consentCheckBox;
    Button registerSubmitButton;

    private final ActivityResultLauncher<Intent> livenessEnrollLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "Registration & Face Enrollment Complete!", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            Toast.makeText(this, "Face enrollment incomplete. Please log in to complete setup.", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        regFullName = findViewById(R.id.regFullName);
        regEmail = findViewById(R.id.regEmail);
        regMobile = findViewById(R.id.regMobile);
        regPassword = findViewById(R.id.regPassword);
        consentCheckBox = findViewById(R.id.consentCheckBox);
        registerSubmitButton = findViewById(R.id.registerSubmitButton);
        registerSubmitButton.setOnClickListener(v -> registerUser());

        android.widget.TextView goToLoginText = findViewById(R.id.goToLoginText);
        if (goToLoginText != null) {
            goToLoginText.setOnClickListener(v -> finish());
        }
    }

    private void registerUser() {
        String name = regFullName.getText().toString().trim();
        String email = regEmail.getText().toString().trim();
        String mobile = regMobile.getText().toString().trim();
        String password = regPassword.getText().toString().trim();

        if (name.isEmpty() || email.isEmpty() || mobile.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (consentCheckBox != null && !consentCheckBox.isChecked()) {
            Toast.makeText(this, "Please agree to the Privacy Policy and biometric consent to proceed.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (registerSubmitButton != null) {
            registerSubmitButton.setEnabled(false);
            registerSubmitButton.setText("Registering... Please wait");
        }

        JsonObject body = new JsonObject();
        body.addProperty("full_name", name);
        body.addProperty("email", email);
        body.addProperty("mobile_no", mobile);
        body.addProperty("password", password);

        ApiClient.getApiService(this).register(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String msg = respObj.has("message") ? respObj.get("message").getAsString() : "Account created successfully!";
                    Toast.makeText(RegisterActivity.this, msg, Toast.LENGTH_SHORT).show();

                    String enrollToken = null;
                    if (respObj.has("enroll_token")) enrollToken = respObj.get("enroll_token").getAsString();
                    if (enrollToken == null && respObj.has("token")) enrollToken = respObj.get("token").getAsString();
                    if (enrollToken == null && respObj.has("jwt")) enrollToken = respObj.get("jwt").getAsString();
                    if (enrollToken == null && respObj.has("jwt_token")) enrollToken = respObj.get("jwt_token").getAsString();

                    if (enrollToken == null && respObj.has("user") && respObj.get("user").isJsonObject()) {
                        JsonObject userObj = respObj.getAsJsonObject("user");
                        if (userObj.has("enroll_token")) enrollToken = userObj.get("enroll_token").getAsString();
                    }

                    if (enrollToken != null) {
                        SharedPreferences sharedPreferences = SecurePrefs.get(RegisterActivity.this);
                        sharedPreferences.edit().putString("jwt_token", enrollToken).apply();

                        Toast.makeText(RegisterActivity.this, "Opening camera for face enrollment...", Toast.LENGTH_SHORT).show();

                        Intent intent = new Intent(RegisterActivity.this, LivenessActivity.class);
                        intent.putExtra("mode", "ENROLL");
                        intent.putExtra("enroll_token", enrollToken);
                        livenessEnrollLauncher.launch(intent);
                    } else {
                        Toast.makeText(RegisterActivity.this, "Authenticating for face enrollment...", Toast.LENGTH_SHORT).show();
                        loginAndEnrollFace(email, password);
                    }
                } else {
                    if (registerSubmitButton != null) {
                        registerSubmitButton.setEnabled(true);
                        registerSubmitButton.setText("Submit Registration");
                    }
                    String displayMsg = ApiClient.parseErrorMessage(response, "Registration failed. Check connection.");
                    Toast.makeText(RegisterActivity.this, displayMsg, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (registerSubmitButton != null) {
                    registerSubmitButton.setEnabled(true);
                    registerSubmitButton.setText("Submit Registration");
                }
                Toast.makeText(RegisterActivity.this, "Registration failed. Check connection.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loginAndEnrollFace(String email, String password) {
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("email", email);
        jsonBody.addProperty("password", password);

        ApiClient.getApiService(this).login(jsonBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String token = respObj.has("token") ? respObj.get("token").getAsString() : null;
                    String role = respObj.has("role") ? respObj.get("role").getAsString() : "voter";

                    if (token != null) {
                        SharedPreferences sharedPreferences = SecurePrefs.get(RegisterActivity.this);
                        sharedPreferences.edit().putString("jwt_token", token).putString("role", role).apply();

                        Toast.makeText(RegisterActivity.this, "Authenticated! Opening camera for face enrollment...", Toast.LENGTH_SHORT).show();

                        Intent intent = new Intent(RegisterActivity.this, LivenessActivity.class);
                        intent.putExtra("mode", "ENROLL");
                        intent.putExtra("enroll_token", token);
                        livenessEnrollLauncher.launch(intent);
                    } else {
                        Toast.makeText(RegisterActivity.this, "Registration complete! Please log in.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                } else {
                    Toast.makeText(RegisterActivity.this, "Account created! Please log in to complete setup.", Toast.LENGTH_LONG).show();
                    finish();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(RegisterActivity.this, "Account created! Please log in to complete setup.", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}