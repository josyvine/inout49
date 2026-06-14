package com.inout.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.inout.app.databinding.ActivityLoginBinding;
import com.inout.app.models.User;
import com.inout.app.utils.CentralConfig;
import com.inout.app.utils.EncryptionHelper;
import com.inout.app.utils.FirebaseManager;

/**
 * Handles Google Sign-In and initial User Profile creation.
 * ZERO BILLING DESIGN:
 * - Retrieves Google Profile Photo URL directly from the Auth object.
 * - Saves the URL as a string in Firestore.
 * UPDATED: Integrated AdMob Banner Ad in the footer.
 * DYNAMIC BYPASS:
 * - Google Sign-In is executed on the [DEFAULT] app linked to CentralConfig.
 * - Profiles and User Firestore records are stored on the secondary named app "admin_app" 
 *   using aligned persistent Email/Password UIDs to keep your original security rules untouched.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private ActivityLoginBinding binding;
    
    private GoogleSignInClient mGoogleSignInClient;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private AdView mAdView;
    
    private String expectedRole;

    private final ActivityResultLauncher<Intent> signInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        if (account != null) {
                            firebaseAuthWithGoogle(account.getIdToken());
                        }
                    } catch (ApiException e) {
                        Log.w(TAG, "Google sign in failed", e);
                        updateUI(null);
                        Toast.makeText(this, "Google Sign-In Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    updateUI(null);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
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

        EncryptionHelper encryptionHelper = EncryptionHelper.getInstance(this);
        expectedRole = encryptionHelper.getUserRole();
        
        // Dynamic bypass: Request the central Web Client ID permanently
        String webClientId = CentralConfig.WEB_CLIENT_ID;

        if (webClientId == null || "508928966890-1lvcph6h5tvmi2hfp06ksk1m69hgr3mg.apps.googleusercontent.com".equals(webClientId)) {
            Toast.makeText(this, "Configuration Error: Central Google Client ID not configured inside CentralConfig.java.", Toast.LENGTH_LONG).show();
            binding.btnGoogleSignIn.setEnabled(false);
            return;
        }

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build();

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        String companyName = encryptionHelper.getCompanyName();
        binding.tvLoginTitle.setText("Login to " + companyName);
        binding.tvLoginSubtitle.setText("Role: " + (expectedRole != null ? expectedRole.toUpperCase() : "UNKNOWN"));

        binding.btnGoogleSignIn.setOnClickListener(v -> signIn());

        // NEW: Load AdMob Banner Ad
        mAdView = findViewById(R.id.adView_login);
        AdRequest adRequest = new AdRequest.Builder().build();
        mAdView.loadAd(adRequest);
    }

    /**
     * Silently authenticates the user on the secondary Admin database ("admin_app")
     * using their verified Google identity and a mathematically computed secure password.
     */
    private void authenticateSecondaryApp(FirebaseUser firebaseUser, OnCompleteListener<AuthResult> onComplete) {
        try {
            FirebaseApp adminApp = FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME);
            FirebaseAuth adminAuth = FirebaseAuth.getInstance(adminApp);
            
            String email = firebaseUser.getEmail();
            String googleUid = firebaseUser.getUid();
            
            if (email == null || email.isEmpty()) {
                Log.e(TAG, "Email is missing from verified Google account.");
                Toast.makeText(this, "Authentication failed. Google Email required.", Toast.LENGTH_LONG).show();
                return;
            }

            String securePassword = calculateSecurePassword(email, googleUid);

            // Silently attempt login
            adminAuth.signInWithEmailAndPassword(email, securePassword)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Silent email/password sign-in successful on admin_app.");
                            onComplete.onComplete(task);
                        } else {
                            // If account does not exist on Admin's project yet, silently register them
                            Log.d(TAG, "Secondary account does not exist. Attempting silent registration.");
                            adminAuth.createUserWithEmailAndPassword(email, securePassword)
                                    .addOnCompleteListener(onComplete);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error executing secondary silent authentication.", e);
        }
    }

    /**
     * Generates a predictable, secure, and unique 16-character password 
     * based on a cryptographic SHA-256 hash of their credentials.
     */
    private String calculateSecurePassword(String email, String googleUid) {
        try {
            String input = email + googleUid + "InOutAppSecurePasswordSalt2026";
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute secure password hash.", e);
            return "FallbackPass123!";
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
            
            // Asynchronous gate: Ensure persistent secondary Email/Password login succeeds before reading Firestore records
            authenticateSecondaryApp(currentUser, task -> {
                binding.progressBar.setVisibility(View.GONE);
                if (task.isSuccessful()) {
                    checkUserInFirestore(currentUser);
                } else {
                    Log.e(TAG, "Secondary persistent authentication failed onStart.", task.getException());
                    Toast.makeText(LoginActivity.this, "Database Connection Failed. Please retry.", Toast.LENGTH_SHORT).show();
                    mAuth.signOut();
                    updateUI(null);
                }
            });
        }
    }

    private void signIn() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnGoogleSignIn.setVisibility(View.INVISIBLE);
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        signInLauncher.launch(signInIntent);
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                binding.progressBar.setVisibility(View.VISIBLE);
                                
                                // Perform silent Email/Password registration/login on the Admin project
                                authenticateSecondaryApp(user, secondaryTask -> {
                                    binding.progressBar.setVisibility(View.GONE);
                                    if (secondaryTask.isSuccessful()) {
                                        checkUserInFirestore(user);
                                    } else {
                                        Log.e(TAG, "Secondary persistent authentication failed on login handshake.", secondaryTask.getException());
                                        Toast.makeText(LoginActivity.this, "Failed to connect to dynamic database.", Toast.LENGTH_SHORT).show();
                                        mAuth.signOut();
                                        updateUI(null);
                                    }
                                });
                            }
                        } else {
                            Log.w(TAG, "signInWithCredential:failure", task.getException());
                            Toast.makeText(LoginActivity.this, "Firebase Authentication Failed.", Toast.LENGTH_SHORT).show();
                            updateUI(null);
                        }
                    }
                });
    }

    private void checkUserInFirestore(FirebaseUser firebaseUser) {
        if (firebaseUser == null) return;

        // Get the permanent, local Email/Password UID from the secondary app to match your original ruleset
        String adminUid = null;
        try {
            FirebaseApp adminApp = FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME);
            adminUid = FirebaseAuth.getInstance(adminApp).getUid();
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving persistent UID from secondary app.", e);
        }

        if (adminUid == null) {
            Toast.makeText(this, "Session initializing. Please try again in a moment.", Toast.LENGTH_SHORT).show();
            updateUI(null);
            return;
        }

        DocumentReference userRef = db.collection("users").document(adminUid);

        final String finalAdminUid = adminUid;
        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                User user = documentSnapshot.toObject(User.class);
                if (user != null && user.getRole().equals(expectedRole)) {
                    // Update photoURL if it changed on Google side
                    if (firebaseUser.getPhotoUrl() != null) {
                        userRef.update("photoUrl", firebaseUser.getPhotoUrl().toString());
                    }
                    proceedToDashboard(user);
                } else {
                    Toast.makeText(LoginActivity.this, "Error: Account role mismatch.", Toast.LENGTH_LONG).show();
                    mAuth.signOut();
                    updateUI(null);
                }
            } else {
                createUserProfile(firebaseUser, userRef, finalAdminUid);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error fetching user from secondary Firestore", e);
            Toast.makeText(LoginActivity.this, "Network Error. Please try again.", Toast.LENGTH_SHORT).show();
            updateUI(null);
        });
    }

    private void createUserProfile(FirebaseUser firebaseUser, DocumentReference userRef, String adminUid) {
        // We store the adminUid in the User object's uid field to ensure request.auth.uid matches the path uid natively
        User newUser = new User(adminUid, firebaseUser.getEmail(), expectedRole);
        
        if (firebaseUser.getDisplayName() != null) {
            newUser.setName(firebaseUser.getDisplayName());
        }

        // ZERO BILLING FIX: Set photoUrl from Google Auth profile
        if (firebaseUser.getPhotoUrl() != null) {
            newUser.setPhotoUrl(firebaseUser.getPhotoUrl().toString());
        }

        if ("admin".equals(expectedRole)) {
            newUser.setApproved(true);
        } else {
            newUser.setApproved(false);
        }

        userRef.set(newUser)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(LoginActivity.this, "Account Created Successfully inside Office Database.", Toast.LENGTH_SHORT).show();
                    proceedToDashboard(newUser);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error creating profile in secondary Firestore", e);
                    Toast.makeText(LoginActivity.this, "Failed to create database record.", Toast.LENGTH_SHORT).show();
                    mAuth.signOut();
                    updateUI(null);
                });
    }

    private void proceedToDashboard(User user) {
        Intent intent;
        if ("admin".equals(user.getRole())) {
            intent = new Intent(this, AdminDashboardActivity.class);
        } else {
            intent = new Intent(this, EmployeeDashboardActivity.class);
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void updateUI(FirebaseUser user) {
        binding.progressBar.setVisibility(View.GONE);
        binding.btnGoogleSignIn.setVisibility(View.VISIBLE);
    }

    // NEW: Lifecycle methods for AdView
    @Override
    public void onPause() {
        if (mAdView != null) mAdView.pause();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdView != null) mAdView.resume();
    }

    @Override
    public void onDestroy() {
        if (mAdView != null) mAdView.destroy();
        super.onDestroy();
    }
}