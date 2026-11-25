package com.example.securevotingapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.securevotingapp.network.ApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LivenessActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 101;
    private static final String BASE_URL = "https://secure-voting-system-9rnd.onrender.com";

    private PreviewView previewView;
    private TextView statusText;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private FaceRecognizer faceRecognizer;

    private String jwtToken;
    private String enrollToken;
    private String activityMode = "VERIFY"; // Default mode
    private boolean isDataLoaded = false;

    // Blink state machine: 0 = waiting for eyes open, 1 = waiting for blink, 2 = processing/done
    private int blinkState = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liveness);

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        cameraExecutor = Executors.newSingleThreadExecutor();

        SharedPreferences prefs = SecurePrefs.get(this);
        jwtToken = prefs.getString("jwt_token", null);

        String extraMode = getIntent().getStringExtra("mode");
        if (extraMode != null && !extraMode.isEmpty()) {
            activityMode = extraMode.toUpperCase();
        }

        enrollToken = getIntent().getStringExtra("enroll_token");

        try {
            faceRecognizer = new FaceRecognizer(this);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load Face Recognition model", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();
        faceDetector = FaceDetection.getClient(options);

        if ("ENROLL".equalsIgnoreCase(activityMode)) {
            statusText.setText("Face Enrollment: Look at camera & BLINK");
        } else if ("RESET".equalsIgnoreCase(activityMode) || "CAPTURE".equalsIgnoreCase(activityMode)) {
            statusText.setText("Look at camera & BLINK to verify face for reset");
        } else {
            statusText.setText("Look at camera & BLINK to verify identity");
        }

        isDataLoaded = true;
        checkPermissionAndStartCamera();
    }

    private void checkPermissionAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null || !isDataLoaded || blinkState == 2) {
            imageProxy.close();
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage image = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (faces.isEmpty()) {
                        runOnUiThread(() -> statusText.setText("No face detected. Look at the camera."));
                    } else {
                        Face face = faces.get(0);
                        Float leftEye = face.getLeftEyeOpenProbability();
                        Float rightEye = face.getRightEyeOpenProbability();

                        if (leftEye != null && rightEye != null) {
                            if (blinkState == 0 && leftEye > 0.7f && rightEye > 0.7f) {
                                String modeText = "ENROLL".equalsIgnoreCase(activityMode)
                                        ? "Face Enrollment: Look at camera & BLINK"
                                        : "Face detected! BLINK to verify identity";
                                runOnUiThread(() -> statusText.setText(modeText));
                                blinkState = 1;
                            } else if (blinkState == 1 && leftEye < 0.3f && rightEye < 0.3f) {
                                blinkState = 2;
                                runOnUiThread(() -> statusText.setText("Processing facial biometrics..."));

                                Bitmap fullBitmap = rotateBitmap(imageProxy.toBitmap(), rotationDegrees);
                                Bitmap croppedFace = cropFace(fullBitmap, face.getBoundingBox());

                                float[] liveEmbedding = faceRecognizer.getFaceEmbedding(croppedFace);

                                if ("ENROLL".equalsIgnoreCase(activityMode)) {
                                    registerFaceEmbedding(liveEmbedding);
                                } else if ("RESET".equalsIgnoreCase(activityMode) || "CAPTURE".equalsIgnoreCase(activityMode)) {
                                    Intent resultIntent = new Intent();
                                    resultIntent.putExtra("face_embedding", liveEmbedding);
                                    setResult(RESULT_OK, resultIntent);
                                    finish();
                                } else {
                                    verifyFaceEmbeddingServer(liveEmbedding);
                                }
                            }
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void registerFaceEmbedding(float[] embedding) {
        String activeToken = (enrollToken != null && !enrollToken.isEmpty()) ? enrollToken : jwtToken;
        if (activeToken == null || activeToken.isEmpty()) {
            SharedPreferences prefs = SecurePrefs.get(this);
            activeToken = prefs.getString("jwt_token", null);
        }

        if (activeToken == null || activeToken.isEmpty()) {
            Toast.makeText(this, "Session token missing. Please log in.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (float v : embedding) {
            arr.add(v);
        }
        body.add("face_embedding", arr);

        ApiClient.getApiService(this).registerFace(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful()) {
                    SecurePrefs.clear(LivenessActivity.this);

                    Toast.makeText(LivenessActivity.this, "Registration & face enrollment complete! Please wait for Admin approval before logging in.", Toast.LENGTH_LONG).show();

                    Intent intent = new Intent(LivenessActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to save face profile");
                    Toast.makeText(LivenessActivity.this, errMessage, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(LivenessActivity.this, "Failed to save face profile. Check connection.", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    private void verifyFaceEmbeddingServer(float[] embedding) {
        if (jwtToken == null || jwtToken.isEmpty()) {
            SharedPreferences prefs = SecurePrefs.get(this);
            jwtToken = prefs.getString("jwt_token", null);
        }

        if (jwtToken == null || jwtToken.isEmpty()) {
            Toast.makeText(this, "Session token missing. Please log in again.", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (float v : embedding) {
            arr.add(v);
        }
        body.add("face_embedding", arr);

        ApiClient.getApiService(this).verifyFace(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String verificationToken = respObj.has("verification_token")
                            ? respObj.get("verification_token").getAsString()
                            : (respObj.has("token") ? respObj.get("token").getAsString() : "");

                    Toast.makeText(LivenessActivity.this, "Face & Liveness Verified!", Toast.LENGTH_SHORT).show();

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("verification_token", verificationToken);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Face verification failed. Please try again.");
                    Toast.makeText(LivenessActivity.this, errMessage, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(LivenessActivity.this, "Face verification failed. Check connection.", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private Bitmap cropFace(Bitmap bitmap, Rect box) {
        int left = Math.max(0, box.left);
        int top = Math.max(0, box.top);
        int width = Math.min(box.width(), bitmap.getWidth() - left);
        int height = Math.min(box.height(), bitmap.getHeight() - top);
        if (width <= 0 || height <= 0) return bitmap;
        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        faceDetector.close();
        if (faceRecognizer != null) {
            faceRecognizer.close();
        }
    }
}