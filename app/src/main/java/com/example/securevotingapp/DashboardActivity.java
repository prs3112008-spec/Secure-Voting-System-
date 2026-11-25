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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.securevotingapp.network.ApiClient;
import com.example.securevotingapp.network.models.CandidateItem;
import com.example.securevotingapp.security.BiometricAuthHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {

    private boolean userHasVoted = false;
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
                            // Step 1 Passed (Liveness)! Now Step 2: Hardware Biometric/PIN 2FA Lock
                            BiometricAuthHelper.authenticate(DashboardActivity.this, new BiometricAuthHelper.BiometricAuthCallback() {
                                @Override
                                public void onSuccess() {
                                    castVote(pendingCandidateId);
                                }

                                @Override
                                public void onError(String message) {
                                    pendingVerificationToken = "";
                                    Toast.makeText(DashboardActivity.this, "Authentication cancelled or failed.", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            pendingVerificationToken = "";
                            Toast.makeText(this, "Liveness check cancelled or failed.", Toast.LENGTH_SHORT).show();
                        }
                    }
            );

    private static final String TAG = "DashboardActivity";

    private android.os.CountDownTimer countDownTimer;
    private boolean isPollsClosedOrLocked = false;

    private static class PollItem {
        int pollId;
        String title;
        String description;
        boolean isActive;
        String status;
        String startTimeIso;
        String endTimeIso;

        PollItem(int pollId, String title, String description, boolean isActive, String status, String startTimeIso, String endTimeIso) {
            this.pollId = pollId;
            this.title = title;
            this.description = description;
            this.isActive = isActive;
            this.status = status;
            this.startTimeIso = startTimeIso;
            this.endTimeIso = endTimeIso;
        }

        private static long parseIsoToMillis(String isoStr) {
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

        @NonNull
        @Override
        public String toString() {
            String badge;
            long now = System.currentTimeMillis();
            long startMs = parseIsoToMillis(startTimeIso);
            long endMs = parseIsoToMillis(endTimeIso);

            if (!isActive) {
                badge = "[PAUSED]";
            } else if (startMs > 0 && now < startMs) {
                badge = "[UPCOMING]";
            } else if (endMs > 0 && now > endMs) {
                badge = "[ENDED]";
            } else {
                badge = "[LIVE]";
            }
            return badge + " Poll #" + pollId + ": " + title;
        }
    }

    private final ArrayList<PollItem> pollList = new ArrayList<>();
    private android.widget.ArrayAdapter<PollItem> pollSpinnerAdapter;
    private Spinner pollSelectorSpinner;
    private TextView pollDescriptionText;
    private String currentPollTitle = "Election";

    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout dashboardSwipeRefresh;
    androidx.recyclerview.widget.RecyclerView candidatesRecyclerView;
    ArrayList<CandidateItem> candidateItemList = new ArrayList<>();
    ArrayList<Integer> candidateIdList = new ArrayList<>(); // Stores the database IDs
    CandidateAdapter adapter;
    int currentPollId = 1; // Default Poll ID

    @Override
    protected void onResume() {
        super.onResume();
        fetchPolls();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        super.setContentView(R.layout.activity_dashboard);

        dashboardSwipeRefresh = findViewById(R.id.dashboardSwipeRefresh);
        if (dashboardSwipeRefresh != null) {
            dashboardSwipeRefresh.setOnRefreshListener(this::fetchPolls);
        }

        Button goToAdminButton = findViewById(R.id.goToAdminButton);
        goToAdminButton.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, AdminDashboardActivity.class);
            startActivity(intent);
        });

        Button goToResultsButton = findViewById(R.id.goToResultsButton);
        goToResultsButton.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, LiveResultsActivity.class);
            intent.putExtra("EXTRA_POLL_ID", currentPollId);
            intent.putExtra("EXTRA_POLL_TITLE", currentPollTitle);
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

        candidatesRecyclerView = findViewById(R.id.premiumRecyclerView);
        if (candidatesRecyclerView != null) {
            candidatesRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        }

        View pollSelectorCard = findViewById(R.id.pollSelectorCard);
        if (pollSelectorCard != null) {
            pollSelectorCard.setOnClickListener(v -> showPollSelectorBottomSheet());
        }

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String userRole = sharedPreferences.getString("role", "voter");

        TextView dashboardSubtitleText = findViewById(R.id.dashboardSubtitleText);
        View adminInfoCard = findViewById(R.id.adminInfoCard);
        Button verifyVoteButton = findViewById(R.id.verifyVoteButton);
        ProgressBar dashboardProgressBar = findViewById(R.id.dashboardProgressBar);
        TextView voterEmptyStateText = findViewById(R.id.voterEmptyStateText);

        if ("admin".equalsIgnoreCase(userRole)) {
            if (dashboardSubtitleText != null) {
                dashboardSubtitleText.setText("Authenticated Administrator Session");
            }
            goToAdminButton.setVisibility(View.VISIBLE);
            if (candidatesRecyclerView != null) candidatesRecyclerView.setVisibility(View.GONE);
            if (adminInfoCard != null) adminInfoCard.setVisibility(View.VISIBLE);
            if (verifyVoteButton != null) verifyVoteButton.setVisibility(View.GONE);
            if (dashboardProgressBar != null) dashboardProgressBar.setVisibility(View.GONE);
            if (voterEmptyStateText != null) voterEmptyStateText.setVisibility(View.GONE);
        } else {
            if (dashboardSubtitleText != null) {
                dashboardSubtitleText.setText("Authenticated Voter Session");
            }
            goToAdminButton.setVisibility(View.GONE);    // Hides Admin Panel button for voters
            if (candidatesRecyclerView != null) candidatesRecyclerView.setVisibility(View.VISIBLE);
            if (adminInfoCard != null) adminInfoCard.setVisibility(View.GONE);
            if (verifyVoteButton != null) verifyVoteButton.setVisibility(View.VISIBLE);
        }

        if (verifyVoteButton != null) {
            verifyVoteButton.setOnClickListener(v -> showVerifyVoteDialog());
        }

        adapter = new CandidateAdapter(() -> userHasVoted, () -> isPollsClosedOrLocked, position -> {
            if (position < candidateIdList.size()) {
                int selectedCandidateId = candidateIdList.get(position);
                onCandidateClick(selectedCandidateId);
            }
        });
        if (candidatesRecyclerView != null) {
            candidatesRecyclerView.setAdapter(adapter);
        }

        fetchPolls();
    }

    private void showPollSelectorBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog bottomSheetDialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 36, 48, 36);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Select Election Poll");
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        for (PollItem poll : pollList) {
            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, 16);
            card.setLayoutParams(params);
            card.setCardBackgroundColor(poll.pollId == currentPollId ? Color.parseColor("#F1F5F9") : Color.WHITE);
            card.setRadius(20f);
            card.setStrokeColor(poll.pollId == currentPollId ? Color.parseColor("#1E3A8A") : Color.parseColor("#E2E8F0"));
            card.setStrokeWidth(poll.pollId == currentPollId ? 3 : 1);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setPadding(24, 20, 24, 20);

            TextView itemText = new TextView(this);
            itemText.setText(poll.toString());
            itemText.setTextColor(Color.parseColor("#0F172A"));
            itemText.setTextSize(14);
            itemText.setTypeface(null, android.graphics.Typeface.BOLD);

            itemLayout.addView(itemText);

            if (poll.description != null && !poll.description.isEmpty()) {
                TextView descText = new TextView(this);
                descText.setText(poll.description);
                descText.setTextColor(Color.parseColor("#64748B"));
                descText.setTextSize(12);
                descText.setPadding(0, 4, 0, 0);
                itemLayout.addView(descText);
            }

            card.addView(itemLayout);
            card.setOnClickListener(v -> {
                selectPoll(poll);
                bottomSheetDialog.dismiss();
            });

            listContainer.addView(card);
        }

        scrollView.addView(listContainer);
        root.addView(scrollView);

        bottomSheetDialog.setContentView(root);
        bottomSheetDialog.show();
    }

    private void fetchPolls() {
        ApiClient.getApiService(this).getPolls().enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                if (dashboardSwipeRefresh != null) {
                    dashboardSwipeRefresh.setRefreshing(false);
                }
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        pollList.clear();
                        JsonArray array = response.body();
                        int selectedIndex = 0;

                        for (int i = 0; i < array.size(); i++) {
                            if (!array.get(i).isJsonObject()) continue;
                            JsonObject p = array.get(i).getAsJsonObject();
                            int pollId = p.has("poll_id") ? p.get("poll_id").getAsInt() : (p.has("id") ? p.get("id").getAsInt() : 1);
                            String title = p.has("title") ? p.get("title").getAsString() : (p.has("poll_title") ? p.get("poll_title").getAsString() : "Poll #" + pollId);
                            String description = p.has("description") ? p.get("description").getAsString() : "";
                            boolean isActive = !p.has("is_active") || p.get("is_active").getAsBoolean();
                            String status = p.has("status") ? p.get("status").getAsString() : (isActive ? "LIVE" : "CLOSED");
                            String startTime = p.has("start_time") && !p.get("start_time").isJsonNull() ? p.get("start_time").getAsString() : "";
                            String endTime = p.has("end_time") && !p.get("end_time").isJsonNull() ? p.get("end_time").getAsString() : "";

                            PollItem item = new PollItem(pollId, title, description, isActive, status, startTime, endTime);
                            pollList.add(item);

                            if ("LIVE".equalsIgnoreCase(status) && selectedIndex == 0) {
                                selectedIndex = i;
                            }
                        }

                        if (!pollList.isEmpty()) {
                            selectPoll(pollList.get(selectedIndex));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing polls response", e);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                if (dashboardSwipeRefresh != null) {
                    dashboardSwipeRefresh.setRefreshing(false);
                }
                Log.e(TAG, "Error fetching polls list", t);
            }
        });
    }

    private void selectPoll(PollItem poll) {
        if (poll == null) return;

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        currentPollId = poll.pollId;
        currentPollTitle = poll.title;
        userHasVoted = false;
        myVoteReceipt = SecurePrefs.get(this).getString("vote_receipt_poll_" + currentPollId, "");

        TextView voterSelectedPollText = findViewById(R.id.voterSelectedPollText);
        if (voterSelectedPollText != null) {
            voterSelectedPollText.setText(poll.toString());
        }

        TextView pollDescriptionText = findViewById(R.id.pollDescriptionText);
        if (pollDescriptionText != null) {
            if (poll.description != null && !poll.description.isEmpty()) {
                pollDescriptionText.setText(poll.description);
                pollDescriptionText.setVisibility(View.VISIBLE);
            } else {
                pollDescriptionText.setVisibility(View.GONE);
            }
        }

        candidateItemList.clear();
        candidateIdList.clear();
        if (adapter != null) {
            adapter.submitList(new ArrayList<>());
        }

        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String userRole = sharedPreferences.getString("role", "voter");

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
        hashView.setTextIsSelectable(true);
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
        if (userHasVoted) {
            String receipt = myVoteReceipt;
            if (receipt == null || receipt.isEmpty()) {
                receipt = SecurePrefs.get(this).getString("vote_receipt_poll_" + currentPollId, "");
            }

            if (!receipt.isEmpty()) {
                myVoteReceipt = receipt;
                showVoteReceiptDialog(receipt);
            } else {
                Toast.makeText(DashboardActivity.this, "Your ballot is securely recorded in the ledger.", Toast.LENGTH_SHORT).show();
            }
        } else {
            // USER HAS NOT VOTED YET: Confirm choice first
            String candidateName = "selected candidate";
            int candidateIndex = candidateIdList.indexOf(selectedCandidateId);
            if (candidateIndex != -1 && candidateIndex < candidateItemList.size()) {
                candidateName = candidateItemList.get(candidateIndex).getName();
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
        hashInput.setSingleLine(false);
        hashInput.setLines(2);
        hashInput.setMaxLines(2);
        hashInput.setTextColor(Color.parseColor("#0F172A"));
        hashInput.setTextSize(13);
        hashInput.setTypeface(android.graphics.Typeface.MONOSPACE);

        String savedHash = SecurePrefs.get(this).getString("vote_receipt_poll_" + currentPollId, myVoteReceipt);
        if (savedHash != null && !savedHash.isEmpty()) {
            myVoteReceipt = savedHash;
            hashInput.setText(savedHash);
        }

        inputLayout.addView(hashInput);
        container.addView(inputLayout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Verify Ledger Ballot Receipt")
                .setMessage("Enter your SHA-256 cryptographic receipt hash to verify ledger status.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Paste", null)
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

        Button neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutralBtn != null) {
            neutralBtn.setTextColor(Color.parseColor("#1E3A8A"));
            neutralBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence pastedText = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (pastedText != null && !pastedText.toString().trim().isEmpty()) {
                        hashInput.setText(pastedText.toString().trim());
                        Toast.makeText(DashboardActivity.this, "Pasted from clipboard", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
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
        ApiClient.getApiService(this).verifyVote(hash).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    boolean verified = !respObj.has("verified") || respObj.get("verified").getAsBoolean();
                    boolean chainIntact = !respObj.has("chain_intact") || respObj.get("chain_intact").getAsBoolean();
                    String rawTs = respObj.has("timestamp") ? respObj.get("timestamp").getAsString() : "";
                    int blockIndex = respObj.has("block_index") ? respObj.get("block_index").getAsInt() : -1;

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
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Receipt hash not found in the ledger.");
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
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(DashboardActivity.this, "Error connecting to verification server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchCandidates() {
        SharedPreferences sharedPreferences = SecurePrefs.get(this);
        String userRole = sharedPreferences.getString("role", "voter");

        ProgressBar progressBar = findViewById(R.id.dashboardProgressBar);
        TextView emptyStateText = findViewById(R.id.voterEmptyStateText);

        if ("admin".equalsIgnoreCase(userRole)) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
            if (candidatesRecyclerView != null) candidatesRecyclerView.setVisibility(View.GONE);
            return;
        }

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }

        ApiClient.getApiService(this).getCandidates(currentPollId).enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        candidateItemList.clear();
                        candidateIdList.clear();

                        JsonArray array = response.body();
                        for (int i = 0; i < array.size(); i++) {
                            if (!array.get(i).isJsonObject()) continue;
                            JsonObject candidate = array.get(i).getAsJsonObject();

                            String name = candidate.has("candidate_name") ? candidate.get("candidate_name").getAsString() : (candidate.has("name") ? candidate.get("name").getAsString() : "Unknown");
                            int candidateId = candidate.has("candidate_id") ? candidate.get("candidate_id").getAsInt() : 0;
                            String party = candidate.has("party") ? candidate.get("party").getAsString() : "Independent";
                            String photoUrl = candidate.has("photo_url") ? candidate.get("photo_url").getAsString() : "";

                            candidateItemList.add(new CandidateItem(candidateId, name, party, photoUrl));
                            candidateIdList.add(candidateId);
                        }
                        if (adapter != null) {
                            adapter.submitList(new ArrayList<>(candidateItemList));
                        }

                        if (candidateItemList.isEmpty()) {
                            if (emptyStateText != null) {
                                emptyStateText.setText("No candidates have been added to this poll yet.");
                                emptyStateText.setVisibility(View.VISIBLE);
                            }
                            if (candidatesRecyclerView != null) candidatesRecyclerView.setVisibility(View.GONE);
                        } else {
                            if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
                            if (candidatesRecyclerView != null) candidatesRecyclerView.setVisibility(View.VISIBLE);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing candidate data", e);
                        Toast.makeText(DashboardActivity.this, "Error parsing data", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(DashboardActivity.this, "Error fetching candidates", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                Toast.makeText(DashboardActivity.this, "Error fetching candidates", Toast.LENGTH_LONG).show();
            }
        });
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
        ApiClient.getApiService(this).getElectionStatus(currentPollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    TextView banner = findViewById(R.id.electionStatusBanner);
                    TextView countdownText = findViewById(R.id.countdownText);
                    com.google.android.material.card.MaterialCardView countdownCard = findViewById(R.id.countdownCard);
                    View candidateContainer = findViewById(R.id.premiumRecyclerView);

                    SharedPreferences sharedPreferences = SecurePrefs.get(DashboardActivity.this);
                    String userRole = sharedPreferences.getString("role", "voter");
                    boolean isAdmin = "admin".equalsIgnoreCase(userRole);

                    long serverTimeMillis = parseIsoToMillis(respObj.has("server_time_utc") ? respObj.get("server_time_utc").getAsString() : "");
                    long timeOffsetMillis = (serverTimeMillis > 0) ? (serverTimeMillis - System.currentTimeMillis()) : 0;
                    long currentUtcMillis = System.currentTimeMillis() + timeOffsetMillis;

                    long startTimeMillis = parseIsoToMillis(respObj.has("start_time") ? respObj.get("start_time").getAsString() : "");
                    long endTimeMillis = parseIsoToMillis(respObj.has("end_time") ? respObj.get("end_time").getAsString() : "");
                    boolean isActive = !respObj.has("is_active") || respObj.get("is_active").getAsBoolean();

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
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Failed to fetch election status", t);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private void checkVoteStatus() {
        ApiClient.getApiService(this).getVoteStatus(currentPollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    boolean hasVoted = (respObj.has("hasVoted") && respObj.get("hasVoted").getAsBoolean()) ||
                                       (respObj.has("has_voted") && respObj.get("has_voted").getAsBoolean());

                    View voterVotedBanner = findViewById(R.id.voterVotedBanner);
                    if (hasVoted) {
                        userHasVoted = true;
                        if (voterVotedBanner != null) voterVotedBanner.setVisibility(View.VISIBLE);

                        String serverHash = "";
                        if (respObj.has("receipt_hash") && !respObj.get("receipt_hash").isJsonNull()) {
                            serverHash = respObj.get("receipt_hash").getAsString();
                        } else if (respObj.has("hash") && !respObj.get("hash").isJsonNull()) {
                            serverHash = respObj.get("hash").getAsString();
                        } else if (respObj.has("current_hash") && !respObj.get("current_hash").isJsonNull()) {
                            serverHash = respObj.get("current_hash").getAsString();
                        }

                        if (!serverHash.isEmpty()) {
                            myVoteReceipt = serverHash;
                            SecurePrefs.get(DashboardActivity.this).edit().putString("vote_receipt_poll_" + currentPollId, serverHash).apply();
                        } else {
                            myVoteReceipt = SecurePrefs.get(DashboardActivity.this).getString("vote_receipt_poll_" + currentPollId, "");
                        }

                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    } else {
                        // Server responds has_voted == false -> Authoritative source of truth: reset state & purge stale receipt
                        userHasVoted = false;
                        myVoteReceipt = "";
                        if (voterVotedBanner != null) voterVotedBanner.setVisibility(View.GONE);
                        SecurePrefs.get(DashboardActivity.this).edit().remove("vote_receipt_poll_" + currentPollId).apply();

                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Error fetching vote status", t);
            }
        });
    }

    private void showVoteReceiptDialog(String voteHash) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ballot Recorded — Cryptographic Receipt")
                .setMessage("Your vote is recorded in the tamper-evident hash chain.\n\nBallot Secrecy Note: Save this receipt hash—for ballot secrecy, the server does not link it to your account.")
                .setView(createReceiptView(voteHash))
                .setCancelable(false)
                .setNeutralButton("Copy Receipt", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Ballot Receipt", voteHash);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                    }
                    Toast.makeText(DashboardActivity.this, "Receipt hash copied to clipboard", Toast.LENGTH_SHORT).show();
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
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("poll_id", currentPollId);
        requestBody.addProperty("candidate_id", candidateId);
        if (pendingVerificationToken != null && !pendingVerificationToken.isEmpty()) {
            requestBody.addProperty("verification_token", pendingVerificationToken);
        }

        ApiClient.getApiService(this).castVote(requestBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                pendingVerificationToken = "";
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    userHasVoted = true;

                    String receipt = "";
                    if (respObj.has("receipt_hash") && !respObj.get("receipt_hash").isJsonNull()) {
                        receipt = respObj.get("receipt_hash").getAsString();
                    } else if (respObj.has("receiptHash") && !respObj.get("receiptHash").isJsonNull()) {
                        receipt = respObj.get("receiptHash").getAsString();
                    } else if (respObj.has("current_hash") && !respObj.get("current_hash").isJsonNull()) {
                        receipt = respObj.get("current_hash").getAsString();
                    } else if (respObj.has("hash") && !respObj.get("hash").isJsonNull()) {
                        receipt = respObj.get("hash").getAsString();
                    }

                    if (!receipt.isEmpty()) {
                        myVoteReceipt = receipt;
                        SecurePrefs.get(DashboardActivity.this).edit().putString("vote_receipt_poll_" + currentPollId, receipt).apply();
                        showVoteReceiptDialog(receipt);
                    } else {
                        Toast.makeText(DashboardActivity.this, "Vote cast successfully!", Toast.LENGTH_LONG).show();
                    }
                    checkVoteStatus();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                } else {
                    String displayMsg = ApiClient.parseErrorMessage(response, "Unable to process vote. Please try again.");
                    Toast.makeText(getApplicationContext(), displayMsg, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                pendingVerificationToken = "";
                Toast.makeText(getApplicationContext(), "Network error. Please check your connection.", Toast.LENGTH_LONG).show();
            }
        });
    }
}