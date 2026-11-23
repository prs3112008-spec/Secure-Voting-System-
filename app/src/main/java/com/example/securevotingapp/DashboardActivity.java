package com.example.securevotingapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DashboardActivity extends AppCompatActivity {

    // -1 means the user hasn't voted yet.
    private int votedCandidateId = -1;
    private String myVoteReceipt = "";
    private int pendingCandidateId = -1;
    private String pendingVerificationToken = "";

    private final ActivityResultLauncher<Intent> livenessLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && pendingCandidateId != -1) {
                            if (result.getData() != null) {
                                pendingVerificationToken = result.getData().getStringExtra("verification_token");
                            }
                            // Liveness passed! Now trigger Fingerprint Prompt
                            showBiometricPrompt(pendingCandidateId);
                        } else {
                            pendingVerificationToken = "";
                            Toast.makeText(this, "Liveness check cancelled or failed.", Toast.LENGTH_SHORT).show();
                        }
                    }
            );

    private static final String TAG = "DashboardActivity";

    private android.os.CountDownTimer countDownTimer;
    private boolean isPollsClosedOrLocked = false;

    ListView candidatesListView;
    ArrayList<String> candidateList = new ArrayList<>();
    ArrayList<Integer> candidateIdList = new ArrayList<>(); // Stores the database IDs
    ArrayList<String> candidatePartyList = new ArrayList<>();
    ArrayList<String> candidatePhotoList = new ArrayList<>();
    CandidateAdapter adapter;
    int currentPollId = 1; // Poll ID is 1

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        super.setContentView(R.layout.activity_dashboard);

        Button goToAdminButton = findViewById(R.id.goToAdminButton);
        goToAdminButton.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, AdminDashboardActivity.class);
            startActivity(intent);
        });

        Button goToResultsButton = findViewById(R.id.goToResultsButton);
        goToResultsButton.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, LiveResultsActivity.class);
            startActivity(intent);
        });

        Button logoutButton = findViewById(R.id.logoutButton);
        logoutButton.setOnClickListener(v -> {
            SecurePrefs.clear(DashboardActivity.this);

            // Redirect back to MainActivity
            Intent intent = new Intent(DashboardActivity.this, MainActivity.class);

            // Clear the activity stack so the user can't press 'back' to re-enter
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        candidatesListView = findViewById(R.id.premiumListView);

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String userRole = sharedPreferences.getString("role", "voter");

        View adminInfoCard = findViewById(R.id.adminInfoCard);
        Button verifyVoteButton = findViewById(R.id.verifyVoteButton);
        ProgressBar dashboardProgressBar = findViewById(R.id.dashboardProgressBar);
        TextView voterEmptyStateText = findViewById(R.id.voterEmptyStateText);

        if ("admin".equalsIgnoreCase(userRole)) {
            goToAdminButton.setVisibility(View.VISIBLE);
            candidatesListView.setVisibility(View.GONE); // Hides TAP TO VOTE cards for admins
            if (adminInfoCard != null) adminInfoCard.setVisibility(View.VISIBLE);
            if (verifyVoteButton != null) verifyVoteButton.setVisibility(View.GONE);
            if (dashboardProgressBar != null) dashboardProgressBar.setVisibility(View.GONE);
            if (voterEmptyStateText != null) voterEmptyStateText.setVisibility(View.GONE);
        } else {
            goToAdminButton.setVisibility(View.GONE);    // Hides Admin Panel button for voters
            candidatesListView.setVisibility(View.VISIBLE);
            if (adminInfoCard != null) adminInfoCard.setVisibility(View.GONE);
            if (verifyVoteButton != null) verifyVoteButton.setVisibility(View.VISIBLE);
        }

        if (verifyVoteButton != null) {
            verifyVoteButton.setOnClickListener(v -> showVerifyVoteDialog());
        }

        adapter = new CandidateAdapter(this, candidateList, candidateIdList, candidatePartyList, candidatePhotoList, () -> votedCandidateId, () -> isPollsClosedOrLocked, position -> {
            if (position < candidateIdList.size()) {
                int selectedCandidateId = candidateIdList.get(position);
                onCandidateClick(selectedCandidateId);
            }
        });
        candidatesListView.setAdapter(adapter);

        // Detects when candidate in list is tapped
        candidatesListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < candidateIdList.size()) {
                int selectedCandidateId = candidateIdList.get(position);
                onCandidateClick(selectedCandidateId);
            }
        });

        if (!"admin".equalsIgnoreCase(userRole)) {
            fetchCandidates();
            checkVoteStatus();
        }
        checkElectionStatus();
    }

    private View createReceiptView(String voteHash) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 32);

        TextView titleView = new TextView(this);
        titleView.setText("Cryptographic Hash Verification");
        titleView.setTextColor(Color.parseColor("#0F172A"));
        titleView.setTextSize(14);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 16);

        TextView hashView = new TextView(this);
        hashView.setText(voteHash);
        hashView.setTextColor(Color.parseColor("#1E3A8A"));
        hashView.setTextSize(13);
        hashView.setTypeface(android.graphics.Typeface.MONOSPACE);
        hashView.setBackgroundColor(Color.parseColor("#F1F5F9"));
        hashView.setPadding(24, 24, 24, 24);

        container.addView(titleView);
        container.addView(hashView);
        return container;
    }

    private void onCandidateClick(int selectedCandidateId) {
        if (votedCandidateId != -1) {
            if (selectedCandidateId == votedCandidateId) {
                // Show the cryptographic receipt
                String hashToCopy = myVoteReceipt.isEmpty() ? "Verified in database" : myVoteReceipt;
                AlertDialog dialog = new AlertDialog.Builder(DashboardActivity.this)
                        .setTitle("Verified Vote Receipt")
                        .setMessage("Your vote is securely recorded in the database.")
                        .setView(createReceiptView(hashToCopy))
                        .setNeutralButton("Copy Hash", (d, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("Vote Receipt Hash", hashToCopy);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip);
                            }
                            Toast.makeText(DashboardActivity.this, "Receipt hash copied to clipboard!", Toast.LENGTH_SHORT).show();
                        })
                        .setPositiveButton("Close", null)
                        .create();

                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                }
                dialog.show();

                Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (positiveBtn != null) positiveBtn.setTextColor(Color.parseColor("#1E3A8A"));
                Button neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (neutralBtn != null) neutralBtn.setTextColor(Color.parseColor("#1E3A8A"));
            } else {
                Toast.makeText(DashboardActivity.this, "You already voted for someone else!", Toast.LENGTH_SHORT).show();
            }
        } else {
            // USER HAS NOT VOTED YET: Confirm choice first
            String candidateName = "selected candidate";
            int candidateIndex = candidateIdList.indexOf(selectedCandidateId);
            if (candidateIndex != -1 && candidateIndex < candidateList.size()) {
                candidateName = candidateList.get(candidateIndex);
            }

            final String finalCandidateName = candidateName;

            AlertDialog confirmDialog = new AlertDialog.Builder(DashboardActivity.this)
                    .setTitle("Confirm Vote")
                    .setMessage("Are you sure you want to vote for " + finalCandidateName + "? This action cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Proceed", (dialog, which) -> {
                        pendingCandidateId = selectedCandidateId;
                        Intent intent = new Intent(DashboardActivity.this, LivenessActivity.class);
                        intent.putExtra("mode", "VERIFY");
                        livenessLauncher.launch(intent);
                    })
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

    private void showVerifyVoteDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 24);

        com.google.android.material.textfield.TextInputLayout inputLayout =
                new com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        inputLayout.setHint("Paste your SHA-256 Receipt Hash");
        inputLayout.setBoxStrokeColor(Color.parseColor("#1E3A8A"));
        inputLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#1E3A8A")));

        com.google.android.material.textfield.TextInputEditText hashInput =
                new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        hashInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        hashInput.setTextColor(Color.parseColor("#0F172A"));
        hashInput.setTextSize(14);
        hashInput.setTypeface(android.graphics.Typeface.MONOSPACE);

        if (myVoteReceipt != null && !myVoteReceipt.isEmpty()) {
            hashInput.setText(myVoteReceipt);
        }

        inputLayout.addView(hashInput);
        container.addView(inputLayout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Verify Ledger Ballot Receipt")
                .setMessage("Enter your SHA-256 cryptographic receipt hash to verify ledger status.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Verify", (d, which) -> {
                    String inputHash = hashInput.getText() != null ? hashInput.getText().toString().trim() : "";
                    if (inputHash.isEmpty()) {
                        Toast.makeText(DashboardActivity.this, "Please enter a valid receipt hash", Toast.LENGTH_SHORT).show();
                    } else {
                        queryVerifyVote(inputHash);
                    }
                })
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        dialog.show();

        Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) positiveBtn.setTextColor(Color.parseColor("#1E3A8A"));
        Button negativeBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negativeBtn != null) negativeBtn.setTextColor(Color.parseColor("#64748B"));
    }

    private String formatLocalTimestamp(String rawTs) {
        if (rawTs == null || rawTs.isEmpty() || rawTs.equalsIgnoreCase("null")) {
            return null;
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
                    java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
                    displayFormat.setTimeZone(java.util.TimeZone.getDefault());
                    return displayFormat.format(date);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void queryVerifyVote(String hash) {
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/verify/" + hash;

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    boolean verified = response.optBoolean("verified", true);
                    boolean chainIntact = response.optBoolean("chain_intact", true);
                    String rawTs = response.optString("timestamp", response.optString("vote_timestamp", response.optString("created_at", "")));
                    int blockIndex = response.optInt("block_index", response.optInt("block", response.optInt("index", -1)));

                    String formattedLocalTime = formatLocalTimestamp(rawTs);

                    StringBuilder msg = new StringBuilder();
                    msg.append("Receipt Status: ").append(verified ? "VERIFIED IN LEDGER" : "UNVERIFIED").append("\n");
                    msg.append("Hash Chain Integrity: ").append(chainIntact ? "INTACT" : "COMPROMISED").append("\n");

                    if (blockIndex != -1) {
                        msg.append("Ledger Block: #").append(blockIndex).append("\n");
                    } else if (formattedLocalTime == null) {
                        msg.append("Ledger Block: Recorded in Chain\n");
                    }

                    if (formattedLocalTime != null) {
                        msg.append("Recorded: ").append(formattedLocalTime).append("\n");
                    }

                    msg.append("\nBallot Secrecy Note: Vote choice remains anonymous and cryptographically protected.");

                    AlertDialog resultDialog = new AlertDialog.Builder(DashboardActivity.this)
                            .setTitle(verified ? "Receipt Verified" : "Verification Warning")
                            .setMessage(msg.toString())
                            .setPositiveButton("OK", null)
                            .create();

                    if (resultDialog.getWindow() != null) {
                        resultDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                    }
                    resultDialog.show();

                    Button posBtn = resultDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (posBtn != null) posBtn.setTextColor(Color.parseColor("#1E3A8A"));
                },
                error -> {
                    String errMessage = "Receipt hash not found in the ledger.";
                    if (error.networkResponse != null) {
                        if (error.networkResponse.statusCode == 429) {
                            errMessage = "Too many attempts. Please wait a few minutes and try again.";
                        } else if (error.networkResponse.data != null) {
                            try {
                                String body = new String(error.networkResponse.data, "UTF-8");
                                JSONObject errObj = new JSONObject(body);
                                errMessage = errObj.optString("error", errObj.optString("message", errMessage));
                            } catch (Exception ignored) {}
                        }
                    }

                    AlertDialog errDialog = new AlertDialog.Builder(DashboardActivity.this)
                            .setTitle("Verification Result")
                            .setMessage(errMessage)
                            .setPositiveButton("OK", null)
                            .create();

                    if (errDialog.getWindow() != null) {
                        errDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                    }
                    errDialog.show();

                    Button posBtn = errDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (posBtn != null) posBtn.setTextColor(Color.parseColor("#1E3A8A"));
                }
        );

        request.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        Volley.newRequestQueue(this).add(request);
    }

    private void showBiometricPrompt(int candidateId) {
        Executor executor = ContextCompat.getMainExecutor(DashboardActivity.this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(DashboardActivity.this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Toast.makeText(DashboardActivity.this, "Auth error: " + errString, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        castVote(candidateId);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Toast.makeText(DashboardActivity.this, "Fingerprint not recognized.", Toast.LENGTH_SHORT).show();
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify Identity")
                .setSubtitle("Confirm your fingerprint to cast your secure vote")
                .setNegativeButtonText("Cancel")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void fetchCandidates() {
        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String userRole = sharedPreferences.getString("role", "voter");

        ProgressBar progressBar = findViewById(R.id.dashboardProgressBar);
        TextView emptyStateText = findViewById(R.id.voterEmptyStateText);

        if ("admin".equalsIgnoreCase(userRole)) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
            if (candidatesListView != null) candidatesListView.setVisibility(View.GONE);
            return;
        }

        String url = "https://secure-voting-system-9rnd.onrender.com/api/candidates/1";
        String token = sharedPreferences.getString("jwt_token", null);

        if (token == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }

        JsonArrayRequest jsonArrayRequest = new JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    try {
                        candidateList.clear();
                        candidateIdList.clear();
                        candidatePartyList.clear();
                        candidatePhotoList.clear();

                        for (int i = 0; i < response.length(); i++) {
                            JSONObject candidate = response.getJSONObject(i);

                            String name = candidate.optString("candidate_name", candidate.optString("name", "Unknown"));
                            int candidateId = candidate.getInt("candidate_id");
                            String party = candidate.optString("party", candidate.optString("party_name", "Independent"));
                            String photoUrl = candidate.optString("photo_url", candidate.optString("image_url", ""));

                            candidateList.add(name);
                            candidateIdList.add(candidateId);
                            candidatePartyList.add(party);
                            candidatePhotoList.add(photoUrl);
                        }
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }

                        if (candidateList.isEmpty()) {
                            if (emptyStateText != null) emptyStateText.setVisibility(View.VISIBLE);
                            if (candidatesListView != null) candidatesListView.setVisibility(View.GONE);
                        } else {
                            if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
                            if (candidatesListView != null) candidatesListView.setVisibility(View.VISIBLE);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing candidate data", e);
                        Toast.makeText(DashboardActivity.this, "Error parsing data", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    Toast.makeText(DashboardActivity.this, "Error fetching candidates", Toast.LENGTH_LONG).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + token);
                return headers;
            }
        };

        jsonArrayRequest.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(jsonArrayRequest);
    }

    private long parseIsoToMillis(String isoStr) {
        if (isoStr == null || isoStr.isEmpty() || isoStr.equalsIgnoreCase("null")) return 0;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date date = sdf.parse(isoStr.length() >= 19 ? isoStr.substring(0, 19) : isoStr);
            return (date != null) ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkElectionStatus() {
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/election-status/1";

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET, url, null,
                response -> {
                    String status = response.optString("status", "LIVE");
                    TextView banner = findViewById(R.id.electionStatusBanner);
                    TextView countdownText = findViewById(R.id.countdownText);
                    com.google.android.material.card.MaterialCardView countdownCard = findViewById(R.id.countdownCard);
                    View candidateContainer = findViewById(R.id.premiumListView);

                    SharedPreferences sharedPreferences = SecurePrefs.get(DashboardActivity.this);
                    String userRole = sharedPreferences.getString("role", "voter");
                    boolean isAdmin = "admin".equalsIgnoreCase(userRole);

                    long serverTimeMillis = parseIsoToMillis(response.optString("server_time_utc", ""));
                    long timeOffsetMillis = (serverTimeMillis > 0) ? (serverTimeMillis - System.currentTimeMillis()) : 0;
                    long currentUtcMillis = System.currentTimeMillis() + timeOffsetMillis;

                    long startTimeMillis = parseIsoToMillis(response.optString("start_time", ""));
                    long endTimeMillis = parseIsoToMillis(response.optString("end_time", ""));
                    boolean isActive = response.optBoolean("is_active", true);

                    if (countDownTimer != null) {
                        countDownTimer.cancel();
                    }

                    if (!isActive) {
                        isPollsClosedOrLocked = true;
                        if (banner != null) {
                            banner.setText("Election Closed - View Live Results for final standings.");
                            banner.setBackgroundColor(Color.parseColor("#DC2626"));
                            banner.setVisibility(View.VISIBLE);
                        }
                        if (countdownText != null) countdownText.setText("Polls are closed");
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#DC2626"));
                        if (candidateContainer != null) candidateContainer.setVisibility(View.GONE);
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }

                    // Safeguard: is_active is true and end_time is null/empty/0
                    if (endTimeMillis <= 0 && startTimeMillis <= 0) {
                        isPollsClosedOrLocked = false;
                        if (banner != null) banner.setVisibility(View.GONE);
                        if (countdownText != null) countdownText.setText("Polls are open");
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#059669"));
                        if (candidateContainer != null && !isAdmin) candidateContainer.setVisibility(View.VISIBLE);
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }

                    long millisUntilTarget = 0;
                    final boolean isWaitingToOpen;

                    if (startTimeMillis > currentUtcMillis) {
                        isWaitingToOpen = true;
                        millisUntilTarget = startTimeMillis - currentUtcMillis;
                        isPollsClosedOrLocked = true;
                        if (banner != null) {
                            banner.setText("Election has not started yet.");
                            banner.setBackgroundColor(Color.parseColor("#D97706"));
                            banner.setVisibility(View.VISIBLE);
                        }
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#D97706"));
                        if (candidateContainer != null) candidateContainer.setVisibility(View.GONE);
                    } else if (endTimeMillis > 0 && currentUtcMillis < endTimeMillis) {
                        isWaitingToOpen = false;
                        millisUntilTarget = endTimeMillis - currentUtcMillis;
                        isPollsClosedOrLocked = false;
                        if (banner != null) banner.setVisibility(View.GONE);
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#1E3A8A"));
                        if (candidateContainer != null && !isAdmin) candidateContainer.setVisibility(View.VISIBLE);
                    } else if (endTimeMillis <= 0 && isActive) {
                        isPollsClosedOrLocked = false;
                        if (banner != null) banner.setVisibility(View.GONE);
                        if (countdownText != null) countdownText.setText("Polls are open");
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#059669"));
                        if (candidateContainer != null && !isAdmin) candidateContainer.setVisibility(View.VISIBLE);
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    } else {
                        isPollsClosedOrLocked = true;
                        if (banner != null) {
                            banner.setText("Election Closed - View Live Results for final standings.");
                            banner.setBackgroundColor(Color.parseColor("#DC2626"));
                            banner.setVisibility(View.VISIBLE);
                        }
                        if (countdownText != null) countdownText.setText("Polls are closed");
                        if (countdownCard != null) countdownCard.setCardBackgroundColor(Color.parseColor("#DC2626"));
                        if (candidateContainer != null) candidateContainer.setVisibility(View.GONE);
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }

                    if (adapter != null) adapter.notifyDataSetChanged();

                    countDownTimer = new android.os.CountDownTimer(millisUntilTarget, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            long seconds = millisUntilFinished / 1000;
                            long hours = seconds / 3600;
                            long minutes = (seconds % 3600) / 60;
                            long secs = seconds % 60;

                            String formattedTime;
                            if (hours >= 24) {
                                long days = hours / 24;
                                long remainingHours = hours % 24;
                                formattedTime = String.format(java.util.Locale.US, "%dd %02dh %02dm %02ds", days, remainingHours, minutes, secs);
                            } else {
                                formattedTime = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
                            }

                            if (countdownText != null) {
                                countdownText.setText((isWaitingToOpen ? "Polls open in " : "Polls close in ") + formattedTime);
                            }
                        }

                        @Override
                        public void onFinish() {
                            checkElectionStatus();
                        }
                    }.start();
                },
                error -> Log.e(TAG, "Failed to fetch election status")
        );

        request.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        Volley.newRequestQueue(this).add(request);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private void checkVoteStatus() {
        // Your new Node.js status endpoint for poll_id 1
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/status/1";

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String token = sharedPreferences.getString("jwt_token", null);

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    try {
                        boolean hasVoted = response.getBoolean("hasVoted");
                        if (hasVoted) {
                            // Store the candidate they voted for
                            votedCandidateId = response.getInt("candidate_id");
                            if (response.has("receipt_hash")) {
                                myVoteReceipt = response.getString("receipt_hash"); // Save the secure hash!
                            }

                            // Refresh the list so the buttons update their colors
                            if (adapter != null) {
                                adapter.notifyDataSetChanged();
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing vote status response", e);
                    }
                },
                error -> Log.e(TAG, "Error fetching vote status")
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + token);
                return headers;
            }
        };

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(jsonObjectRequest);
    }

    private void showVoteReceiptDialog(String voteHash) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vote Confirmed & Secured")
                .setMessage("Your vote is recorded in the tamper-evident hash chain.")
                .setView(createReceiptView(voteHash))
                .setCancelable(false)
                .setNeutralButton("Copy Hash", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Vote Receipt Hash", voteHash);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                    }
                    Toast.makeText(DashboardActivity.this, "Receipt hash copied!", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Done", (d, which) -> d.dismiss())
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        dialog.show();

        Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) positiveBtn.setTextColor(Color.parseColor("#1E3A8A"));
        Button neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutralBtn != null) neutralBtn.setTextColor(Color.parseColor("#1E3A8A"));
    }

    private void castVote(int candidateId) {
        // This is your Node.js backend endpoint for casting votes
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/cast";

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String token = sharedPreferences.getString("jwt_token", null);

        // We package poll_id, candidate_id, and verification_token into the request
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("poll_id", 1);          // Poll ID
            requestBody.put("candidate_id", candidateId); // The candidate you tapped
            if (pendingVerificationToken != null && !pendingVerificationToken.isEmpty()) {
                requestBody.put("verification_token", pendingVerificationToken);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error building request body", e);
        }

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                url,
                requestBody,
                response -> {
                    pendingVerificationToken = "";
                    votedCandidateId = candidateId;
                    String voteHash = response.optString("current_hash", response.optString("receipt_hash", "Hash unavailable"));
                    myVoteReceipt = voteHash;
                    showVoteReceiptDialog(voteHash);
                    checkVoteStatus();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(DashboardActivity.this, "Vote cast successfully!", Toast.LENGTH_LONG).show();
                },
                error -> {
                    pendingVerificationToken = "";
                    String displayMsg = "Network error. Please check your connection.";
                    if (error.networkResponse != null && error.networkResponse.statusCode == 429) {
                        displayMsg = "Too many attempts. Please wait a few minutes and try again.";
                    } else if (error instanceof com.android.volley.TimeoutError) {
                        displayMsg = "Server took too long to respond. Try again.";
                    } else if (error.networkResponse != null && error.networkResponse.data != null) {
                        try {
                            String body = new String(error.networkResponse.data, "UTF-8");
                            JSONObject errObj = new JSONObject(body);
                            if (errObj.has("error")) {
                                displayMsg = errObj.getString("error");
                            } else if (errObj.has("message")) {
                                displayMsg = errObj.getString("message");
                            }
                        } catch (Exception e) {
                            displayMsg = "Unable to process vote. Please try again.";
                        }
                    }
                    Toast.makeText(getApplicationContext(), displayMsg, Toast.LENGTH_LONG).show();
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + token);
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                60000,
                0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(jsonObjectRequest);
    }

    // Sends the POST request to cast vote
    private void castVote(int pollId, int candidateId) {
        String url = "https://secure-voting-system-9rnd.onrender.com/api/votes/cast";

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String token = sharedPreferences.getString("jwt_token", null);

        // 1. Create the JSON body required by backend
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("poll_id", pollId);
            jsonBody.put("candidate_id", candidateId);
            if (pendingVerificationToken != null && !pendingVerificationToken.isEmpty()) {
                jsonBody.put("verification_token", pendingVerificationToken);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error building vote request body", e);
        }

        // 2. Make the POST request
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                url,
                jsonBody,
                response -> {
                    pendingVerificationToken = "";
                    try {
                        String msg = response.getString("message");
                        Toast.makeText(DashboardActivity.this, msg, Toast.LENGTH_LONG).show();
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing vote response", e);
                    }
                },
                error -> {
                    pendingVerificationToken = "";
                    String errorMessage = "Unable to process vote. Please try again.";
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
                    Toast.makeText(DashboardActivity.this, errorMessage, Toast.LENGTH_LONG).show();
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

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                60000,
                0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        RequestQueue queue = Volley.newRequestQueue(this);
        queue.add(jsonObjectRequest);
    }
}