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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.securevotingapp.network.ApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AdminDashboardActivity extends AppCompatActivity {

    private static final String TAG = "AdminDashboardActivity";

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

    private static class VoterItem {
        int userId;
        String email;
        boolean isApproved;
        String role;
        JsonObject permissions;

        VoterItem(int userId, String email, boolean isApproved, String role, JsonObject permissions) {
            this.userId = userId;
            this.email = email;
            this.isApproved = isApproved;
            this.role = role != null ? role : "voter";
            this.permissions = permissions != null ? permissions : new JsonObject();
        }
    }

    private final ArrayList<VoterItem> allVotersList = new ArrayList<>();
    private String currentVoterSearchQuery = "";
    private String currentVoterFilterMode = "ALL";

    private final ArrayList<PollItem> pollList = new ArrayList<>();
    private Button createPollBtn;
    private String currentPollTitle = "Election";

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
                        Bitmap scaled = Bitmap.createScaledBitmap(original, 400, 400, true);

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
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

    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout adminSwipeRefresh;

    private long lastPausedAt = 0L;
    private static final long INACTIVITY_TIMEOUT_MS = 600_000L; // 10 minutes

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);

        if (savedInstanceState != null) {
            lastPausedAt = savedInstanceState.getLong("lastPausedAt", 0L);
        }

        SharedPreferences prefs = SecurePrefs.get(this);
        String userRole = prefs.getString("role", "voter");
        boolean isSuperAdmin = "super_admin".equalsIgnoreCase(userRole) || "admin".equalsIgnoreCase(userRole);
        boolean isSubAdmin = "sub_admin".equalsIgnoreCase(userRole);

        boolean canApproveVoters = isSuperAdmin;
        boolean canExportCsv = isSuperAdmin;
        boolean canViewResults = isSuperAdmin;

        if (isSubAdmin) {
            String permsJson = prefs.getString("permissions_json", null);
            if (permsJson != null) {
                try {
                    JsonObject pObj = JsonParser.parseString(permsJson).getAsJsonObject();
                    if (pObj.has("can_approve_voters")) canApproveVoters = pObj.get("can_approve_voters").getAsBoolean();
                    if (pObj.has("can_export_csv")) canExportCsv = pObj.get("can_export_csv").getAsBoolean();
                    if (pObj.has("can_view_results")) canViewResults = pObj.get("can_view_results").getAsBoolean();
                } catch (Exception ignored) {}
            }
        }

        adminSwipeRefresh = findViewById(R.id.adminSwipeRefresh);
        if (adminSwipeRefresh != null) {
            final boolean finalCanApproveVoters = canApproveVoters;
            adminSwipeRefresh.setOnRefreshListener(() -> {
                fetchAdminPolls(0);
                if (isSuperAdmin || finalCanApproveVoters) {
                    loadVoters();
                } else {
                    adminSwipeRefresh.setRefreshing(false);
                }
            });
        }

        com.google.android.material.appbar.AppBarLayout appBarLayout = findViewById(R.id.adminAppBarLayout);
        if (appBarLayout != null && adminSwipeRefresh != null) {
            appBarLayout.addOnOffsetChangedListener((appBar, verticalOffset) -> adminSwipeRefresh.setEnabled(verticalOffset == 0));
        }

        TextView adminConsoleSubtitleText = findViewById(R.id.adminConsoleSubtitleText);
        if (adminConsoleSubtitleText != null) {
            adminConsoleSubtitleText.setText(isSubAdmin ? "Delegated Election Officer Console" : "Election Authority System Console");
        }

        TextView electionCardTitle = findViewById(R.id.electionCardTitle);
        if (electionCardTitle != null) {
            electionCardTitle.setText(isSubAdmin ? "Election Reports & Controls" : "Election Schedule & Master Switch");
        }

        tabElectionBtn = findViewById(R.id.tabElectionBtn);
        tabCandidatesBtn = findViewById(R.id.tabCandidatesBtn);
        tabVotersBtn = findViewById(R.id.tabVotersBtn);

        sectionElection = findViewById(R.id.sectionElection);
        sectionCandidates = findViewById(R.id.sectionCandidates);
        sectionVoters = findViewById(R.id.sectionVoters);

        adminLogoutBtn = findViewById(R.id.adminLogoutBtn);

        View adminPollSelectorCard = findViewById(R.id.adminPollSelectorCard);
        if (adminPollSelectorCard != null) {
            adminPollSelectorCard.setOnClickListener(v -> showPollSelectorBottomSheet());
        }

        createPollBtn = findViewById(R.id.createPollBtn);
        if (createPollBtn != null) {
            createPollBtn.setOnClickListener(v -> showCreatePollDialog());
        }

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

        if (pickStartTimeBtn != null) pickStartTimeBtn.setOnClickListener(v -> pickDateTime(true, pickStartTimeBtn));
        if (pickEndTimeBtn != null) pickEndTimeBtn.setOnClickListener(v -> pickDateTime(false, pickEndTimeBtn));
        if (saveScheduleBtn != null) saveScheduleBtn.setOnClickListener(v -> saveElectionSchedule());

        Button quickStartPollBtn = findViewById(R.id.quickStartPollBtn);
        if (quickStartPollBtn != null) {
            quickStartPollBtn.setOnClickListener(v -> quickStartPollNow());
        }

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

        // Hide Super-Admin Controls if sub_admin
        if (isSubAdmin) {
            if (createPollBtn != null) createPollBtn.setVisibility(View.GONE);
            if (tabCandidatesBtn != null) tabCandidatesBtn.setVisibility(View.GONE);
            if (pickStartTimeBtn != null) pickStartTimeBtn.setVisibility(View.GONE);
            if (pickEndTimeBtn != null) pickEndTimeBtn.setVisibility(View.GONE);
            if (quickStartPollBtn != null) quickStartPollBtn.setVisibility(View.GONE);
            if (saveScheduleBtn != null) saveScheduleBtn.setVisibility(View.GONE);
            if (verifyChainBtn != null) verifyChainBtn.setVisibility(View.GONE);
            if (auditLogsBtn != null) auditLogsBtn.setVisibility(View.GONE);
            if (electionActiveSwitch != null) electionActiveSwitch.setVisibility(View.GONE);
        }

        // Enforce Scoped Permissions
        if (tabVotersBtn != null) {
            tabVotersBtn.setVisibility((isSuperAdmin || canApproveVoters) ? View.VISIBLE : View.GONE);
        }
        if (exportReportBtn != null) {
            exportReportBtn.setVisibility((isSuperAdmin || canExportCsv) ? View.VISIBLE : View.GONE);
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

        EditText voterSearchInput = findViewById(R.id.voterSearchInput);
        if (voterSearchInput != null) {
            voterSearchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentVoterSearchQuery = (s != null) ? s.toString().trim().toLowerCase(java.util.Locale.US) : "";
                    filterAndRenderVoters();
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        com.google.android.material.chip.ChipGroup voterFilterChipGroup = findViewById(R.id.voterFilterChipGroup);
        if (voterFilterChipGroup != null) {
            voterFilterChipGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipFilterPending) {
                    currentVoterFilterMode = "PENDING";
                } else if (checkedId == R.id.chipFilterApproved) {
                    currentVoterFilterMode = "APPROVED";
                } else if (checkedId == R.id.chipFilterSubAdmins) {
                    currentVoterFilterMode = "SUB_ADMINS";
                } else {
                    currentVoterFilterMode = "ALL";
                }
                filterAndRenderVoters();
            });
        }

        // Default tab selection for sub_admin
        if (isSubAdmin) {
            if (canApproveVoters) {
                showTab("voters");
            } else {
                showTab("election");
            }
        } else {
            showTab("election");
        }

        // Fetch polls list & load initial data
        fetchAdminPolls(0);

        // Safeguard: Only load voters if super_admin OR canApproveVoters == true
        if (isSuperAdmin || canApproveVoters) {
            loadVoters();
        }
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
                selectAdminPoll(poll);
                bottomSheetDialog.dismiss();
            });

            listContainer.addView(card);
        }

        scrollView.addView(listContainer);
        root.addView(scrollView);

        bottomSheetDialog.setContentView(root);
        bottomSheetDialog.show();
    }

    private void fetchAdminPolls(int autoSelectPollId) {
        ApiClient.getApiService(this).getPolls().enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                if (adminSwipeRefresh != null) {
                    adminSwipeRefresh.setRefreshing(false);
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

                            if (autoSelectPollId > 0 && pollId == autoSelectPollId) {
                                selectedIndex = i;
                            } else if (autoSelectPollId <= 0 && "LIVE".equalsIgnoreCase(status) && selectedIndex == 0) {
                                selectedIndex = i;
                            }
                        }

                        if (!pollList.isEmpty()) {
                            selectAdminPoll(pollList.get(selectedIndex));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing admin polls response", e);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                if (adminSwipeRefresh != null) {
                    adminSwipeRefresh.setRefreshing(false);
                }
                Log.e(TAG, "Error fetching admin polls list", t);
            }
        });
    }

    private void selectAdminPoll(PollItem poll) {
        if (poll == null) return;

        currentPollId = poll.pollId;
        currentPollTitle = poll.title;

        TextView activeStat = findViewById(R.id.activeElectionStatText);
        if (activeStat != null) {
            activeStat.setText("Poll #" + currentPollId);
        }

        TextView adminSelectedPollText = findViewById(R.id.adminSelectedPollText);
        if (adminSelectedPollText != null) {
            adminSelectedPollText.setText(poll.toString());
        }

        ApiClient.getApiService(this).getPollResults(currentPollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject obj = response.body();
                    int totalVotes = 0;
                    if (obj.has("turnout") && obj.get("turnout").isJsonObject()) {
                        totalVotes = obj.getAsJsonObject("turnout").has("total_votes_cast")
                                ? obj.getAsJsonObject("turnout").get("total_votes_cast").getAsInt() : 0;
                    }
                    TextView totalVotesStatText = findViewById(R.id.totalVotesStatText);
                    if (totalVotesStatText != null) {
                        totalVotesStatText.setText(totalVotes + (totalVotes == 1 ? " Vote" : " Votes"));
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {}
        });

        loadCurrentSchedule();
        loadAdminCandidates();
    }

    private void showCreatePollDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 24);

        com.google.android.material.textfield.TextInputLayout titleLayout = new com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        titleLayout.setHint("Poll Title");
        com.google.android.material.textfield.TextInputEditText titleInput = new com.google.android.material.textfield.TextInputEditText(titleLayout.getContext());
        titleLayout.addView(titleInput);

        com.google.android.material.textfield.TextInputLayout descLayout = new com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        descLayout.setHint("Description (e.g. Student Council 2026)");
        com.google.android.material.textfield.TextInputEditText descInput = new com.google.android.material.textfield.TextInputEditText(descLayout.getContext());
        descLayout.addView(descInput);

        com.google.android.material.button.MaterialButton startBtn = new com.google.android.material.button.MaterialButton(this);
        startBtn.setText("Set Start Date & Time");
        startBtn.setBackgroundColor(Color.parseColor("#475569"));
        startBtn.setTextColor(Color.WHITE);
        startBtn.setCornerRadius(16);

        com.google.android.material.button.MaterialButton endBtn = new com.google.android.material.button.MaterialButton(this);
        endBtn.setText("Set End Date & Time");
        endBtn.setBackgroundColor(Color.parseColor("#475569"));
        endBtn.setTextColor(Color.WHITE);
        endBtn.setCornerRadius(16);

        final String[] createStartIso = {""};
        final String[] createEndIso = {""};

        startBtn.setOnClickListener(v -> pickDateTimeForCreate(true, startBtn, createStartIso));
        endBtn.setOnClickListener(v -> pickDateTimeForCreate(false, endBtn, createEndIso));

        container.addView(titleLayout);
        container.addView(descLayout);
        container.addView(startBtn);
        container.addView(endBtn);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Create New Election Poll")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create Poll", (d, which) -> {
                    String title = titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
                    String desc = descInput.getText() != null ? descInput.getText().toString().trim() : "";

                    if (title.isEmpty()) {
                        Toast.makeText(AdminDashboardActivity.this, "Please enter a poll title", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (createStartIso[0].isEmpty() || createEndIso[0].isEmpty()) {
                        Toast.makeText(AdminDashboardActivity.this, "Please select both start and end date/time", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    createPollOnServer(title, desc, createStartIso[0], createEndIso[0]);
                })
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
        dialog.show();

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.setTextColor(Color.parseColor("#059669"));
    }

    private void pickDateTimeForCreate(boolean isStart, Button targetBtn, String[] targetIsoHolder) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, year, month, day) -> {
            new android.app.TimePickerDialog(this, (tView, hour, minute) -> {
                cal.set(year, month, day, hour, minute, 0);
                java.text.SimpleDateFormat isoFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());

                targetIsoHolder[0] = isoFormat.format(cal.getTime());
                targetBtn.setText((isStart ? "Start: " : "End: ") + displayFormat.format(cal.getTime()));
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), false).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void createPollOnServer(String title, String description, String startTime, String endTime) {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        if (!description.isEmpty()) body.addProperty("description", description);
        if (!startTime.isEmpty()) body.addProperty("start_time", startTime);
        if (!endTime.isEmpty()) body.addProperty("end_time", endTime);

        ApiClient.getApiService(this).createPoll(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String msg = respObj.has("message") ? respObj.get("message").getAsString() : "Poll created successfully!";
                    Toast.makeText(AdminDashboardActivity.this, msg, Toast.LENGTH_SHORT).show();

                    int createdPollId = -1;
                    if (respObj.has("poll") && respObj.get("poll").isJsonObject()) {
                        JsonObject pollObj = respObj.getAsJsonObject("poll");
                        if (pollObj.has("poll_id")) createdPollId = pollObj.get("poll_id").getAsInt();
                        else if (pollObj.has("id")) createdPollId = pollObj.get("id").getAsInt();
                    }
                    if (createdPollId == -1) {
                        if (respObj.has("poll_id")) createdPollId = respObj.get("poll_id").getAsInt();
                        else if (respObj.has("id")) createdPollId = respObj.get("id").getAsInt();
                    }

                    fetchAdminPolls(createdPollId);
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to create poll. Please try again.");
                    Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(AdminDashboardActivity.this, "Failed to create poll. Check connection.", Toast.LENGTH_LONG).show();
            }
        });
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

    private void addCandidateToDatabase(String candidateName) {
        if (selectedPhotoBase64 == null || selectedPhotoBase64.trim().isEmpty()) {
            Toast.makeText(AdminDashboardActivity.this, "Please select a candidate photo before adding.", Toast.LENGTH_SHORT).show();
            if (addCandidateButton != null) {
                addCandidateButton.setEnabled(true);
                addCandidateButton.setText("ADD CANDIDATE");
            }
            return;
        }

        String party = (candidatePartyInput != null) ? candidatePartyInput.getText().toString().trim() : "";

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("poll_id", currentPollId);
        jsonBody.addProperty("candidate_name", candidateName);
        jsonBody.addProperty("party", party.isEmpty() ? "Independent" : party);
        jsonBody.addProperty("photo_url", selectedPhotoBase64);

        ApiClient.getApiService(this).addCandidate(jsonBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (addCandidateButton != null) {
                    addCandidateButton.setEnabled(true);
                    addCandidateButton.setText("ADD CANDIDATE");
                }
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    String msg = respObj.has("message") ? respObj.get("message").getAsString() : "Candidate added successfully!";
                    Toast.makeText(AdminDashboardActivity.this, msg, Toast.LENGTH_LONG).show();

                    if (candidateNameInput != null) candidateNameInput.setText("");
                    if (candidatePartyInput != null) candidatePartyInput.setText("");
                    selectedPhotoBase64 = "";
                    if (candidatePhotoPreview != null) {
                        candidatePhotoPreview.setImageResource(android.R.drawable.ic_menu_myplaces);
                    }
                    loadAdminCandidates();
                } else {
                    String errorMessage = ApiClient.parseErrorMessage(response, "Failed to add candidate. Please try again.");
                    Toast.makeText(AdminDashboardActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (addCandidateButton != null) {
                    addCandidateButton.setEnabled(true);
                    addCandidateButton.setText("ADD CANDIDATE");
                }
                Toast.makeText(AdminDashboardActivity.this, "Failed to add candidate. Check connection.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadVoters() {
        ApiClient.getApiService(this).getVoters().enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                if (adminSwipeRefresh != null) {
                    adminSwipeRefresh.setRefreshing(false);
                }
                if (response.isSuccessful() && response.body() != null) {
                    allVotersList.clear();
                    JsonArray array = response.body();
                    int unapprovedCount = 0;
                    for (int i = 0; i < array.size(); i++) {
                        if (!array.get(i).isJsonObject()) continue;
                        JsonObject voter = array.get(i).getAsJsonObject();
                        int userId = voter.has("user_id") ? voter.get("user_id").getAsInt() : 0;
                        String email = voter.has("email") ? voter.get("email").getAsString() : "Unknown";
                        boolean isApproved = voter.has("is_approved") && voter.get("is_approved").getAsBoolean();
                        String role = voter.has("role") ? voter.get("role").getAsString() : "voter";

                        JsonObject perms = null;
                        if (voter.has("permissions") && voter.get("permissions").isJsonObject()) {
                            perms = voter.getAsJsonObject("permissions");
                        }

                        if (!isApproved) unapprovedCount++;
                        allVotersList.add(new VoterItem(userId, email, isApproved, role, perms));
                    }

                    TextView pendingStat = findViewById(R.id.pendingVotersStatText);
                    if (pendingStat != null) {
                        pendingStat.setText(unapprovedCount + " Pending");
                    }

                    filterAndRenderVoters();
                } else {
                    Toast.makeText(AdminDashboardActivity.this, "Failed to load voters", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                if (adminSwipeRefresh != null) {
                    adminSwipeRefresh.setRefreshing(false);
                }
                Toast.makeText(AdminDashboardActivity.this, "Failed to load voters", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterAndRenderVoters() {
        if (votersContainer == null) return;
        votersContainer.removeAllViews();

        if (allVotersList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No voters registered yet.");
            empty.setTextColor(Color.parseColor("#64748B"));
            empty.setTextSize(14);
            empty.setPadding(16, 24, 16, 24);
            empty.setGravity(Gravity.CENTER);
            votersContainer.addView(empty);
            return;
        }

        int renderedCount = 0;
        for (VoterItem voter : allVotersList) {
            boolean matchesFilter = true;
            if ("PENDING".equalsIgnoreCase(currentVoterFilterMode) && voter.isApproved) {
                matchesFilter = false;
            } else if ("APPROVED".equalsIgnoreCase(currentVoterFilterMode) && !voter.isApproved) {
                matchesFilter = false;
            } else if ("SUB_ADMINS".equalsIgnoreCase(currentVoterFilterMode) && !"sub_admin".equalsIgnoreCase(voter.role)) {
                matchesFilter = false;
            }

            boolean matchesSearch = true;
            if (!currentVoterSearchQuery.isEmpty()) {
                matchesSearch = voter.email != null && voter.email.toLowerCase(java.util.Locale.US).contains(currentVoterSearchQuery);
            }

            if (matchesFilter && matchesSearch) {
                addVoterRow(voter);
                renderedCount++;
            }
        }

        if (renderedCount == 0) {
            TextView noMatches = new TextView(this);
            noMatches.setText("No matching voters found.");
            noMatches.setTextColor(Color.parseColor("#64748B"));
            noMatches.setTextSize(14);
            noMatches.setPadding(16, 24, 16, 24);
            noMatches.setGravity(Gravity.CENTER);
            votersContainer.addView(noMatches);
        }
    }

    private void addVoterRow(VoterItem voter) {
        SharedPreferences prefs = SecurePrefs.get(this);
        String myRole = prefs.getString("role", "voter");
        boolean isSuperAdmin = "super_admin".equalsIgnoreCase(myRole) || "admin".equalsIgnoreCase(myRole);

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

        LinearLayout cardLayout = new LinearLayout(this);
        cardLayout.setOrientation(LinearLayout.VERTICAL);
        cardLayout.setPadding(24, 20, 24, 20);

        TextView emailText = new TextView(this);
        emailText.setText(voter.email);
        emailText.setTextColor(Color.parseColor("#1E293B"));
        emailText.setTextSize(15);
        emailText.setTypeface(null, android.graphics.Typeface.BOLD);
        emailText.setSingleLine(true);
        emailText.setEllipsize(android.text.TextUtils.TruncateAt.END);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, 4, 0, 0);

        TextView statusText = new TextView(this);
        statusText.setText("Status: " + (voter.isApproved ? "Approved" : "Pending"));
        statusText.setTextColor(voter.isApproved ? Color.parseColor("#15803D") : Color.parseColor("#B45309"));
        statusText.setTextSize(13);

        statusRow.addView(statusText);

        if ("sub_admin".equalsIgnoreCase(voter.role)) {
            TextView subAdminBadge = new TextView(this);
            subAdminBadge.setText("SUB-ADMIN");
            subAdminBadge.setTextColor(Color.WHITE);
            subAdminBadge.setTextSize(10);
            subAdminBadge.setTypeface(null, android.graphics.Typeface.BOLD);

            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setColor(Color.parseColor("#1E3A8A"));
            badgeBg.setCornerRadius(6 * getResources().getDisplayMetrics().density);
            subAdminBadge.setBackground(badgeBg);

            int badgePadH = (int) (8 * getResources().getDisplayMetrics().density);
            int badgePadV = (int) (2 * getResources().getDisplayMetrics().density);
            subAdminBadge.setPadding(badgePadH, badgePadV, badgePadH, badgePadV);

            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart((int) (10 * getResources().getDisplayMetrics().density));
            subAdminBadge.setLayoutParams(badgeParams);

            statusRow.addView(subAdminBadge);
        }

        cardLayout.addView(emailText);
        cardLayout.addView(statusRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        btnRow.setLayoutParams(btnRowParams);

        int padH = (int) (16 * getResources().getDisplayMetrics().density);
        int minW = (int) (84 * getResources().getDisplayMetrics().density);

        com.google.android.material.button.MaterialButton actionButton;
        if (!voter.isApproved) {
            actionButton = new com.google.android.material.button.MaterialButton(this);
            actionButton.setText("Approve");
            actionButton.setBackgroundColor(Color.parseColor("#059669"));
            actionButton.setTextColor(Color.WHITE);
        } else {
            actionButton = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.style.Widget_MaterialComponents_Button_OutlinedButton);
            actionButton.setText("Revoke");
            actionButton.setBackgroundColor(Color.WHITE);
            actionButton.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626")));
            actionButton.setStrokeWidth((int) (1.5f * getResources().getDisplayMetrics().density));
            actionButton.setTextColor(Color.parseColor("#DC2626"));
        }
        actionButton.setPadding(padH, actionButton.getPaddingTop(), padH, actionButton.getPaddingBottom());
        actionButton.setMinimumWidth(minW);
        actionButton.setGravity(Gravity.CENTER);
        actionButton.setCornerRadius((int) (16 * getResources().getDisplayMetrics().density));
        actionButton.setOnClickListener(v -> confirmUpdateVoterApproval(voter.userId, !voter.isApproved, actionButton, voter.email));

        btnRow.addView(actionButton);

        if (voter.isApproved && isSuperAdmin) {
            com.google.android.material.button.MaterialButton delegateBtn =
                    new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.style.Widget_MaterialComponents_Button_OutlinedButton);
            delegateBtn.setText("sub_admin".equalsIgnoreCase(voter.role) ? "Manage Role" : "Delegate");
            delegateBtn.setBackgroundColor(Color.WHITE);
            delegateBtn.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#1E3A8A")));
            delegateBtn.setStrokeWidth((int) (1.5f * getResources().getDisplayMetrics().density));
            delegateBtn.setTextColor(Color.parseColor("#1E3A8A"));
            delegateBtn.setPadding(padH, delegateBtn.getPaddingTop(), padH, delegateBtn.getPaddingBottom());
            delegateBtn.setMinimumWidth(minW);
            delegateBtn.setGravity(Gravity.CENTER);
            delegateBtn.setCornerRadius((int) (16 * getResources().getDisplayMetrics().density));
            LinearLayout.LayoutParams dParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dParams.setMarginStart((int) (8 * getResources().getDisplayMetrics().density));
            delegateBtn.setLayoutParams(dParams);
            delegateBtn.setOnClickListener(v -> showDelegateDialog(voter));

            btnRow.addView(delegateBtn);
        }

        cardLayout.addView(btnRow);
        card.addView(cardLayout);
        votersContainer.addView(card);
    }

    private void showDelegateDialog(VoterItem voter) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 24);

        TextView headerText = new TextView(this);
        headerText.setText("Role Delegation");
        headerText.setTextSize(18);
        headerText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerText.setTextColor(Color.parseColor("#0F172A"));
        headerText.setPadding(0, 0, 0, 4);

        TextView userEmailText = new TextView(this);
        userEmailText.setText(voter.email);
        userEmailText.setTextSize(14);
        userEmailText.setTextColor(Color.parseColor("#475569"));
        userEmailText.setPadding(0, 0, 0, 20);

        androidx.appcompat.widget.SwitchCompat subAdminSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        subAdminSwitch.setText("Promote to Sub-Admin (Election Officer)");
        subAdminSwitch.setTextColor(Color.parseColor("#0F172A"));
        boolean isCurrentlySubAdmin = "sub_admin".equalsIgnoreCase(voter.role);
        subAdminSwitch.setChecked(isCurrentlySubAdmin);

        LinearLayout permsContainer = new LinearLayout(this);
        permsContainer.setOrientation(LinearLayout.VERTICAL);
        permsContainer.setPadding(16, 16, 16, 16);
        permsContainer.setVisibility(isCurrentlySubAdmin ? View.VISIBLE : View.GONE);

        boolean hasApprove = !voter.permissions.has("can_approve_voters") || voter.permissions.get("can_approve_voters").getAsBoolean();
        boolean hasResults = !voter.permissions.has("can_view_results") || voter.permissions.get("can_view_results").getAsBoolean();
        boolean hasExport = !voter.permissions.has("can_export_csv") || voter.permissions.get("can_export_csv").getAsBoolean();

        com.google.android.material.checkbox.MaterialCheckBox cbApproveVoters = new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbApproveVoters.setText("Approve / Revoke Voters");
        cbApproveVoters.setChecked(isCurrentlySubAdmin ? hasApprove : true);

        com.google.android.material.checkbox.MaterialCheckBox cbViewResults = new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbViewResults.setText("View Live Results");
        cbViewResults.setChecked(isCurrentlySubAdmin ? hasResults : true);

        com.google.android.material.checkbox.MaterialCheckBox cbExportCsv = new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbExportCsv.setText("Export Official CSV Report");
        cbExportCsv.setChecked(isCurrentlySubAdmin ? hasExport : true);

        TextView helperLabel = new TextView(this);
        helperLabel.setText("Select at least one permission");
        helperLabel.setTextColor(Color.parseColor("#DC2626"));
        helperLabel.setTextSize(12);
        helperLabel.setPadding(0, 8, 0, 0);
        helperLabel.setVisibility(View.GONE);

        permsContainer.addView(cbApproveVoters);
        permsContainer.addView(cbViewResults);
        permsContainer.addView(cbExportCsv);
        permsContainer.addView(helperLabel);

        container.addView(headerText);
        container.addView(userEmailText);
        container.addView(subAdminSwitch);
        container.addView(permsContainer);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save Delegation", (d, which) -> {
                    boolean promote = subAdminSwitch.isChecked();
                    String targetRole = promote ? "sub_admin" : "voter";

                    JsonObject permsJson = new JsonObject();
                    permsJson.addProperty("can_approve_voters", cbApproveVoters.isChecked());
                    permsJson.addProperty("can_view_results", cbViewResults.isChecked());
                    permsJson.addProperty("can_export_csv", cbExportCsv.isChecked());

                    JsonObject payload = new JsonObject();
                    payload.addProperty("user_id", voter.userId);
                    payload.addProperty("role", targetRole);
                    payload.add("permissions", permsJson);

                    ApiClient.getApiService(AdminDashboardActivity.this).delegateRole(payload).enqueue(new Callback<JsonObject>() {
                        @Override
                        public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                            if (response.isSuccessful()) {
                                Toast.makeText(AdminDashboardActivity.this, "Role & permissions updated!", Toast.LENGTH_SHORT).show();
                                loadVoters();
                            } else {
                                String errMessage = ApiClient.parseErrorMessage(response, "Failed to delegate role.");
                                Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                            Toast.makeText(AdminDashboardActivity.this, "Failed to delegate role. Check connection.", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }

        Runnable updateGuard = () -> {
            Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (subAdminSwitch.isChecked()) {
                boolean atLeastOne = cbApproveVoters.isChecked() || cbViewResults.isChecked() || cbExportCsv.isChecked();
                helperLabel.setVisibility(atLeastOne ? View.GONE : View.VISIBLE);
                if (posBtn != null) {
                    posBtn.setEnabled(atLeastOne);
                }
            } else {
                helperLabel.setVisibility(View.GONE);
                if (posBtn != null) {
                    posBtn.setEnabled(true);
                }
            }
        };

        subAdminSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            permsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            updateGuard.run();
        });

        cbApproveVoters.setOnCheckedChangeListener((btn, isChecked) -> updateGuard.run());
        cbViewResults.setOnCheckedChangeListener((btn, isChecked) -> updateGuard.run());
        cbExportCsv.setOnCheckedChangeListener((btn, isChecked) -> updateGuard.run());

        dialog.setOnShowListener(d -> updateGuard.run());
        dialog.show();

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.setTextColor(Color.parseColor("#1E3A8A"));
        Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negBtn != null) negBtn.setTextColor(Color.parseColor("#64748B"));
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

        JsonObject body = new JsonObject();
        body.addProperty("is_approved", approve);

        ApiClient.getApiService(this).approveVoter(userId, body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(AdminDashboardActivity.this, approve ? "Voter Approved!" : "Approval Revoked", Toast.LENGTH_SHORT).show();
                    loadVoters();
                } else {
                    if (actionButton != null) {
                        actionButton.setEnabled(true);
                        actionButton.setText(approve ? "Revoke" : "Approve");
                    }
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to update approval");
                    Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (actionButton != null) {
                    actionButton.setEnabled(true);
                    actionButton.setText(approve ? "Revoke" : "Approve");
                }
                Toast.makeText(AdminDashboardActivity.this, "Failed to update approval", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String formatScheduleLocalDisplay(String isoStr) {
        if (isoStr == null || isoStr.isEmpty() || isoStr.equalsIgnoreCase("null")) return "";
        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
        };

        for (String pattern : formats) {
            try {
                java.text.SimpleDateFormat parseFormat = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                parseFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = parseFormat.parse(isoStr);
                if (date != null) {
                    java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
                    displayFormat.setTimeZone(java.util.TimeZone.getDefault());
                    return displayFormat.format(date);
                }
            } catch (Exception ignored) {}
        }
        return isoStr;
    }

    private void loadCurrentSchedule() {
        ApiClient.getApiService(this).getElectionStatus(currentPollId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    if (electionActiveSwitch != null) {
                        electionActiveSwitch.setChecked(!respObj.has("is_active") || respObj.get("is_active").getAsBoolean());
                    }
                    selectedStartIso = respObj.has("start_time") ? respObj.get("start_time").getAsString() : "";
                    selectedEndIso = respObj.has("end_time") ? respObj.get("end_time").getAsString() : "";

                    if (!selectedStartIso.isEmpty() && pickStartTimeBtn != null) {
                        String formattedStart = formatScheduleLocalDisplay(selectedStartIso);
                        pickStartTimeBtn.setText("Start: " + (formattedStart.isEmpty() ? selectedStartIso : formattedStart));
                    }
                    if (!selectedEndIso.isEmpty() && pickEndTimeBtn != null) {
                        String formattedEnd = formatScheduleLocalDisplay(selectedEndIso);
                        pickEndTimeBtn.setText("End: " + (formattedEnd.isEmpty() ? selectedEndIso : formattedEnd));
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(AdminDashboardActivity.this, "Could not load current schedule", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void pickDateTime(boolean isStart, Button targetBtn) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, year, month, day) -> {
            new android.app.TimePickerDialog(this, (tView, hour, minute) -> {
                cal.set(year, month, day, hour, minute, 0);
                java.text.SimpleDateFormat isoFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
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

    private void quickStartPollNow() {
        java.util.Calendar calNow = java.util.Calendar.getInstance();
        java.util.Calendar cal24h = java.util.Calendar.getInstance();
        cal24h.add(java.util.Calendar.DAY_OF_YEAR, 1);

        java.text.SimpleDateFormat isoFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        selectedStartIso = isoFormat.format(calNow.getTime());
        selectedEndIso = isoFormat.format(cal24h.getTime());

        if (electionActiveSwitch != null) {
            electionActiveSwitch.setChecked(true);
        }

        saveElectionSchedule();
    }

    private void saveElectionSchedule() {
        JsonObject body = new JsonObject();
        body.addProperty("poll_id", currentPollId);
        body.addProperty("start_time", selectedStartIso);
        body.addProperty("end_time", selectedEndIso);
        if (electionActiveSwitch != null) {
            body.addProperty("is_active", electionActiveSwitch.isChecked());
        }

        ApiClient.getApiService(this).updateSchedule(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String msg = response.body().has("message") ? response.body().get("message").getAsString() : "Schedule saved!";
                    Toast.makeText(AdminDashboardActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(AdminDashboardActivity.this, "Failed to save schedule", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(AdminDashboardActivity.this, "Failed to save schedule", Toast.LENGTH_SHORT).show();
            }
        });
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

        ApiClient.getApiService(this).deleteCandidate(candidateId).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(AdminDashboardActivity.this, "Candidate deleted!", Toast.LENGTH_SHORT).show();
                    loadAdminCandidates();
                } else {
                    if (deleteBtn != null) {
                        deleteBtn.setEnabled(true);
                        deleteBtn.setText("DELETE");
                    }
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to delete candidate");
                    Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (deleteBtn != null) {
                    deleteBtn.setEnabled(true);
                    deleteBtn.setText("DELETE");
                }
                Toast.makeText(AdminDashboardActivity.this, "Failed to delete candidate", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadAdminCandidates() {
        LinearLayout candidatesContainer = findViewById(R.id.candidatesContainer);
        if (candidatesContainer == null) return;

        candidatesContainer.setVisibility(View.VISIBLE);

        ApiClient.getApiService(this).getCandidates(currentPollId).enqueue(new Callback<JsonArray>() {
            @Override
            public void onResponse(@NonNull Call<JsonArray> call, @NonNull Response<JsonArray> response) {
                candidatesContainer.removeAllViews();
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JsonArray array = response.body();

                        if (array.size() == 0) {
                            TextView empty = new TextView(AdminDashboardActivity.this);
                            empty.setText("No candidates added yet.");
                            empty.setTextColor(Color.parseColor("#64748B"));
                            empty.setTextSize(14);
                            empty.setPadding(16, 24, 16, 24);
                            empty.setGravity(Gravity.CENTER);
                            candidatesContainer.addView(empty);
                            return;
                        }

                        for (int i = 0; i < array.size(); i++) {
                            if (!array.get(i).isJsonObject()) continue;
                            JsonObject c = array.get(i).getAsJsonObject();

                            int candidateId = c.has("candidate_id") ? c.get("candidate_id").getAsInt() : (c.has("id") ? c.get("id").getAsInt() : 0);
                            String name = c.has("candidate_name") ? c.get("candidate_name").getAsString() : (c.has("name") ? c.get("name").getAsString() : "Unknown");
                            String party = c.has("party") ? c.get("party").getAsString() : (c.has("party_name") ? c.get("party_name").getAsString() : "Independent");
                            String photoUrl = c.has("photo_url") ? c.get("photo_url").getAsString() : (c.has("image_url") ? c.get("image_url").getAsString() : "");

                            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(AdminDashboardActivity.this);
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

                            LinearLayout row = new LinearLayout(AdminDashboardActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setPadding(24, 20, 24, 20);
                            row.setGravity(Gravity.CENTER_VERTICAL);

                            ImageView avatarImageView = new ImageView(AdminDashboardActivity.this);
                            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(96, 96);
                            avatarParams.setMargins(0, 0, 20, 0);
                            avatarImageView.setLayoutParams(avatarParams);

                            Glide.with(AdminDashboardActivity.this)
                                    .load(photoUrl)
                                    .circleCrop()
                                    .placeholder(android.R.drawable.ic_menu_myplaces)
                                    .error(android.R.drawable.ic_menu_myplaces)
                                    .into(avatarImageView);

                            LinearLayout textLayout = new LinearLayout(AdminDashboardActivity.this);
                            textLayout.setOrientation(LinearLayout.VERTICAL);
                            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            textParams.setMarginEnd(24);
                            textLayout.setLayoutParams(textParams);

                            TextView nameText = new TextView(AdminDashboardActivity.this);
                            nameText.setText(name);
                            nameText.setTextColor(Color.parseColor("#1E293B"));
                            nameText.setTextSize(15);
                            nameText.setTypeface(null, android.graphics.Typeface.BOLD);

                            TextView partyText = new TextView(AdminDashboardActivity.this);
                            partyText.setText(party);
                            partyText.setTextColor(Color.parseColor("#475569"));
                            partyText.setTextSize(13);

                            textLayout.addView(nameText);
                            textLayout.addView(partyText);

                            com.google.android.material.button.MaterialButton deleteBtn =
                                    new com.google.android.material.button.MaterialButton(AdminDashboardActivity.this, null, com.google.android.material.R.style.Widget_MaterialComponents_Button_OutlinedButton);
                            deleteBtn.setText("DELETE");
                            deleteBtn.setTextColor(Color.parseColor("#DC2626"));
                            deleteBtn.setBackgroundColor(Color.WHITE);
                            deleteBtn.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626")));
                            deleteBtn.setStrokeWidth((int) (1.5f * getResources().getDisplayMetrics().density));
                            int candPadH = (int) (14 * getResources().getDisplayMetrics().density);
                            deleteBtn.setPadding(candPadH, deleteBtn.getPaddingTop(), candPadH, deleteBtn.getPaddingBottom());
                            deleteBtn.setMinimumWidth((int) (84 * getResources().getDisplayMetrics().density));
                            deleteBtn.setCornerRadius((int) (16 * getResources().getDisplayMetrics().density));
                            deleteBtn.setOnClickListener(v -> confirmDeleteCandidate(candidateId, deleteBtn, name));

                            row.addView(avatarImageView);
                            row.addView(textLayout);
                            row.addView(deleteBtn);
                            card.addView(row);
                            candidatesContainer.addView(card);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parsing candidates failed", e);
                        Toast.makeText(AdminDashboardActivity.this, "Failed to load candidates. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(AdminDashboardActivity.this, "Failed to load candidates. Check connection.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonArray> call, @NonNull Throwable t) {
                Toast.makeText(AdminDashboardActivity.this, "Failed to load candidates. Check connection.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void verifyChainIntegrity() {
        ApiClient.getApiService(this).verifyChainIntegrity().enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject respObj = response.body();
                    boolean isValid = respObj.has("valid") && respObj.get("valid").getAsBoolean();
                    String message = respObj.has("message") ? respObj.get("message").getAsString() : "Verification complete.";

                    new AlertDialog.Builder(AdminDashboardActivity.this)
                            .setTitle(isValid ? "Chain Integrity Verified" : "Tampering Detected!")
                            .setMessage(message)
                            .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                            .show();
                } else {
                    Toast.makeText(AdminDashboardActivity.this, "Error connecting to verification server", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Toast.makeText(AdminDashboardActivity.this, "Error connecting to verification server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchAuditLogs() {
        ApiClient.getApiService(this).getAuditLogs().enqueue(new Callback<com.google.gson.JsonElement>() {
            @Override
            public void onResponse(@NonNull Call<com.google.gson.JsonElement> call, @NonNull Response<com.google.gson.JsonElement> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        com.google.gson.JsonElement element = response.body();
                        JsonArray array = null;

                        if (element.isJsonArray()) {
                            array = element.getAsJsonArray();
                        } else if (element.isJsonObject()) {
                            JsonObject obj = element.getAsJsonObject();
                            if (obj.has("logs") && obj.get("logs").isJsonArray()) {
                                array = obj.getAsJsonArray("logs");
                            } else if (obj.has("audit_logs") && obj.get("audit_logs").isJsonArray()) {
                                array = obj.getAsJsonArray("audit_logs");
                            } else if (obj.has("auditLogs") && obj.get("auditLogs").isJsonArray()) {
                                array = obj.getAsJsonArray("auditLogs");
                            } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                                array = obj.getAsJsonArray("data");
                            }
                        }

                        displayAuditLogsDialog(array);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing audit logs", e);
                        Toast.makeText(AdminDashboardActivity.this, "Failed to parse audit logs", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to fetch audit logs");
                    Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<com.google.gson.JsonElement> call, @NonNull Throwable t) {
                Log.e(TAG, "Error fetching audit logs", t);
                String msg = t.getMessage() != null && !t.getMessage().isEmpty() ? t.getMessage() : "Failed to fetch audit logs";
                Toast.makeText(AdminDashboardActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void exportElectionReport(Button exportBtn) {
        if (exportBtn != null) {
            exportBtn.setEnabled(false);
            exportBtn.setText("EXPORTING REPORT...");
        }

        ApiClient.getApiService(this).exportResults(currentPollId).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (exportBtn != null) {
                    exportBtn.setEnabled(true);
                    exportBtn.setText("EXPORT OFFICIAL REPORT (CSV)");
                }
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        java.io.File reportsDir = new java.io.File(getCacheDir(), "reports");
                        if (!reportsDir.exists()) {
                            reportsDir.mkdirs();
                        }

                        java.io.File reportFile = new java.io.File(reportsDir, "election-report-poll-" + currentPollId + ".csv");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(reportFile);
                        fos.write(response.body().bytes());
                        fos.flush();
                        fos.close();

                        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(AdminDashboardActivity.this, getPackageName() + ".fileprovider", reportFile);

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/csv");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Official Election Report - Poll #" + currentPollId);
                        shareIntent.setClipData(android.content.ClipData.newRawUri("Official Election Report", contentUri));
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        startActivity(Intent.createChooser(shareIntent, "Share Official Election Report"));
                        Toast.makeText(AdminDashboardActivity.this, "Report generated and ready to share!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save or share CSV report", e);
                        Toast.makeText(AdminDashboardActivity.this, "Failed to generate CSV report. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    String errMessage = ApiClient.parseErrorMessage(response, "Failed to export report");
                    Toast.makeText(AdminDashboardActivity.this, errMessage, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                if (exportBtn != null) {
                    exportBtn.setEnabled(true);
                    exportBtn.setText("EXPORT OFFICIAL REPORT (CSV)");
                }
                Toast.makeText(AdminDashboardActivity.this, "Failed to export report", Toast.LENGTH_SHORT).show();
            }
        });
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
                    java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
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

    private String formatAuditDetails(String action, JsonObject json) {
        if (json == null) return "";
        String upperAction = action != null ? action.toUpperCase(java.util.Locale.US) : "";

        if (upperAction.contains("DELEGATE") || upperAction.contains("ROLE")) {
            int userId = json.has("user_id") ? json.get("user_id").getAsInt() : (json.has("voter_id") ? json.get("voter_id").getAsInt() : 0);
            String role = json.has("role") ? json.get("role").getAsString() : "";
            if ("sub_admin".equalsIgnoreCase(role)) {
                ArrayList<String> permsList = new ArrayList<>();
                if (json.has("permissions") && json.get("permissions").isJsonObject()) {
                    JsonObject perms = json.getAsJsonObject("permissions");
                    if (!perms.has("can_approve_voters") || perms.get("can_approve_voters").getAsBoolean()) permsList.add("Approve Voters");
                    if (!perms.has("can_view_results") || perms.get("can_view_results").getAsBoolean()) permsList.add("Live Results");
                    if (!perms.has("can_export_csv") || perms.get("can_export_csv").getAsBoolean()) permsList.add("Export CSV");
                }
                String permStr = permsList.isEmpty() ? "No permissions" : android.text.TextUtils.join(", ", permsList);
                return "User #" + userId + " promoted to Sub-Admin • " + permStr;
            } else {
                return "User #" + userId + " demoted to Voter";
            }
        }

        if (upperAction.contains("SCHEDULE")) {
            int pollId = json.has("poll_id") ? json.get("poll_id").getAsInt() : currentPollId;
            String startRaw = json.has("start_time") ? json.get("start_time").getAsString() : "";
            String endRaw = json.has("end_time") ? json.get("end_time").getAsString() : "";
            boolean isActive = !json.has("is_active") || json.get("is_active").getAsBoolean();

            String startFormatted = formatScheduleLocalDisplay(startRaw);
            String endFormatted = formatScheduleLocalDisplay(endRaw);

            String timeRange = (!startFormatted.isEmpty() && !endFormatted.isEmpty())
                    ? startFormatted + " – " + endFormatted
                    : (!startRaw.isEmpty() ? startRaw : "No schedule set");

            return "Poll #" + pollId + " • " + timeRange + (isActive ? " (Active)" : " (Paused)");
        }

        if (upperAction.contains("APPROVE") && json.has("voter_id")) {
            return "Voter ID #" + json.get("voter_id").getAsInt() + " — Access Approved";
        }
        if (upperAction.contains("REVOKE") && json.has("voter_id")) {
            return "Voter ID #" + json.get("voter_id").getAsInt() + " — Access Revoked";
        }
        if (upperAction.contains("ADD_CANDIDATE")) {
            String candidateName = json.has("candidate_name") ? json.get("candidate_name").getAsString() : (json.has("name") ? json.get("name").getAsString() : "N/A");
            return "Added Candidate: " + candidateName;
        }
        if (upperAction.contains("DELETE_CANDIDATE")) {
            int candId = json.has("candidate_id") ? json.get("candidate_id").getAsInt() : (json.has("id") ? json.get("id").getAsInt() : 0);
            return "Removed Candidate ID #" + candId;
        }
        if (upperAction.contains("EXPORT")) {
            int pollId = json.has("poll_id") ? json.get("poll_id").getAsInt() : currentPollId;
            return "Exported CSV Report for Poll #" + pollId;
        }

        StringBuilder sb = new StringBuilder();
        for (String key : json.keySet()) {
            JsonElement val = json.get(key);
            String cleanKey = formatActionTitle(key);
            sb.append(cleanKey).append(": ").append(val.toString()).append(" • ");
        }
        if (sb.length() > 3) {
            sb.setLength(sb.length() - 3);
        }
        return sb.toString();
    }

    private void displayAuditLogsDialog(JsonArray array) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 32);

        if (array == null || array.size() == 0) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No audit logs recorded yet.");
            emptyText.setTextColor(Color.parseColor("#64748B"));
            emptyText.setTextSize(14);
            container.addView(emptyText);
        } else {
            for (int i = 0; i < array.size(); i++) {
                if (!array.get(i).isJsonObject()) continue;
                JsonObject log = array.get(i).getAsJsonObject();

                String rawAction = log.has("action") ? log.get("action").getAsString() : (log.has("event_type") ? log.get("event_type").getAsString() : "System Event");
                String actionTitle = formatActionTitle(rawAction);
                String adminEmail = log.has("admin_email") ? log.get("admin_email").getAsString() : (log.has("user_email") ? log.get("user_email").getAsString() : (log.has("performed_by") ? log.get("performed_by").getAsString() : "Admin"));
                
                JsonObject detailsJson = null;
                if (log.has("target_details") && log.get("target_details").isJsonObject()) {
                    detailsJson = log.getAsJsonObject("target_details");
                } else if (log.has("details") && log.get("details").isJsonObject()) {
                    detailsJson = log.getAsJsonObject("details");
                } else if (log.has("target_details") && log.get("target_details").isJsonPrimitive()) {
                    try {
                        detailsJson = JsonParser.parseString(log.get("target_details").getAsString()).getAsJsonObject();
                    } catch (Exception ignored) {}
                }

                String formattedDetails = formatAuditDetails(rawAction, detailsJson);
                String rawCreatedAt = log.has("created_at") ? log.get("created_at").getAsString() : (log.has("timestamp") ? log.get("timestamp").getAsString() : "");
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

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("lastPausedAt", lastPausedAt);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lastPausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastPausedAt > 0L && (System.currentTimeMillis() - lastPausedAt) > INACTIVITY_TIMEOUT_MS) {
            SecurePrefs.clear(this);
            Toast.makeText(this, "Admin session locked due to 10 minutes of inactivity", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }
}