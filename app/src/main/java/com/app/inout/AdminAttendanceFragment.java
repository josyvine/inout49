package com.inout.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.inout.app.databinding.FragmentAdminAttendanceBinding;
import com.inout.app.models.User;
import com.inout.app.utils.FirebaseManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin view for Attendance.
 * 1. Select employee from Spinner.
 * 2. Opens the Professional Attendance Profile Pop-up (CV-style).
 * DYNAMIC BYPASS:
 * - Redirects all Firestore database reads to the secondary named app instance "admin_app".
 */
public class AdminAttendanceFragment extends Fragment {

    private static final String TAG = "AdminAttendanceFrag";
    private FragmentAdminAttendanceBinding binding;
    private FirebaseFirestore db;
    
    private List<User> employees;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAdminAttendanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Dynamic bypass: Initialize Firestore database pointing to secondary app
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary admin_app not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }
        
        employees = new ArrayList<>();

        // Load the list of employees into the spinner first
        loadEmployeeList();
    }

    /**
     * Fetches all approved employees to populate the selection spinner.
     * This logic is identical to your original code.
     */
    private void loadEmployeeList() {
        binding.progressBar.setVisibility(View.VISIBLE);
        db.collection("users")
                .whereEqualTo("role", "employee")
                .whereEqualTo("approved", true)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    binding.progressBar.setVisibility(View.GONE);
                    employees.clear();
                    List<String> employeeNames = new ArrayList<>();
                    employeeNames.add("Select an Employee");

                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            user.setUid(doc.getId()); // Ensure UID is preserved
                            employees.add(user);
                            // Format: Name (EmployeeID)
                            employeeNames.add(user.getName() + " (" + user.getEmployeeId() + ")");
                        }
                    }

                    setupSpinner(employeeNames);
                })
                .addOnFailureListener(e -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Error loading employees", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Sets up the dropdown menu.
     * When a name is selected, it triggers the Pop-Up Window.
     */
    private void setupSpinner(List<String> names) {
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), 
                android.R.layout.simple_spinner_item, names);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerEmployees.setAdapter(spinnerAdapter);

        binding.spinnerEmployees.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    // Position 0 is the hint "Select an Employee"
                    // Get the User object for the selected person
                    User selectedUser = employees.get(position - 1);
                    
                    // NEW LOGIC: Launch the pop-up profile window
                    openAttendanceProfileDialog(selectedUser);
                    
                    // Reset spinner so the same person can be selected again if the dialog is closed
                    binding.spinnerEmployees.setSelection(0);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * This method initializes and displays the new CV-style Attendance Profile window.
     * @param user The employee whose attendance is being viewed.
     */
    private void openAttendanceProfileDialog(User user) {
        // Create the dialog instance and pass the User data to it
        AttendanceProfileDialog dialog = AttendanceProfileDialog.newInstance(user);
        // Show it as a pop-up over the current screen
        dialog.show(getChildFragmentManager(), "AttendanceProfileDialog");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}