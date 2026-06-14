package com.inout.app;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.inout.app.adapters.AttendanceAdapter;
import com.inout.app.databinding.DialogAttendanceProfileBinding;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.models.User;
import com.inout.app.utils.EncryptionHelper;
import com.inout.app.utils.FirebaseManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Professional Pop-up Window for Attendance Profile.
 * Features: Fixed CV-Header, Horizontal 14-column CSV Table, Full Month Report.
 * UPDATED: Integrated Remarks and Emergency Leave logic for Monthly Export.
 * DYNAMIC BYPASS:
 * - Redirects all Firestore reads to the secondary named app instance "admin_app".
 */
public class AttendanceProfileDialog extends DialogFragment {

    private static final String TAG = "AttendanceDialog";
    private DialogAttendanceProfileBinding binding;
    private User employee;
    private FirebaseFirestore db;
    private AttendanceAdapter adapter;
    private List<AttendanceRecord> fullMonthList;

    public static AttendanceProfileDialog newInstance(User user) {
        AttendanceProfileDialog frag = new AttendanceProfileDialog();
        frag.employee = user;
        return frag;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            int width = ViewGroup.LayoutParams.MATCH_PARENT;
            int height = ViewGroup.LayoutParams.MATCH_PARENT;
            dialog.getWindow().setLayout(width, height);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogAttendanceProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize Firestore pointing to the secondary named "admin_app" instance
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary admin_app not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }

        fullMonthList = new ArrayList<>();

        setupHeader();
        setupTable();
        loadAttendanceData();

        binding.btnClose.setOnClickListener(v -> dismiss());

        // UPDATED: Export button now uses the 14-column CSV logic
        binding.btnExportCsv.setOnClickListener(v -> {
            if (fullMonthList != null && !fullMonthList.isEmpty()) {
                String fileName = employee.getName().replace(" ", "_") + "_" + 
                                 new SimpleDateFormat("MMM_yyyy", Locale.US).format(Calendar.getInstance().getTime());
                CsvExportHelper.exportAttendanceToCsv(requireContext(), fullMonthList, fileName);
            } else {
                Toast.makeText(getContext(), "No data available to export.", Toast.LENGTH_SHORT).show();
            }
        });

        // NEW: Export button for dynamic PDF generation
        binding.btnExportPdf.setOnClickListener(v -> {
            if (fullMonthList != null && !fullMonthList.isEmpty()) {
                String fileName = employee.getName().replace(" ", "_") + "_" + 
                                 new SimpleDateFormat("MMM_yyyy", Locale.US).format(Calendar.getInstance().getTime());
                PdfExportHelper.exportAttendanceToPdf(requireContext(), employee, fullMonthList, fileName);
            } else {
                Toast.makeText(getContext(), "No data available to export.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupHeader() {
        binding.tvHeaderName.setText(employee.getName());
        binding.tvHeaderId.setText("ID: " + employee.getEmployeeId());
        binding.tvHeaderPhone.setText("Phone: " + employee.getPhone());
        binding.tvHeaderCompany.setText(EncryptionHelper.getInstance(getContext()).getCompanyName());

        Calendar cal = Calendar.getInstance();
        String currentMonthYear = new SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.getTime());
        binding.tvHeaderMonth.setText(currentMonthYear);

        if (employee.getPhotoUrl() != null && !employee.getPhotoUrl().isEmpty()) {
            Glide.with(this)
                    .load(employee.getPhotoUrl())
                    .circleCrop()
                    .placeholder(R.drawable.inout)
                    .into(binding.ivProfilePhoto);
        }
    }

    private void setupTable() {
        binding.rvAttendanceTable.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AttendanceAdapter(fullMonthList);
        binding.rvAttendanceTable.setAdapter(adapter);
    }

    private void loadAttendanceData() {
        binding.progressBar.setVisibility(View.VISIBLE);

        db.collection("attendance")
                .whereEqualTo("employeeId", employee.getEmployeeId())
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    Map<String, AttendanceRecord> existingLogs = new HashMap<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        AttendanceRecord record = doc.toObject(AttendanceRecord.class);
                        if (record != null) {
                            existingLogs.put(record.getDate(), record);
                        }
                    }
                    generateFullMonthReport(existingLogs);
                })
                .addOnFailureListener(e -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Log.e(TAG, "Data fetch failed from secondary database.", e);
                    Toast.makeText(getContext(), "Error loading month records", Toast.LENGTH_SHORT).show();
                });
    }

    private void generateFullMonthReport(Map<String, AttendanceRecord> logs) {
        fullMonthList.clear();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        SimpleDateFormat dateIdFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat dayNameFormat = new SimpleDateFormat("EEEE", Locale.US);

        for (int i = 1; i <= maxDay; i++) {
            String dateId = dateIdFormat.format(cal.getTime());
            String dayName = dayNameFormat.format(cal.getTime());

            if (logs.containsKey(dateId)) {
                AttendanceRecord record = logs.get(dateId);
                record.setDayOfWeek(dayName);
                fullMonthList.add(record);
            } else {
                AttendanceRecord absent = new AttendanceRecord();
                absent.setDate(dateId);
                absent.setDayOfWeek(dayName);
                fullMonthList.add(absent);
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        binding.progressBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }
}