package com.inout.app;

import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.inout.app.databinding.ActivityEmployeeDashboardBinding;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.models.User;
import com.inout.app.utils.CentralConfig;
import com.inout.app.utils.EncryptionHelper;
import com.inout.app.utils.FirebaseManager;
import com.inout.app.utils.LocationHelper;
import com.inout.app.utils.TimeUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Main dashboard for Employees.
 * Handles navigation between Check-In/Out and Attendance History.
 * Monitors Admin Approval status and Profile completeness.
 * UPDATED: Handles Emergency Leave, Medical Leave, and Resume logic with real-time menu sync and spinning loader.
 * DYNAMIC BYPASS:
 * - Reads/Writes data directly from secondary named app "admin_app" Firestore instance.
 * - Targets profiles using the aligned anonymous UID to keep your original security rules untouched.
 */
public class EmployeeDashboardActivity extends AppCompatActivity {

    private static final String TAG = "EmployeeDashboardActivity";
    private ActivityEmployeeDashboardBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private User currentUser;
    private AttendanceRecord todayRecord;
    private ListenerRegistration userListener;
    private ListenerRegistration attendanceListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmployeeDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        
        // Target Admin's dynamic Firestore database
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary admin_app not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }

        setSupportActionBar(binding.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_employee);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();

            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_employee_checkin, 
                    R.id.nav_employee_history)
                    .build();

            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.navView, navController);
        }

        checkUserProfileAndStatus();
        observeAttendanceStatus();
    }

    private void observeAttendanceStatus() {
        FirebaseUser fbUser = mAuth.getCurrentUser();
        if (fbUser == null) return;

        // Get the active Anonymous UID of the admin_app to match your original ruleset
        String adminUid = null;
        try {
            FirebaseApp adminApp = FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME);
            adminUid = FirebaseAuth.getInstance(adminApp).getUid();
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving dynamic UID from secondary app.", e);
        }

        if (adminUid == null) {
            adminUid = fbUser.getUid(); // fallback
        }

        // Snapshot listener ensures we get the employeeId correctly and stay in sync
        userListener = db.collection("users").document(adminUid).addSnapshotListener((userDoc, error) -> {
            if (userDoc != null && userDoc.exists()) {
                currentUser = userDoc.toObject(User.class);
                if (currentUser != null && currentUser.getEmployeeId() != null) {
                    
                    if (attendanceListener != null) attendanceListener.remove();

                    String dateId = TimeUtils.getCurrentDateId();
                    String recordId = currentUser.getEmployeeId() + "_" + dateId;

                    attendanceListener = db.collection("attendance").document(recordId).addSnapshotListener((snapshot, e) -> {
                        // Trigger spinning loader during refresh
                        if (snapshot != null) {
                            binding.syncProgressBar.setVisibility(View.VISIBLE);
                            
                            todayRecord = snapshot.toObject(AttendanceRecord.class);
                            
                            // Refresh the Top Menu state
                            invalidateOptionsMenu(); 

                            // Brief delay to allow UI to settle before hiding loader
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                binding.syncProgressBar.setVisibility(View.GONE);
                            }, 800);
                        } else {
                            todayRecord = null;
                        }
                    });
                }
            }
        });
    }

    private void checkUserProfileAndStatus() {
        FirebaseUser firebaseUser = mAuth.getCurrentUser();
        if (firebaseUser == null) return;

        // Get the active Anonymous UID of the admin_app to match your original ruleset
        String adminUid = null;
        try {
            FirebaseApp adminApp = FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME);
            adminUid = FirebaseAuth.getInstance(adminApp).getUid();
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving dynamic UID from secondary app.", e);
        }

        if (adminUid == null) {
            adminUid = firebaseUser.getUid(); // fallback
        }

        db.collection("users").document(adminUid)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) return;

                    if (snapshot != null && snapshot.exists()) {
                        User user = snapshot.toObject(User.class);
                        if (user != null) {
                            if (user.getPhone() == null || user.getPhone().isEmpty() || 
                                user.getPhotoUrl() == null || user.getPhotoUrl().isEmpty()) {

                                Toast.makeText(this, "Please complete your profile first.", Toast.LENGTH_SHORT).show();
                                startActivity(new Intent(this, EmployeeProfileActivity.class));
                                return;
                            }

                            if (!user.isApproved()) {
                                showWaitingOverlay(true);
                            } else {
                                showWaitingOverlay(false);
                            }
                        }
                    }
                });
    }

    private void showWaitingOverlay(boolean show) {
        if (show) {
            binding.layoutWaitingApproval.setVisibility(View.VISIBLE);
            binding.navView.setVisibility(View.GONE);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("Pending Approval");
        } else {
            binding.layoutWaitingApproval.setVisibility(View.GONE);
            binding.navView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.employee_top_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem emergencyItem = menu.findItem(R.id.action_emergency_leave);
        MenuItem medicalItem = menu.findItem(R.id.action_medical_leave);
        MenuItem resumeItem = menu.findItem(R.id.action_resume);

        boolean checkedIn = todayRecord != null && todayRecord.getCheckInTime() != null;
        boolean checkedOut = todayRecord != null && todayRecord.getCheckOutTime() != null;

        if (emergencyItem != null) {
            boolean canEmergency = checkedIn && !checkedOut;
            emergencyItem.setEnabled(canEmergency);
            if (emergencyItem.getIcon() != null) emergencyItem.getIcon().setAlpha(canEmergency ? 255 : 128);
        }

        if (medicalItem != null) {
            boolean canMedical = !checkedIn; 
            medicalItem.setEnabled(canMedical);
            if (medicalItem.getIcon() != null) medicalItem.getIcon().setAlpha(canMedical ? 255 : 128);
        }

        if (resumeItem != null) {
            boolean canResume = !checkedIn; 
            resumeItem.setEnabled(canResume);
            if (resumeItem.getIcon() != null) resumeItem.getIcon().setAlpha(canResume ? 255 : 128);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit_profile) {
            startActivity(new Intent(this, EmployeeProfileActivity.class));
            return true;
        } else if (id == R.id.action_emergency_leave) {
            handleEmergencyLeaveRequest();
            return true;
        } else if (id == R.id.action_medical_leave) {
            handleMedicalLeaveRequest();
            return true;
        } else if (id == R.id.action_resume) {
            handleResumeRequest();
            return true;
        } else if (id == R.id.action_logout) {
            logout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handleMedicalLeaveRequest() {
        if (currentUser == null) return;
        db.collection("users").document(currentUser.getUid())
                .update("medicalLeaveStatus", "pending")
                .addOnSuccessListener(aVoid -> Toast.makeText(this, "Medical Leave Permission Requested.", Toast.LENGTH_LONG).show());
    }

    /**
     * Logic Fix: Captures Resume click and marks record with "Late Start" remark immediately.
     */
    private void handleResumeRequest() {
        if (currentUser == null) return;
        String dateId = TimeUtils.getCurrentDateId();
        String recordId = currentUser.getEmployeeId() + "_" + dateId;
        String initialRemarks = "Late Start / Resume Requested";

        // Mark that resume was requested and add initial remark for CSV clarity
        db.collection("attendance").document(recordId)
                .update("resumeRequested", true, "remarks", initialRemarks)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Resume enabled. You can now Check-In.", Toast.LENGTH_SHORT).show();
                    triggerFragmentRefresh(); // Trigger instant fragment state reload on successful update [2]
                })
                .addOnFailureListener(e -> {
                    // If record doesn't exist yet, create a shell record with the resume flag and remarks
                    AttendanceRecord newRecord = new AttendanceRecord(currentUser.getEmployeeId(), currentUser.getName(), dateId, TimeUtils.getCurrentTimestamp());
                    newRecord.setRecordId(recordId);
                    newRecord.setResumeRequested(true);
                    newRecord.setRemarks(initialRemarks);
                    db.collection("attendance").document(recordId).set(newRecord)
                            .addOnSuccessListener(aVoid -> {
                                Toast.makeText(this, "Resume enabled. You can now Check-In.", Toast.LENGTH_SHORT).show();
                                triggerFragmentRefresh(); // Trigger instant fragment state reload on successful set [2]
                            });
                });
    }

    /**
     * Safely finds the active EmployeeCheckInFragment and triggers a real-time UI refresh [2].
     */
    private void triggerFragmentRefresh() {
        try {
            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.nav_host_fragment_employee);
            if (navHostFragment != null) {
                Fragment currentFragment = navHostFragment.getChildFragmentManager()
                        .getPrimaryNavigationFragment();
                if (currentFragment instanceof EmployeeCheckInFragment) {
                    ((EmployeeCheckInFragment) currentFragment).refreshCheckInStatus();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger fragment UI refresh", e);
        }
    }

    private void handleEmergencyLeaveRequest() {
        if (todayRecord == null || currentUser == null) return;

        new LocationHelper(this).getCurrentLocation(new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationResult(Location location) {
                String leaveTime = TimeUtils.getCurrentTime();
                String leaveLoc = todayRecord.getLocationName();
                String remarks = "Emergency leave at " + leaveLoc + " took at " + leaveTime;

                db.collection("attendance").document(todayRecord.getRecordId())
                        .update("emergencyLeaveTime", leaveTime,
                                "emergencyLeaveLocation", leaveLoc,
                                "remarks", remarks)
                        .addOnSuccessListener(aVoid -> {
                            db.collection("users").document(currentUser.getUid())
                                    .update("emergencyLeaveStatus", "pending")
                                    .addOnSuccessListener(aVoid2 -> {
                                        Toast.makeText(EmployeeDashboardActivity.this, "Emergency Leave Requested.", Toast.LENGTH_LONG).show();
                                    });
                        });
            }

            @Override
            public void onError(String errorMsg) {
                Toast.makeText(EmployeeDashboardActivity.this, "Location required.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void logout() {
        if (userListener != null) userListener.remove();
        if (attendanceListener != null) attendanceListener.remove();
        mAuth.signOut();
        
        // Google OAuth sign-out pointing cleanly to CentralConfig Web Client ID
        String webClientId = CentralConfig.WEB_CLIENT_ID;
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .build();
        GoogleSignInClient googleSignInClient = GoogleSignIn.getClient(this, gso);
        googleSignInClient.signOut().addOnCompleteListener(task -> {
            EncryptionHelper.getInstance(EmployeeDashboardActivity.this).clearUserRole();
            Intent intent = new Intent(EmployeeDashboardActivity.this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}