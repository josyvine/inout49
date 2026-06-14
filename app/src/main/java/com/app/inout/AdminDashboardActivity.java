package com.inout.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem; 

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.inout.app.databinding.ActivityAdminDashboardBinding;
import com.inout.app.utils.CentralConfig;
import com.inout.app.utils.EncryptionHelper;

/**
 * Main dashboard for Admins.
 * Orchestrates administrative navigation fragments and utilities.
 * DYNAMIC BYPASS:
 * - Google Sign-Out points directly to CentralConfig Google OAuth client ID parameters
 *   to avoid null crashes on SHA-free Admin projects.
 */
public class AdminDashboardActivity extends AppCompatActivity {

    private ActivityAdminDashboardBinding binding;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        setSupportActionBar(binding.toolbar);

        // Setup Navigation Component
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_admin);
        
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            
            // Define top-level destinations (screens that shouldn't show a 'Back' arrow)
            // IDs must match the menu/bottom_nav_menu.xml and mobile_navigation_admin.xml
            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_admin_employees, 
                    R.id.nav_admin_attendance, 
                    R.id.nav_admin_locations, 
                    R.id.nav_admin_qr)
                    .build();

            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.navView, navController);
        }
    }

    // Create the top options menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.admin_top_menu, menu);
        return true;
    }

    // Handle menu clicks
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            logout();
            return true;
        } else if (item.getItemId() == R.id.action_switch_company) {
            switchCompany();
            return true;
        } else if (item.getItemId() == R.id.action_contact_dev) {
            // NEW: Launch the Contact Developer popup
            showContactDevDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * NEW: Initializes and displays the feedback form for the developer.
     */
    private void showContactDevDialog() {
        ContactDevDialog dialog = new ContactDevDialog();
        dialog.show(getSupportFragmentManager(), "ContactDevDialog");
    }

    /**
     * Full Logout Logic.
     * 1. Signs out of Firebase.
     * 2. Signs out of Google (forces account picker for next login).
     * 3. Clears the "Admin" role from local storage.
     * 4. Returns to the absolute landing page (Splash/Role Selection).
     */
    private void logout() {
        // 1. Sign out from Firebase
        mAuth.signOut();
        
        // 2. Configure and sign out from Google pointing to your Central Client ID
        // This is crucial to prevent null pointer crashes on SHA-free Admin projects.
        String webClientId = CentralConfig.WEB_CLIENT_ID;
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .build();
        GoogleSignInClient googleSignInClient = GoogleSignIn.getClient(this, gso);
        
        googleSignInClient.signOut().addOnCompleteListener(task -> {
            // 3. Clear the stored Role (Admin) locally
            EncryptionHelper.getInstance(AdminDashboardActivity.this).clearUserRole();
            
            // 4. Return to SplashActivity and clear the entire activity history stack
            Intent intent = new Intent(AdminDashboardActivity.this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void switchCompany() {
        // To switch company, we go back to the Setup screen
        mAuth.signOut();
        // Note: For switching company, we might not clear the role, 
        // just go back to AdminSetupActivity.
        Intent intent = new Intent(this, AdminSetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}