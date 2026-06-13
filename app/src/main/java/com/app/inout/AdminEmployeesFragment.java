package com.inout.app;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.inout.app.databinding.FragmentAdminEmployeesBinding;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.models.User;
import com.inout.app.models.CompanyConfig;
import com.inout.app.utils.FirebaseManager;
import com.inout.app.utils.TimeUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Updated Fragment to handle Multi-Selection, Bulk Deletion, 
 * Individual/Bulk Location Assignment, Traveling Mode, and Shift Timing.
 * UPDATED: Handles Emergency Leave and Medical Leave (Paid/Unpaid) approvals.
 * DYNAMIC BYPASS:
 * - Redirects all Firestore reads and writes to the secondary named app instance "admin_app".
 */
public class AdminEmployeesFragment extends Fragment implements EmployeeListAdapter.OnEmployeeActionListener {

    private static final String TAG = "AdminEmployeesFrag";
    private FragmentAdminEmployeesBinding binding;
    private FirebaseFirestore db;
    private EmployeeListAdapter adapter;
    private List<User> employeeList;
    private List<CompanyConfig> locationList; 

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAdminEmployeesBinding.inflate(inflater, container, false);
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
        
        employeeList = new ArrayList<>();
        locationList = new ArrayList<>();
        
        setupRecyclerView();
        listenForEmployees();
        fetchLocations(); 
    }

    private void setupRecyclerView() {
        binding.recyclerViewEmployees.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new EmployeeListAdapter(getContext(), employeeList, this);
        binding.recyclerViewEmployees.setAdapter(adapter);
    }

    private void fetchLocations() {
        db.collection("locations").addSnapshotListener((value, error) -> {
            if (error != null) {
                Log.e(TAG, "Failed to fetch locations", error);
                return;
            }
            if (value != null) {
                locationList.clear();
                for (DocumentSnapshot doc : value) {
                    CompanyConfig loc = doc.toObject(CompanyConfig.class);
                    if (loc != null) {
                        loc.setId(doc.getId()); 
                        locationList.add(loc);
                    }
                }
            }
        });
    }

    private void listenForEmployees() {
        binding.progressBar.setVisibility(View.VISIBLE);
        db.collection("users")
                .whereEqualTo("role", "employee")
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot value, @Nullable FirebaseFirestoreException error) {
                        binding.progressBar.setVisibility(View.GONE);
                        if (error != null) {
                            Log.e(TAG, "Listen failed.", error);
                            return;
                        }

                        if (value != null) {
                            employeeList.clear();
                            for (DocumentSnapshot doc : value) {
                                User user = doc.toObject(User.class);
                                if (user != null) {
                                    user.setUid(doc.getId());
                                    employeeList.add(user);
                                }
                            }
                            adapter.notifyDataSetChanged();
                            binding.tvEmptyView.setVisibility(employeeList.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }
                });
    }

    /**
     * Handles individual "Approve" button on the employee card.
     * UPDATED: Routes to Emergency Leave, Medical Leave, or standard Approval.
     */
    @Override
    public void onApproveClicked(User user) {
        if ("pending".equals(user.getEmergencyLeaveStatus())) {
            showEmergencyLeaveApprovalDialog(user);
        } else if ("pending".equals(user.getMedicalLeaveStatus())) {
            showMedicalLeaveDecisionDialog(user);
        } else {
            if (locationList.isEmpty()) {
                Toast.makeText(getContext(), "Please add an Office Location first!", Toast.LENGTH_LONG).show();
                return;
            }
            showIndividualApproveDialog(user);
        }
    }

    /**
     * NEW: Dialog to handle Medical Leave (Asking Paid vs Unpaid).
     */
    private void showMedicalLeaveDecisionDialog(User user) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Medical Leave Decision");
        builder.setMessage("Grant medical leave for " + user.getName() + "? Choose leave type:");

        builder.setPositiveButton("Paid Leave", (dialog, which) -> {
            processMedicalLeaveApproval(user, "paid");
        });

        builder.setNeutralButton("Unpaid Leave", (dialog, which) -> {
            processMedicalLeaveApproval(user, "unpaid");
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void processMedicalLeaveApproval(User user, String type) {
        String dateId = TimeUtils.getCurrentDateId();
        String recordId = user.getEmployeeId() + "_" + dateId;
        String remarks = user.getName() + " on Medical Leave (" + type.toUpperCase() + ")";

        // 1. Update User Document
        db.collection("users").document(user.getUid())
                .update("medicalLeaveStatus", "approved", "medicalLeaveType", type)
                .addOnSuccessListener(aVoid -> {
                    // 2. Update/Create Attendance Record with remarks
                    db.collection("attendance").document(recordId)
                            .update("medicalLeaveType", type, "remarks", remarks)
                            .addOnFailureListener(e -> {
                                // If no check-in record exists yet, create one
                                AttendanceRecord rec = new AttendanceRecord(user.getEmployeeId(), user.getName(), dateId, TimeUtils.getCurrentTimestamp());
                                rec.setRecordId(recordId);
                                rec.setMedicalLeaveType(type);
                                rec.setRemarks(remarks);
                                db.collection("attendance").document(recordId).set(rec);
                            });
                    Toast.makeText(getContext(), "Medical Leave (" + type + ") approved.", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Dialog to handle specific Emergency Leave approval.
     */
    private void showEmergencyLeaveApprovalDialog(User user) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Emergency Leave Approval")
                .setMessage("Approve emergency leave request for " + user.getName() + "?")
                .setPositiveButton("Approve Leave", (dialog, which) -> {
                    db.collection("users").document(user.getUid())
                            .update("emergencyLeaveStatus", "none")
                            .addOnSuccessListener(aVoid -> {
                                Toast.makeText(getContext(), "Leave Approved. User status reset.", Toast.LENGTH_SHORT).show();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showIndividualApproveDialog(User user) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Approve " + user.getName());

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 10);

        final EditText inputId = new EditText(requireContext());
        inputId.setHint("Employee ID (e.g. EMP001)");
        inputId.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (user.getEmployeeId() != null) inputId.setText(user.getEmployeeId());
        layout.addView(inputId);

        final Spinner spinner = new Spinner(requireContext());
        spinner.setPadding(0, 40, 0, 40);
        List<String> names = new ArrayList<>();
        int currentSelection = 0;
        for (int i = 0; i < locationList.size(); i++) {
            names.add(locationList.get(i).getName());
            if (locationList.get(i).getId().equals(user.getAssignedLocationId())) {
                currentSelection = i;
            }
        }
        ArrayAdapter<String> spinAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        spinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinAdapter);
        spinner.setSelection(currentSelection);
        layout.addView(spinner);

        final CheckBox cbTraveling = new CheckBox(requireContext());
        cbTraveling.setText("Enable Traveling / Remote Start");
        cbTraveling.setChecked(user.isTraveling()); 
        layout.addView(cbTraveling);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText("Assigned Shift Hours:");
        tvLabel.setPadding(0, 20, 0, 10);
        layout.addView(tvLabel);

        LinearLayout timeLayout = new LinearLayout(requireContext());
        timeLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        final TextView tvStart = new TextView(requireContext());
        tvStart.setText(user.getShiftStartTime() != null ? user.getShiftStartTime() : "09:00 AM");
        tvStart.setTextSize(16);
        tvStart.setPadding(0, 0, 40, 0);
        tvStart.setOnClickListener(v -> showTimePicker(tvStart));
        
        final TextView tvEnd = new TextView(requireContext());
        tvEnd.setText(user.getShiftEndTime() != null ? user.getShiftEndTime() : "06:00 PM");
        tvEnd.setTextSize(16);
        tvEnd.setOnClickListener(v -> showTimePicker(tvEnd));

        timeLayout.addView(tvStart);
        timeLayout.addView(new TextView(requireContext()) {{ setText(" - "); }});
        timeLayout.addView(tvEnd);
        layout.addView(timeLayout);

        builder.setView(layout);

        builder.setPositiveButton("Approve & Save", (dialog, which) -> {
            String empId = inputId.getText().toString().trim();
            int selectedIndex = spinner.getSelectedItemPosition();
            
            if (!empId.isEmpty() && selectedIndex >= 0) {
                String locId = locationList.get(selectedIndex).getId();
                
                db.collection("users").document(user.getUid())
                        .update("approved", true, 
                                "employeeId", empId, 
                                "assignedLocationId", locId,
                                "isTraveling", cbTraveling.isChecked(),
                                "shiftStartTime", tvStart.getText().toString(),
                                "shiftEndTime", tvEnd.getText().toString())
                        .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "User Updated Successfully!", Toast.LENGTH_SHORT).show());
            } else {
                Toast.makeText(getContext(), "ID and Location required!", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showTimePicker(TextView targetView) {
        Calendar cal = Calendar.getInstance();
        new TimePickerDialog(requireContext(), (view, hourOfDay, minute) -> {
            String amPm = (hourOfDay >= 12) ? "PM" : "AM";
            int hour12 = (hourOfDay > 12) ? hourOfDay - 12 : hourOfDay;
            if (hour12 == 0) hour12 = 12;
            String time = String.format(Locale.US, "%02d:%02d %s", hour12, minute, amPm);
            targetView.setText(time);
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
    }

    @Override
    public void onDeleteClicked(User user) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove Employee")
                .setMessage("Delete " + user.getName() + "? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    db.collection("users").document(user.getUid()).delete()
                            .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "Employee removed.", Toast.LENGTH_SHORT).show());
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onBulkActionRequested(List<User> selectedUsers) {
        String[] options = {"Remove Selected Employees", "Assign Location & Shift"};
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Bulk Actions (" + selectedUsers.size() + " selected)");
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                showBulkDeleteConfirmation(selectedUsers);
            } else if (which == 1) {
                showBulkLocationAssignment(selectedUsers);
            }
        });
        builder.show();
    }

    private void showBulkDeleteConfirmation(List<User> selectedUsers) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Confirm Removal")
                .setMessage("Are you sure you want to remove " + selectedUsers.size() + " employees?")
                .setPositiveButton("Remove All", (dialog, which) -> {
                    performBulkDelete(selectedUsers);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performBulkDelete(List<User> selectedUsers) {
        WriteBatch batch = db.batch();
        for (User user : selectedUsers) {
            batch.delete(db.collection("users").document(user.getUid()));
        }
        batch.commit().addOnSuccessListener(aVoid -> {
            Toast.makeText(getContext(), "Selected employees removed.", Toast.LENGTH_SHORT).show();
            adapter.clearSelection();
        });
    }

    private void showBulkLocationAssignment(List<User> selectedUsers) {
        if (locationList.isEmpty()) {
            Toast.makeText(getContext(), "Add an Office Location first!", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Assign to " + selectedUsers.size() + " Users");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 10);

        final Spinner spinner = new Spinner(requireContext());
        List<String> names = new ArrayList<>();
        for (CompanyConfig c : locationList) names.add(c.getName());
        ArrayAdapter<String> spinAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        spinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinAdapter);
        layout.addView(spinner);

        final CheckBox cbTraveling = new CheckBox(requireContext());
        cbTraveling.setText("Enable Traveling / Remote Start");
        layout.addView(cbTraveling);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText("Assigned Shift Hours:");
        tvLabel.setPadding(0, 20, 0, 10);
        layout.addView(tvLabel);

        LinearLayout timeLayout = new LinearLayout(requireContext());
        timeLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        final TextView tvStart = new TextView(requireContext());
        tvStart.setText("09:00 AM");
        tvStart.setTextSize(16);
        tvStart.setPadding(0, 0, 40, 0);
        tvStart.setOnClickListener(v -> showTimePicker(tvStart));
        
        final TextView tvEnd = new TextView(requireContext());
        tvEnd.setText("06:00 PM");
        tvEnd.setTextSize(16);
        tvEnd.setOnClickListener(v -> showTimePicker(tvEnd));

        timeLayout.addView(tvStart);
        timeLayout.addView(new TextView(requireContext()) {{ setText(" - "); }});
        timeLayout.addView(tvEnd);
        layout.addView(timeLayout);

        builder.setView(layout);
        builder.setPositiveButton("Assign All", (dialog, which) -> {
            int selectedIndex = spinner.getSelectedItemPosition();
            if (selectedIndex >= 0) {
                String locId = locationList.get(selectedIndex).getId();
                performBulkAssignment(selectedUsers, locId, cbTraveling.isChecked(), 
                                      tvStart.getText().toString(), tvEnd.getText().toString());
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void performBulkAssignment(List<User> selectedUsers, String locId, boolean isTraveling, String start, String end) {
        WriteBatch batch = db.batch();
        for (User user : selectedUsers) {
            batch.update(db.collection("users").document(user.getUid()), 
                    "assignedLocationId", locId,
                    "approved", true,
                    "isTraveling", isTraveling,
                    "shiftStartTime", start,
                    "shiftEndTime", end);
        }
        
        batch.commit().addOnSuccessListener(aVoid -> {
            Toast.makeText(getContext(), "Bulk assignment successful.", Toast.LENGTH_SHORT).show();
            adapter.clearSelection();
        }).addOnFailureListener(e -> {
            Toast.makeText(getContext(), "Bulk update failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}