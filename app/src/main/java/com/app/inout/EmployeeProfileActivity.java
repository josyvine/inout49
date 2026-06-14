package com.inout.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.inout.app.databinding.ActivityEmployeeProfileBinding;
import com.inout.app.models.User;
import com.inout.app.utils.FirebaseManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Activity for Employees to set up their Profile (Name, Phone).
 * ZERO BILLING SOLUTION:
 * - No Firebase Storage used.
 * - Profile Photo is retrieved directly from the Google Account photoURL.
 * - Only the URL string is stored in Firestore.
 * DYNAMIC BYPASS:
 * - Redirects all Firestore reads and writes to the secondary named app instance "admin_app".
 * - Targets profiles using the aligned persistent local UID to keep your original security rules untouched.
 */
public class EmployeeProfileActivity extends AppCompatActivity {

    private static final String TAG = "EmployeeProfileActivity";
    private ActivityEmployeeProfileBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmployeeProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Default mAuth operates on the DEFAULT FirebaseApp (which points permanently to CentralConfig)
        mAuth = FirebaseAuth.getInstance();
        
        // Initialize Firestore pointing to the secondary named "admin_app" instance
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary admin_app not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }

        // 1. Load data if user already exists
        loadCurrentUserData();

        // 2. Disable photo selection button as it's now synced with Google
        binding.btnSelectPhoto.setVisibility(View.GONE);
        
        // 3. Save Button Listener
        binding.btnSaveProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onCreate(View v) {
                // Avoid using overridden onCreate in listener context. 
                // Handled in regular onClick pattern below.
            }

            @Override
            public void onClick(View v) {
                validateAndSave();
            }
        });
    }

    private void loadCurrentUserData() {
        FirebaseUser firebaseUser = mAuth.getCurrentUser();
        if (firebaseUser == null) return;

        // Display current Google info as hint/default
        if (firebaseUser.getDisplayName() != null) {
            binding.etName.setText(firebaseUser.getDisplayName());
        }

        // Get the active persistent local UID of the admin_app to match your original ruleset
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

        // Fetch the user's profile from Firestore to see if phone is already saved
        db.collection("users").document(adminUid).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        User user = documentSnapshot.toObject(User.class);
                        if (user != null) {
                            if (user.getName() != null) binding.etName.setText(user.getName());
                            if (user.getPhone() != null) binding.etPhone.setText(user.getPhone());
                        }
                    }
                });
    }

    private void validateAndSave() {
        String name = binding.etName.getText().toString().trim();
        String phone = binding.etPhone.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            binding.etName.setError("Name required");
            return;
        }
        if (TextUtils.isEmpty(phone)) {
            binding.etPhone.setError("Phone required");
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnSaveProfile.setEnabled(false);

        // Directly proceed to save data using Google's photo URL
        saveFirestoreData(name, phone);
    }

    private void saveFirestoreData(String name, String phone) {
        FirebaseUser firebaseUser = mAuth.getCurrentUser();
        if (firebaseUser == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the active persistent local UID of the admin_app to match your original ruleset
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

        // **ZERO BILLING DESIGN**
        // We pull the URL directly from the Google Auth object provided by the system.
        String googlePhotoUrl = "";
        if (firebaseUser.getPhotoUrl() != null) {
            googlePhotoUrl = firebaseUser.getPhotoUrl().toString();
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("phone", phone);
        updates.put("photoUrl", googlePhotoUrl); // Saving the Google-hosted link

        db.collection("users").document(adminUid)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(EmployeeProfileActivity.this, "Profile Updated via Google Sync", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnSaveProfile.setEnabled(true);
                        Log.e(TAG, "Profile update failed in secondary Firestore.", e);
                        Toast.makeText(EmployeeProfileActivity.this, "Update failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }
}