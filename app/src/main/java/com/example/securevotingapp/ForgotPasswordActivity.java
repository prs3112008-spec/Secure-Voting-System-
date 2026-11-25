package com.example.securevotingapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.securevotingapp.network.ApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ForgotPasswordActivity extends AppCompatActivity {

    private EditText resetEmail, resetMobile, resetNewPassword, resetConfirmPassword;
    private Button resetSubmitButton;

    private String pendingEmail = "";
    private String pendingMobile = "";
    private String pendingNewPassword = "";

    private final ActivityResultLauncher<Intent> livenessResetLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            float[] embedding = result.getData().getFloatArrayExtra("face_embedding");
                            if (embedding != null && embedding.length > 0) {
                                performPasswordReset(pendingEmail, pendingMobile, pendingNewPassword, embedding);
                            } else {
                                Toast.makeText(this, "Failed to capture face embedding. Try again.", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(this, "Face verification cancelled.", Toast.LENGTH_SHORT).show();
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        resetEmail = findViewById(R.id.resetEmail);
        resetMobile = findViewById(R.id.resetMobile);
        resetNewPassword = findViewById(R.id.resetNewPassword);
        resetConfirmPassword = findViewById(R.id.resetConfirmPassword);
        resetSubmitButton = findViewById(R.id.resetSubmitButton);

        resetSubmitButton.setOnClickListener(v -> validateAndStartLiveness());

        TextView backToLoginText = findViewById(R.id.backToLoginText);
        if (backToLoginText != null) {
            backToLoginText.setOnClickListener(v -> finish());
        }
    }

    private void validateAndStartLiveness() {
        String email = resetEmail.getText().toString().trim();
        String mobile = resetMobile.getText().toString().trim();
        String newPass = resetNewPassword.getText().toString().trim();
        String confirmPass = resetConfirmPassword.getText().toString().trim();

        if (email.isEmpty() || mobile.isEmpty() || newPass.isEmpty() || confirmPass.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPass.equals(confirmPass)) {
            Toast.makeText(this, "New passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingEmail = email;
        pendingMobile = mobile;
        pendingNewPassword = newPass;

        Toast.makeText(this, "Opening camera for face verification...", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(ForgotPasswordActivity.this, LivenessActivity.class);
        intent.putExtra("mode", "RESET");
        livenessResetLauncher.launch(intent);
    }

    private void performPasswordReset(String email, String mobile, String newPassword, float[] embedding) {
        if (resetSubmitButton != null) {
            resetSubmitButton.setEnabled(false);
            resetSubmitButton.setText("Resetting Password...");
        }

        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("mobile_no", mobile);
        body.addProperty("new_password", newPassword);

        JsonArray arr = new JsonArray();
        for (float v : embedding) {
            arr.add(v);
        }
        body.add("face_embedding", arr);

        ApiClient.getApiService(this).resetPassword(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Toast.makeText(ForgotPasswordActivity.this, "Password reset successfully. Please sign in.", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    if (resetSubmitButton != null) {
                        resetSubmitButton.setEnabled(true);
                        resetSubmitButton.setText("Verify Face & Reset Password");
                    }
                    String errMessage = ApiClient.parseErrorMessage(response, "Password reset failed. Verify email, mobile, and face.");
                    Toast.makeText(ForgotPasswordActivity.this, errMessage, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (resetSubmitButton != null) {
                    resetSubmitButton.setEnabled(true);
                    resetSubmitButton.setText("Verify Face & Reset Password");
                }
                Toast.makeText(ForgotPasswordActivity.this, "Network error. Check connection.", Toast.LENGTH_LONG).show();
            }
        });
    }
}