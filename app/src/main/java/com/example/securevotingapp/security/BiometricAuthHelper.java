package com.example.securevotingapp.security;

import android.content.Context;
import android.graphics.Color;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class BiometricAuthHelper {

    public interface BiometricAuthCallback {
        void onSuccess();
        void onError(String message);
    }

    public static void authenticate(FragmentActivity activity, BiometricAuthCallback callback) {
        if (activity == null || activity.isFinishing()) return;

        Context context = activity.getApplicationContext();
        BiometricManager biometricManager = BiometricManager.from(context);

        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            Executor executor = ContextCompat.getMainExecutor(activity);

            BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            Toast.makeText(context, "Authentication error: " + errString, Toast.LENGTH_SHORT).show();
                            if (callback != null) callback.onError(errString.toString());
                        }

                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            Toast.makeText(context, "2FA Hardware Authentication Passed!", Toast.LENGTH_SHORT).show();
                            if (callback != null) callback.onSuccess();
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            Toast.makeText(context, "Biometric authentication not recognized.", Toast.LENGTH_SHORT).show();
                        }
                    });

            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm Ballot Identity")
                    .setSubtitle("2FA Hardware Verification Required")
                    .setDescription("Verify your fingerprint, face, or device PIN to sign and cast your encrypted ballot.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();

            biometricPrompt.authenticate(promptInfo);
        } else {
            // Fallback confirmation dialog for emulators or devices without hardware/enrolled biometrics
            AlertDialog confirmDialog = new AlertDialog.Builder(activity)
                    .setTitle("Confirm Ballot Identity")
                    .setMessage("No hardware biometric enrolled on this device. Do you want to proceed with signing and casting your secure ballot?")
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        if (callback != null) callback.onError("Authentication cancelled");
                    })
                    .setPositiveButton("Proceed & Cast Ballot", (dialog, which) -> {
                        if (callback != null) callback.onSuccess();
                    })
                    .setCancelable(false)
                    .create();

            if (confirmDialog.getWindow() != null) {
                confirmDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            }
            confirmDialog.show();

            Button posBtn = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (posBtn != null) posBtn.setTextColor(Color.parseColor("#1E3A8A"));
            Button negBtn = confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negBtn != null) negBtn.setTextColor(Color.parseColor("#64748B"));
        }
    }
}