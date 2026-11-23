package com.example.securevotingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

public class RegisterActivity extends AppCompatActivity {

    private static final String REGISTER_URL = "https://secure-voting-system-9rnd.onrender.com/api/auth/register";
    EditText regFullName, regEmail, regMobile, regPassword;
    Button registerSubmitButton;

    private final ActivityResultLauncher<Intent> livenessEnrollLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "Registration & Face Enrollment Complete!", Toast.LENGTH_LONG).show();
                            finish(); // Sends the user back to the Login screen
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
        registerSubmitButton = findViewById(R.id.registerSubmitButton);

        registerSubmitButton.setOnClickListener(v -> registerUser());
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

        if (registerSubmitButton != null) {
            registerSubmitButton.setEnabled(false);
            registerSubmitButton.setText("Registering... Please wait");
        }

        JSONObject body = new JSONObject();
        try {
            body.put("full_name", name);
            body.put("email", email);
            body.put("mobile_no", mobile);
            body.put("password", password);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                REGISTER_URL,
                body,
                response -> {
                    String msg = response.optString("message", "Account created successfully!");
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

                    // Extract enroll_token or token from register response
                    String enrollToken = response.optString("enroll_token", null);
                    if (enrollToken == null) enrollToken = response.optString("token", null);
                    if (enrollToken == null) enrollToken = response.optString("jwt", null);
                    if (enrollToken == null) enrollToken = response.optString("jwt_token", null);

                    if (enrollToken == null && response.has("user")) {
                        JSONObject userObj = response.optJSONObject("user");
                        if (userObj != null) {
                            enrollToken = userObj.optString("enroll_token", userObj.optString("token", userObj.optString("jwt", null)));
                        }
                    }
                    if (enrollToken == null && response.has("data")) {
                        JSONObject dataObj = response.optJSONObject("data");
                        if (dataObj != null) {
                            enrollToken = dataObj.optString("enroll_token", dataObj.optString("token", dataObj.optString("jwt", null)));
                        }
                    }

                    if (enrollToken != null) {
                        SharedPreferences sharedPreferences = SecurePrefs.get(RegisterActivity.this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString("jwt_token", enrollToken);
                        editor.apply();

                        Toast.makeText(this, "Opening camera for face enrollment...", Toast.LENGTH_SHORT).show();

                        // Launch LivenessActivity in ENROLL mode with enroll_token
                        Intent intent = new Intent(RegisterActivity.this, LivenessActivity.class);
                        intent.putExtra("mode", "ENROLL");
                        intent.putExtra("enroll_token", enrollToken);
                        livenessEnrollLauncher.launch(intent);
                    } else {
                        // Fallback: Perform automatic login to fetch token before launching face enrollment
                        Toast.makeText(this, "Authenticating for face enrollment...", Toast.LENGTH_SHORT).show();
                        loginAndEnrollFace(email, password);
                    }
                },
                error -> {
                    if (registerSubmitButton != null) {
                        registerSubmitButton.setEnabled(true);
                        registerSubmitButton.setText("Submit Registration");
                    }
                    String displayMsg = "Registration failed. Check connection.";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        displayMsg = "Too many attempts. Please wait a few minutes and try again.";
                    } else if (error.networkResponse != null && error.networkResponse.data != null) {
                        try {
                            JSONObject errObj = new JSONObject(new String(error.networkResponse.data, "UTF-8"));
                            displayMsg = errObj.optString("error", errObj.optString("message", displayMsg));
                        } catch (Exception ignored) {}
                    }
                    Toast.makeText(this, displayMsg, Toast.LENGTH_LONG).show();
                }
        );

        request.setRetryPolicy(new DefaultRetryPolicy(60000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        Volley.newRequestQueue(this).add(request);
    }

    private void loginAndEnrollFace(String email, String password) {
        String loginUrl = "https://secure-voting-system-9rnd.onrender.com/api/auth/login";

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("email", email);
            jsonBody.put("password", password);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                loginUrl,
                jsonBody,
                response -> {
                    String token = response.optString("token", response.optString("jwt", response.optString("jwt_token", null)));
                    String role = response.optString("role", "voter");

                    if (token != null) {
                        SharedPreferences sharedPreferences = SecurePrefs.get(RegisterActivity.this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString("jwt_token", token);
                        editor.putString("role", role);
                        editor.apply();

                        Toast.makeText(this, "Authenticated! Opening camera for face enrollment...", Toast.LENGTH_SHORT).show();

                        Intent intent = new Intent(RegisterActivity.this, LivenessActivity.class);
                        intent.putExtra("mode", "ENROLL");
                        intent.putExtra("enroll_token", token);
                        livenessEnrollLauncher.launch(intent);
                    } else {
                        Toast.makeText(this, "Registration complete! Please log in.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                },
                error -> {
                    Toast.makeText(this, "Account created! Please log in to complete setup.", Toast.LENGTH_LONG).show();
                    finish();
                }
        );

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(60000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        Volley.newRequestQueue(this).add(jsonObjectRequest);
    }
}