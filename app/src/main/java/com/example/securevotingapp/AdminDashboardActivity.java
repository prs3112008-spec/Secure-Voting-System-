package com.example.securevotingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class AdminDashboardActivity extends AppCompatActivity {

    private static final String TAG = "AdminDashboardActivity";
    private static final String BASE_URL = "https://secure-voting-system-9rnd.onrender.com";

    EditText candidateNameInput, candidatePartyInput;
    Button addCandidateButton, selectPhotoButton;
    ImageView candidatePhotoPreview;
    LinearLayout votersContainer;
    int currentPollId = 1;

    Button tabElectionBtn, tabCandidatesBtn, tabVotersBtn;
    View sectionElection, sectionCandidates, sectionVoters;
    Button adminLogoutBtn;

    private String selectedPhotoBase64 = "";

    private final ActivityResultLauncher<String> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        Bitmap original = BitmapFactory.decodeStream(is);
                        Bitmap scaled = Bitmap.createScaledBitmap(original, 150, 150, true);

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                        String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                        selectedPhotoBase64 = "data:image/jpeg;base64," + base64;

                        ImageView preview = findViewById(R.id.candidatePhotoPreview);
                        if (preview != null) {
                            Glide.with(this).load(uri).circleCrop().into(preview);
                        }
                        Toast.makeText(this, "Photo selected!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private androidx.appcompat.widget.SwitchCompat electionActiveSwitch;
    private Button pickStartTimeBtn, pickEndTimeBtn, saveScheduleBtn;
    private String selectedStartIso = "";
    private String selectedEndIso = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);

        tabElectionBtn = findViewById(R.id.tabElectionBtn);
        tabCandidatesBtn = findViewById(R.id.tabCandidatesBtn);
        tabVotersBtn = findViewById(R.id.tabVotersBtn);

        sectionElection = findViewById(R.id.sectionElection);
        sectionCandidates = findViewById(R.id.sectionCandidates);
        sectionVoters = findViewById(R.id.sectionVoters);

        adminLogoutBtn = findViewById(R.id.adminLogoutBtn);

        if (tabElectionBtn != null) tabElectionBtn.setOnClickListener(v -> showTab("election"));
        if (tabCandidatesBtn != null) tabCandidatesBtn.setOnClickListener(v -> showTab("candidates"));
        if (tabVotersBtn != null) tabVotersBtn.setOnClickListener(v -> showTab("voters"));

        if (adminLogoutBtn != null) {
            adminLogoutBtn.setOnClickListener(v -> {
                SecurePrefs.clear(AdminDashboardActivity.this);

                Intent intent = new Intent(AdminDashboardActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }

        candidateNameInput = findViewById(R.id.candidateNameInput);
        candidatePartyInput = findViewById(R.id.candidatePartyInput);
        selectPhotoButton = findViewById(R.id.pickPhotoBtn);
        candidatePhotoPreview = findViewById(R.id.candidatePhotoPreview);
        addCandidateButton = findViewById(R.id.addCandidateButton);
        votersContainer = findViewById(R.id.votersContainer);

        if (selectPhotoButton != null) {
            selectPhotoButton.setOnClickListener(v -> photoPickerLauncher.launch("image/*"));
        }

        electionActiveSwitch = findViewById(R.id.electionActiveSwitch);
        pickStartTimeBtn = findViewById(R.id.pickStartTimeBtn);
        pickEndTimeBtn = findViewById(R.id.pickEndTimeBtn);
        saveScheduleBtn = findViewById(R.id.saveScheduleBtn);

        loadCurrentSchedule();

        if (pickStartTimeBtn != null) pickStartTimeBtn.setOnClickListener(v -> pickDateTime(true, pickStartTimeBtn));
        if (pickEndTimeBtn != null) pickEndTimeBtn.setOnClickListener(v -> pickDateTime(false, pickEndTimeBtn));
        if (saveScheduleBtn != null) saveScheduleBtn.setOnClickListener(v -> saveElectionSchedule());

        Button verifyChainBtn = findViewById(R.id.verifyChainBtn);
        if (verifyChainBtn != null) {
            verifyChainBtn.setOnClickListener(v -> verifyChainIntegrity());
        }

        Button auditLogsBtn = findViewById(R.id.auditLogsBtn);
        if (auditLogsBtn != null) {
            auditLogsBtn.setOnClickListener(v -> fetchAuditLogs());
        }

        Button exportReportBtn = findViewById(R.id.exportReportBtn);
        if (exportReportBtn != null) {
            exportReportBtn.setOnClickListener(v -> exportElectionReport(exportReportBtn));
        }

        addCandidateButton.setOnClickListener(v -> {
            String newName = candidateNameInput.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(AdminDashboardActivity.this, "Please enter a name", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedPhotoBase64 == null || selectedPhotoBase64.trim().isEmpty()) {
                Toast.makeText(AdminDashboardActivity.this, "Please select a candidate photo before adding.", Toast.LENGTH_SHORT).show();
                return;
            }
            addCandidateButton.setEnabled(false);
            addCandidateButton.setText("ADDING...");
            addCandidateToDatabase(newName);
        });

        // Default tab on launch
        showTab("election");

        // Load all voters & candidates when Admin Dashboard opens
        loadVoters();
        loadAdminCandidates();
    }

    private void showTab(String tab) {
        if ("election".equalsIgnoreCase(tab)) {
            if (sectionElection != null) sectionElection.setVisibility(View.VISIBLE);
            if (sectionCandidates != null) sectionCandidates.setVisibility(View.GONE);
            if (sectionVoters != null) sectionVoters.setVisibility(View.GONE);

            if (tabElectionBtn != null) {
                tabElectionBtn.setBackgroundColor(Color.parseColor("#1E3A8A"));
                tabElectionBtn.setTextColor(Color.WHITE);
            }
            if (tabCandidatesBtn != null) {
                tabCandidatesBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabCandidatesBtn.setTextColor(Color.parseColor("#334155"));
            }
            if (tabVotersBtn != null) {
                tabVotersBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabVotersBtn.setTextColor(Color.parseColor("#334155"));
            }
        } else if ("candidates".equalsIgnoreCase(tab)) {
            if (sectionElection != null) sectionElection.setVisibility(View.GONE);
            if (sectionCandidates != null) sectionCandidates.setVisibility(View.VISIBLE);
            if (sectionVoters != null) sectionVoters.setVisibility(View.GONE);

            if (tabCandidatesBtn != null) {
                tabCandidatesBtn.setBackgroundColor(Color.parseColor("#1E3A8A"));
                tabCandidatesBtn.setTextColor(Color.WHITE);
            }
            if (tabElectionBtn != null) {
                tabElectionBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabElectionBtn.setTextColor(Color.parseColor("#334155"));
            }
            if (tabVotersBtn != null) {
                tabVotersBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabVotersBtn.setTextColor(Color.parseColor("#334155"));
            }
        } else if ("voters".equalsIgnoreCase(tab)) {
            if (sectionElection != null) sectionElection.setVisibility(View.GONE);
            if (sectionCandidates != null) sectionCandidates.setVisibility(View.GONE);
            if (sectionVoters != null) sectionVoters.setVisibility(View.VISIBLE);

            if (tabVotersBtn != null) {
                tabVotersBtn.setBackgroundColor(Color.parseColor("#1E3A8A"));
                tabVotersBtn.setTextColor(Color.WHITE);
            }
            if (tabElectionBtn != null) {
                tabElectionBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabElectionBtn.setTextColor(Color.parseColor("#334155"));
            }
            if (tabCandidatesBtn != null) {
                tabCandidatesBtn.setBackgroundColor(Color.parseColor("#E2E8F0"));
                tabCandidatesBtn.setTextColor(Color.parseColor("#334155"));
            }
        }
    }

    private String getJwtToken() {
        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        return sharedPreferences.getString("jwt_token", null);
    }

    private void addCandidateToDatabase(String candidateName) {
        if (selectedPhotoBase64 == null || selectedPhotoBase64.trim().isEmpty()) {
            Toast.makeText(AdminDashboardActivity.this, "Please select a candidate photo before adding.", Toast.LENGTH_SHORT).show();
            if (addCandidateButton != null) {
                addCandidateButton.setEnabled(true);
                addCandidateButton.setText("ADD CANDIDATE");
            }
            return;
        }

        String url = BASE_URL + "/api/candidates/add";
        String token = getJwtToken();
        String party = (candidatePartyInput != null) ? candidatePartyInput.getText().toString().trim() : "";

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("poll_id", currentPollId);
            jsonBody.put("candidate_name", candidateName);
            jsonBody.put("party", party.isEmpty() ? "Independent" : party);
            jsonBody.put("photo_url", selectedPhotoBase64);
        } catch (JSONException e) {
            Log.e(TAG, "Error building JSON request body", e);
        }

        final String requestBodyString = jsonBody.toString();

        StringRequest stringRequest = new StringRequest(
                Request.Method.POST,
                url,
                response -> {
                    try {
                        String msg = "Candidate added successfully!";
                        if (response != null && !response.trim().isEmpty()) {
                            try {
                                JSONObject respObj = new JSONObject(response.trim());
                                if (respObj.has("message")) {
                                    msg = respObj.getString("message");
                                }
                            } catch (JSONException ignored) {}
                        }
                        Toast.makeText(AdminDashboardActivity.this, msg, Toast.LENGTH_LONG).show();
                        if (candidateNameInput != null) candidateNameInput.setText("");
                        if (candidatePartyInput != null) candidatePartyInput.setText("");
                        selectedPhotoBase64 = "";
                        if (candidatePhotoPreview != null) {
                            candidatePhotoPreview.setImageResource(android.R.drawable.ic_menu_myplaces);
                        }
                        if (addCandidateButton != null) {
                            addCandidateButton.setEnabled(true);
                            addCandidateButton.setText("ADD CANDIDATE");
                        }
                        loadAdminCandidates();
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing add candidate response", e);
                        if (addCandidateButton != null) {
                            addCandidateButton.setEnabled(true);
                            addCandidateButton.setText("ADD CANDIDATE");
                        }
                    }
                },
                error -> {
                    if (addCandidateButton != null) {
                        addCandidateButton.setEnabled(true);
                        addCandidateButton.setText("ADD CANDIDATE");
                    }
                    String errorMessage = "Failed to add candidate. Please try again.";
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        try {
                            String body = new String(error.networkResponse.data, "UTF-8");
                            JSONObject errObj = new JSONObject(body);
                            String serverMsg = errObj.optString("error", errObj.optString("message", ""));
                            if (!serverMsg.isEmpty()) {
                                errorMessage = serverMsg;
                            }
                        } catch (Exception ignored) {}
                    }
                    Toast.makeText(AdminDashboardActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
        ) {
            @Override
            public String getBodyContentType() {
                return "application/json; charset=utf-8";
            }

            @Override
            public byte[] getBody() {
                try {
                    return requestBodyString.getBytes("utf-8");
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(stringRequest);
    }

    private void loadVoters() {
        String url = BASE_URL + "/api/admin/voters";
        String token = getJwtToken();

        JsonArrayRequest request = new JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    votersContainer.removeAllViews();
                    try {
                        if (response == null || response.length() == 0) {
                            TextView empty = new TextView(this);
                            empty.setText("No voters registered yet.");
                            empty.setTextColor(Color.parseColor("#64748B"));
                            empty.setTextSize(14);
                            empty.setPadding(16, 24, 16, 24);
                            empty.setGravity(Gravity.CENTER);
                            votersContainer.addView(empty);
                            return;
                        }
                        for (int i = 0; i < response.length(); i++) {
                            JSONObject voter = response.getJSONObject(i);
                            int userId = voter.getInt("user_id");
                            String email = voter.optString("email", "Unknown");
                            boolean isApproved = voter.optBoolean("is_approved", false);

                            addVoterRow(userId, email, isApproved);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing voters list", e);
                    }
                },
                error -> Toast.makeText(this, "Failed to load voters", Toast.LENGTH_SHORT).show()
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + token);
                return headers;
            }
        };

        Volley.newRequestQueue(this).add(request);
    }

    private void addVoterRow(int userId, String email, boolean isApproved) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(24, 20, 24, 20);
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, 0, 0, 16);
        row.setLayoutParams(rowParams);

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView emailText = new TextView(this);
        emailText.setText(email);
        emailText.setTextColor(Color.parseColor("#1E293B"));
        emailText.setTextSize(15);
        emailText.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView statusText = new TextView(this);
        statusText.setText("Status: " + (isApproved ? "Approved" : "Pending"));
        statusText.setTextColor(isApproved ? Color.parseColor("#15803D") : Color.parseColor("#B45309"));
        statusText.setTextSize(13);

        textLayout.addView(emailText);
        textLayout.addView(statusText);

        com.google.android.material.button.MaterialButton actionButton = new com.google.android.material.button.MaterialButton(this);
        actionButton.setText(isApproved ? "Revoke" : "Approve");
        actionButton.setBackgroundColor(isApproved ? Color.parseColor("#DC2626") : Color.parseColor("#059669"));
        actionButton.setTextColor(Color.WHITE);
        actionButton.setCornerRadius(16);

        actionButton.setOnClickListener(v -> confirmUpdateVoterApproval(userId, !isApproved, actionButton, email));

        row.addView(textLayout);
        row.addView(actionButton);
        votersContainer.addView(row);
    }

    private void confirmUpdateVoterApproval(int userId, boolean approve, Button actionButton, String email) {
        String actionText = approve ? "approve" : "revoke access for";
        AlertDialog confirmDialog = new AlertDialog.Builder(this)
                .setTitle(approve ? "Approve Voter" : "Revoke Access")
                .setMessage("Are you sure you want to " + actionText + " " + email + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm", (dialog, which) -> updateVoterApproval(userId, approve, actionButton))
                .create();

        if (confirmDialog.getWindow() != null) {
            confirmDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        confirmDialog.show();

        Button posBtn = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.setTextColor(approve ? Color.parseColor("#059669") : Color.parseColor("#DC2626"));
        Button negBtn = confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negBtn != null) negBtn.setTextColor(Color.parseColor("#64748B"));
    }

    private void updateVoterApproval(int userId, boolean approve, Button actionButton) {
        if (actionButton != null) {
            actionButton.setEnabled(false);
            actionButton.setText("UPDATING...");
        }
        String url = BASE_URL + "/api/admin/voters/" + userId + "/approve";
        String token = getJwtToken();

        JSONObject body = new JSONObject();
        try {
            body.put("is_approved", approve);
        } catch (JSONException e) {
            Log.e(TAG, "Error building approval body", e);
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.PUT,
                url,
                body,
                response -> {
                    Toast.makeText(this, approve ? "Voter Approved!" : "Approval Revoked", Toast.LENGTH_SHORT).show();
                    loadVoters(); // Refresh the list immediately
                },
                error -> {
                    if (actionButton != null) {
                        actionButton.setEnabled(true);
                        actionButton.setText(approve ? "Revoke" : "Approve");
                    }
                    String errMessage = "Failed to update approval";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        errMessage = "Too many attempts. Please wait a few minutes and try again.";
                    }
                    Toast.makeText(this, errMessage, Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("Authorization", "Bearer " + token);
                return headers;
            }
        };

        Volley.newRequestQueue(this).add(request);
    }

    private void loadCurrentSchedule() {
        String url = BASE_URL + "/api/votes/election-status/1";
        String token = getJwtToken();

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    if (electionActiveSwitch != null) {
                        electionActiveSwitch.setChecked(response.optBoolean("is_active", true));
                    }
                    selectedStartIso = response.optString("start_time", "");
                    selectedEndIso = response.optString("end_time", "");
                    if (!selectedStartIso.isEmpty() && pickStartTimeBtn != null) {
                        pickStartTimeBtn.setText("Start: " + (selectedStartIso.length() >= 16 ? selectedStartIso.substring(0, 16).replace("T", " ") : selectedStartIso));
                    }
                    if (!selectedEndIso.isEmpty() && pickEndTimeBtn != null) {
                        pickEndTimeBtn.setText("End: " + (selectedEndIso.length() >= 16 ? selectedEndIso.substring(0, 16).replace("T", " ") : selectedEndIso));
                    }
                },
                error -> Toast.makeText(this, "Could not load current schedule", Toast.LENGTH_SHORT).show()
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        Volley.newRequestQueue(this).add(req);
    }

    private void pickDateTime(boolean isStart, Button targetBtn) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, year, month, day) -> {
            new android.app.TimePickerDialog(this, (tView, hour, minute) -> {
                cal.set(year, month, day, hour, minute, 0);
                java.text.SimpleDateFormat isoFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault());
                java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());

                if (isStart) {
                    selectedStartIso = isoFormat.format(cal.getTime());
                    targetBtn.setText("Start: " + displayFormat.format(cal.getTime()));
                } else {
                    selectedEndIso = isoFormat.format(cal.getTime());
                    targetBtn.setText("End: " + displayFormat.format(cal.getTime()));
                }
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), false).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void saveElectionSchedule() {
        String url = BASE_URL + "/api/votes/update-schedule";
        String token = getJwtToken();

        try {
            JSONObject body = new JSONObject();
            body.put("poll_id", 1);
            body.put("start_time", selectedStartIso);
            body.put("end_time", selectedEndIso);
            if (electionActiveSwitch != null) {
                body.put("is_active", electionActiveSwitch.isChecked());
            }

            JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, body,
                    response -> Toast.makeText(this, response.optString("message", "Schedule saved!"), Toast.LENGTH_SHORT).show(),
                    error -> Toast.makeText(this, "Failed to save schedule", Toast.LENGTH_SHORT).show()
            ) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json; charset=utf-8");
                    if (token != null) {
                        headers.put("Authorization", "Bearer " + token);
                    }
                    return headers;
                }
            };

            Volley.newRequestQueue(this).add(req);
        } catch (Exception e) {
            Log.e(TAG, "Error saving schedule", e);
        }
    }

    private void confirmDeleteCandidate(int candidateId, Button deleteBtn, String candidateName) {
        AlertDialog confirmDialog = new AlertDialog.Builder(this)
                .setTitle("Delete Candidate")
                .setMessage("Are you sure you want to delete " + candidateName + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm", (dialog, which) -> deleteCandidate(candidateId, deleteBtn))
                .create();

        if (confirmDialog.getWindow() != null) {
            confirmDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        confirmDialog.show();

        Button posBtn = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.setTextColor(Color.parseColor("#DC2626"));
        Button negBtn = confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negBtn != null) negBtn.setTextColor(Color.parseColor("#64748B"));
    }

    private void deleteCandidate(int candidateId, Button deleteBtn) {
        if (deleteBtn != null) {
            deleteBtn.setEnabled(false);
            deleteBtn.setText("DELETING...");
        }
        String url = BASE_URL + "/api/candidates/" + candidateId;
        String token = getJwtToken();

        StringRequest request = new StringRequest(
                Request.Method.DELETE, url,
                response -> {
                    Toast.makeText(this, "Candidate deleted!", Toast.LENGTH_SHORT).show();
                    loadAdminCandidates(); // Refresh list immediately
                },
                error -> {
                    if (deleteBtn != null) {
                        deleteBtn.setEnabled(true);
                        deleteBtn.setText("DELETE");
                    }
                    String errMessage = "Failed to delete candidate";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        errMessage = "Too many attempts. Please wait a few minutes and try again.";
                    }
                    Toast.makeText(this, errMessage, Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        Volley.newRequestQueue(this).add(request);
    }

    private void loadAdminCandidates() {
        String url = BASE_URL + "/api/candidates/1";
        LinearLayout candidatesContainer = findViewById(R.id.candidatesContainer);
        if (candidatesContainer == null) return;

        candidatesContainer.setVisibility(View.VISIBLE);
        String token = getJwtToken();

        StringRequest request = new StringRequest(
                Request.Method.GET, url,
                response -> {
                    candidatesContainer.removeAllViews();
                    try {
                        JSONArray array = null;
                        String trimmed = response != null ? response.trim() : "";
                        if (trimmed.startsWith("[")) {
                            array = new JSONArray(trimmed);
                        } else if (trimmed.startsWith("{")) {
                            JSONObject obj = new JSONObject(trimmed);
                            array = obj.optJSONArray("candidates");
                            if (array == null) array = obj.optJSONArray("data");
                        }

                        if (array == null || array.length() == 0) {
                            TextView empty = new TextView(this);
                            empty.setText("No candidates added yet.");
                            empty.setTextColor(Color.parseColor("#64748B"));
                            empty.setTextSize(14);
                            empty.setPadding(16, 24, 16, 24);
                            empty.setGravity(Gravity.CENTER);
                            candidatesContainer.addView(empty);
                            return;
                        }

                        for (int i = 0; i < array.length(); i++) {
                            JSONObject c = array.getJSONObject(i);
                            int candidateId = c.optInt("candidate_id", c.optInt("id"));
                            String name = c.optString("candidate_name", c.optString("name", "Unknown"));
                            String party = c.optString("party", c.optString("party_name", "Independent"));
                            String photoUrl = c.optString("photo_url", c.optString("image_url", ""));

                            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
                            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            cardParams.setMargins(0, 0, 0, 16);
                            card.setLayoutParams(cardParams);
                            card.setCardBackgroundColor(Color.WHITE);
                            card.setRadius(24f); // 12dp equivalent
                            card.setStrokeColor(Color.parseColor("#E2E8F0"));
                            card.setStrokeWidth(2);
                            card.setCardElevation(0f);

                            LinearLayout row = new LinearLayout(this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setPadding(24, 20, 24, 20);
                            row.setGravity(Gravity.CENTER_VERTICAL);

                            ImageView avatarImageView = new ImageView(this);
                            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(96, 96);
                            avatarParams.setMargins(0, 0, 20, 0);
                            avatarImageView.setLayoutParams(avatarParams);

                            Glide.with(this)
                                    .load(photoUrl)
                                    .circleCrop()
                                    .placeholder(android.R.drawable.ic_menu_myplaces)
                                    .error(android.R.drawable.ic_menu_myplaces)
                                    .into(avatarImageView);

                            LinearLayout textLayout = new LinearLayout(this);
                            textLayout.setOrientation(LinearLayout.VERTICAL);
                            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            textParams.setMarginEnd(24);
                            textLayout.setLayoutParams(textParams);

                            TextView nameText = new TextView(this);
                            nameText.setText(name);
                            nameText.setTextColor(Color.parseColor("#1E293B"));
                            nameText.setTextSize(15);
                            nameText.setTypeface(null, android.graphics.Typeface.BOLD);

                            TextView partyText = new TextView(this);
                            partyText.setText(party);
                            partyText.setTextColor(Color.parseColor("#475569"));
                            partyText.setTextSize(13);

                            textLayout.addView(nameText);
                            textLayout.addView(partyText);

                            com.google.android.material.button.MaterialButton deleteBtn = new com.google.android.material.button.MaterialButton(this);
                            deleteBtn.setText("DELETE");
                            deleteBtn.setTextColor(Color.WHITE);
                            deleteBtn.setBackgroundColor(Color.parseColor("#DC2626"));
                            deleteBtn.setCornerRadius(16);
                            deleteBtn.setOnClickListener(v -> confirmDeleteCandidate(candidateId, deleteBtn, name));

                            row.addView(avatarImageView);
                            row.addView(textLayout);
                            row.addView(deleteBtn);
                            card.addView(row);
                            candidatesContainer.addView(card);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parsing candidates failed", e);
                        Toast.makeText(this, "Failed to load candidates. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    Log.e(TAG, "Fetching candidates failed", error);
                    Toast.makeText(this, "Failed to load candidates. Check connection.", Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        request.setShouldCache(false);
        Volley.newRequestQueue(this).add(request);
    }

    private void verifyChainIntegrity() {
        String url = BASE_URL + "/api/votes/verify-chain";
        String token = getJwtToken();

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    boolean isValid = response.optBoolean("valid", false);
                    String message = response.optString("message", "Verification complete.");

                    new AlertDialog.Builder(this)
                            .setTitle(isValid ? "Chain Integrity Verified" : "Tampering Detected!")
                            .setMessage(message)
                            .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                            .show();
                },
                error -> Toast.makeText(this, "Error connecting to verification server", Toast.LENGTH_SHORT).show()
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        Volley.newRequestQueue(this).add(request);
    }

    private void fetchAuditLogs() {
        String url = BASE_URL + "/api/admin/audit-logs";
        String token = getJwtToken();

        StringRequest request = new StringRequest(
                Request.Method.GET,
                url,
                response -> {
                    try {
                        JSONArray array = null;
                        String trimmed = response != null ? response.trim() : "";
                        if (trimmed.startsWith("[")) {
                            array = new JSONArray(trimmed);
                        } else if (trimmed.startsWith("{")) {
                            JSONObject obj = new JSONObject(trimmed);
                            array = obj.optJSONArray("logs");
                            if (array == null) array = obj.optJSONArray("audit_logs");
                            if (array == null) array = obj.optJSONArray("data");
                        }

                        displayAuditLogsDialog(array);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing audit logs", e);
                        Toast.makeText(this, "Failed to parse audit logs", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    String errMessage = "Failed to fetch audit logs";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        errMessage = "Too many attempts. Please wait a few minutes and try again.";
                    }
                    Toast.makeText(this, errMessage, Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        Volley.newRequestQueue(this).add(request);
    }

    private void exportElectionReport(Button exportBtn) {
        if (exportBtn != null) {
            exportBtn.setEnabled(false);
            exportBtn.setText("EXPORTING REPORT...");
        }

        String url = BASE_URL + "/api/admin/export-results/1";
        String token = getJwtToken();

        StringRequest request = new StringRequest(
                Request.Method.GET,
                url,
                response -> {
                    if (exportBtn != null) {
                        exportBtn.setEnabled(true);
                        exportBtn.setText("EXPORT OFFICIAL REPORT (CSV)");
                    }
                    try {
                        java.io.File reportsDir = new java.io.File(getCacheDir(), "reports");
                        if (!reportsDir.exists()) {
                            reportsDir.mkdirs();
                        }

                        java.io.File reportFile = new java.io.File(reportsDir, "election-report-poll-1.csv");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(reportFile);
                        fos.write(response != null ? response.getBytes("UTF-8") : new byte[0]);
                        fos.flush();
                        fos.close();

                        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", reportFile);

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/csv");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Official Election Report - Poll #1");
                        shareIntent.setClipData(android.content.ClipData.newRawUri("Official Election Report", contentUri));
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        startActivity(Intent.createChooser(shareIntent, "Share Official Election Report"));
                        Toast.makeText(this, "Report generated and ready to share!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save or share CSV report", e);
                        Toast.makeText(this, "Failed to generate CSV report. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    if (exportBtn != null) {
                        exportBtn.setEnabled(true);
                        exportBtn.setText("EXPORT OFFICIAL REPORT (CSV)");
                    }
                    String errMessage = "Failed to export report";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        errMessage = "Too many attempts. Please wait a few minutes and try again.";
                    }
                    Toast.makeText(this, errMessage, Toast.LENGTH_SHORT).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                if (token != null) {
                    headers.put("Authorization", "Bearer " + token);
                }
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(
                60000,
                0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        Volley.newRequestQueue(this).add(request);
    }

    private String formatAuditTimestamp(String rawTs) {
        if (rawTs == null || rawTs.isEmpty() || rawTs.equalsIgnoreCase("null")) {
            return "";
        }

        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
        };

        for (String pattern : formats) {
            try {
                java.text.SimpleDateFormat parseFormat = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                parseFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = parseFormat.parse(rawTs);
                if (date != null) {
                    java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", java.util.Locale.getDefault());
                    displayFormat.setTimeZone(java.util.TimeZone.getDefault());
                    return displayFormat.format(date);
                }
            } catch (Exception ignored) {}
        }
        return rawTs;
    }

    private String formatActionTitle(String rawAction) {
        if (rawAction == null || rawAction.isEmpty()) return "System Event";
        String[] words = rawAction.replace("_", " ").toLowerCase(java.util.Locale.US).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String formatAuditDetails(String action, String rawDetails) {
        if (rawDetails == null || rawDetails.isEmpty() || rawDetails.equalsIgnoreCase("null")) {
            return "";
        }

        String upperAction = action != null ? action.toUpperCase(java.util.Locale.US) : "";

        try {
            JSONObject json = new JSONObject(rawDetails.trim());

            if (upperAction.contains("APPROVE") && json.has("voter_id")) {
                return "Voter ID #" + json.optInt("voter_id") + " — Access Approved";
            }
            if (upperAction.contains("REVOKE") && json.has("voter_id")) {
                return "Voter ID #" + json.optInt("voter_id") + " — Access Revoked";
            }
            if (upperAction.contains("ADD_CANDIDATE")) {
                String candidateName = json.optString("candidate_name", json.optString("name", "N/A"));
                return "Added Candidate: " + candidateName;
            }
            if (upperAction.contains("DELETE_CANDIDATE")) {
                int candId = json.optInt("candidate_id", json.optInt("id", 0));
                return "Removed Candidate ID #" + candId;
            }
            if (upperAction.contains("EXPORT")) {
                int pollId = json.optInt("poll_id", 1);
                return "Exported CSV Report for Poll #" + pollId;
            }

            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = json.get(key);
                String cleanKey = formatActionTitle(key);
                sb.append(cleanKey).append(": ").append(val.toString()).append(" • ");
            }
            if (sb.length() > 3) {
                sb.setLength(sb.length() - 3);
            }
            return sb.toString();

        } catch (JSONException e) {
            return rawDetails;
        }
    }

    private void displayAuditLogsDialog(JSONArray array) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 32);

        if (array == null || array.length() == 0) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No audit logs recorded yet.");
            emptyText.setTextColor(Color.parseColor("#64748B"));
            emptyText.setTextSize(14);
            container.addView(emptyText);
        } else {
            for (int i = 0; i < array.length(); i++) {
                JSONObject log = array.optJSONObject(i);
                if (log == null) continue;

                String rawAction = log.optString("action", log.optString("event_type", "System Event"));
                String actionTitle = formatActionTitle(rawAction);
                String adminEmail = log.optString("admin_email", log.optString("user_email", log.optString("performed_by", "Admin")));
                String rawDetails = log.optString("target_details", log.optString("details", ""));
                String formattedDetails = formatAuditDetails(rawAction, rawDetails);
                String rawCreatedAt = log.optString("created_at", log.optString("timestamp", ""));
                String formattedTime = formatAuditTimestamp(rawCreatedAt);

                com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                cardParams.setMargins(0, 0, 0, 16);
                card.setLayoutParams(cardParams);
                card.setCardBackgroundColor(Color.WHITE);
                card.setRadius(24f);
                card.setStrokeColor(Color.parseColor("#E2E8F0"));
                card.setStrokeWidth(2);
                card.setCardElevation(0f);

                LinearLayout itemLayout = new LinearLayout(this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setPadding(24, 20, 24, 20);

                TextView actionText = new TextView(this);
                actionText.setText(actionTitle);
                actionText.setTextColor(Color.parseColor("#0F172A"));
                actionText.setTextSize(14);
                actionText.setTypeface(null, android.graphics.Typeface.BOLD);

                TextView emailText = new TextView(this);
                emailText.setText("By: " + adminEmail + (formattedTime.isEmpty() ? "" : " • " + formattedTime));
                emailText.setTextColor(Color.parseColor("#64748B"));
                emailText.setTextSize(12);

                itemLayout.addView(actionText);
                itemLayout.addView(emailText);

                if (!formattedDetails.isEmpty()) {
                    TextView detailsText = new TextView(this);
                    detailsText.setText(formattedDetails);
                    detailsText.setTextColor(Color.parseColor("#334155"));
                    detailsText.setTextSize(12);
                    detailsText.setPadding(0, 4, 0, 0);
                    itemLayout.addView(detailsText);
                }

                card.addView(itemLayout);
                container.addView(card);
            }
        }

        scrollView.addView(container);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin Audit Logs")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        dialog.show();

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.setTextColor(Color.parseColor("#1E3A8A"));
    }
}