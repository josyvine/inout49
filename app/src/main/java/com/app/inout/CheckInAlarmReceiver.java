package com.inout.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.utils.FirebaseManager;
import com.inout.app.utils.TimeUtils;

/**
 * BroadcastReceiver triggered by AlarmManager to notify employees
 * exactly one minute before their assigned shift check-in window opens [2].
 * UPDATED: Uses goAsync() to verify the attendance state in Firestore before sounding false alarms.
 * DYNAMIC BYPASS:
 * - Redirects all Firestore reads to the secondary named app instance "admin_app"
 *   using the aligned persistent UID to bypass SHA-1 constraints and protect original rules.
 */
public class CheckInAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "CheckInAlarmReceiver";
    private static final String CHANNEL_ID = "shift_reminder_channel";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received: Checking verification constraints");

        // Extract the exact type of alarm triggered [2]
        String reminderType = intent.getStringExtra("reminder_type");
        if (reminderType == null) {
            reminderType = "about_to_check_in"; // Standard fallback
        }

        final String finalReminderType = reminderType;
        final PendingResult pendingResult = goAsync();

        FirebaseAuth mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() == null) {
            // Not authenticated, fallback to standard notification immediately
            fireNotificationAndVoice(context, finalReminderType);
            pendingResult.finish();
            return;
        }

        // Dynamic bypass: Initialize Firestore database pointing to secondary app
        FirebaseFirestore db;
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary admin_app not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }

        // FIXED: Declare a final copy of the database instance to pass Java lambda checks
        final FirebaseFirestore finalDb = db;

        // Get the active persistent UID of the admin_app to match your original ruleset
        String uid = null;
        try {
            FirebaseApp adminApp = FirebaseApp.getInstance(FirebaseManager.ADMIN_APP_NAME);
            uid = FirebaseAuth.getInstance(adminApp).getUid();
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving dynamic UID from secondary app.", e);
        }

        if (uid == null) {
            uid = mAuth.getCurrentUser().getUid(); // fallback to Google UID
        }

        // FIXED: Declare a final copy of the UID variable to pass Java lambda checks
        final String finalUid = uid;

        // 1. Fetch user's assigned employee ID dynamically from secondary Firestore
        finalDb.collection("users").document(finalUid).get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                String empId = task.getResult().getString("employeeId");
                
                if (empId != null && !empId.isEmpty()) {
                    String todayDateId = TimeUtils.getCurrentDateId();
                    String yesterdayDateId = TimeUtils.getYesterdayDateId();

                    String todayRecordId = empId + "_" + todayDateId;
                    String yesterdayRecordId = empId + "_" + yesterdayDateId;

                    // 2. Query yesterday's record first to resolve cross-midnight shifts
                    finalDb.collection("attendance").document(yesterdayRecordId).get().addOnCompleteListener(yesTask -> {
                        boolean handled = false;

                        if (yesTask.isSuccessful() && yesTask.getResult() != null && yesTask.getResult().exists()) {
                            AttendanceRecord record = yesTask.getResult().toObject(AttendanceRecord.class);
                            
                            if (record != null && record.getCheckInTime() != null && 
                                (record.getCheckOutTime() == null || record.getCheckOutTime().isEmpty())) {
                                
                                // An incomplete overnight shift is active from yesterday
                                boolean skipAlarm = false;
                                if ("about_to_check_in".equals(finalReminderType) || "late_check_in".equals(finalReminderType)) {
                                    // They are currently still checked in from yesterday. Skip new check-in alarms.
                                    skipAlarm = true;
                                } else if ("about_to_check_out".equals(finalReminderType)) {
                                    // They have not checked out yet. We DO NOT skip checkout alarms.
                                    skipAlarm = false; 
                                }

                                if (!skipAlarm) {
                                    fireNotificationAndVoice(context, finalReminderType);
                                } else {
                                    Log.d(TAG, "Aborting alarm notification: Yesterday's overnight shift is still active.");
                                }
                                handled = true;
                            }
                        }

                        if (handled) {
                            pendingResult.finish();
                            return;
                        }

                        // 3. Fallback: Query today's attendance record
                        finalDb.collection("attendance").document(todayRecordId).get().addOnCompleteListener(todayTask -> {
                            boolean skipAlarm = false;

                            if (todayTask.isSuccessful() && todayTask.getResult() != null && todayTask.getResult().exists()) {
                                AttendanceRecord record = todayTask.getResult().toObject(AttendanceRecord.class);
                                
                                if (record != null) {
                                    // Analyze if the action for this specific alarm is already completed
                                    if ("about_to_check_in".equals(finalReminderType) || "late_check_in".equals(finalReminderType)) {
                                        if (record.getCheckInTime() != null && !record.getCheckInTime().isEmpty()) {
                                            skipAlarm = true; // Employee is already checked in, skip false alarm
                                        }
                                    } else if ("about_to_check_out".equals(finalReminderType)) {
                                        if (record.getCheckOutTime() != null && !record.getCheckOutTime().isEmpty()) {
                                            skipAlarm = true; // Employee is already checked out, skip false alarm
                                        }
                                    }
                                }
                            }

                            if (!skipAlarm) {
                                fireNotificationAndVoice(context, finalReminderType);
                            } else {
                                Log.d(TAG, "Aborting alarm notification: Attendance action already recorded.");
                            }
                            pendingResult.finish();
                        });
                    });
                } else {
                    fireNotificationAndVoice(context, finalReminderType);
                    pendingResult.finish();
                }
            } else {
                // Task failed or user profile does not exist. Trigger fallback safely.
                if (!task.isSuccessful() && task.getException() != null) {
                    Log.e(TAG, "Query failed. Firing fallback alarm.", task.getException());
                }
                fireNotificationAndVoice(context, finalReminderType);
                pendingResult.finish();
            }
        });
    }

    /**
     * Helper method containing the primary notification builder and background voice engines.
     */
    private void fireNotificationAndVoice(Context context, String reminderType) {
        try {
            // 1. Create high-importance channel (Required for Android 8.0+)
            createNotificationChannel(context);

            // 2. Intent to launch the launcher splash activity when tapped
            Intent launchIntent = new Intent(context, SplashActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Determine dynamic titles and message content based on the alarm state [2]
            String title = "Shift Reminder";
            String content = "Please check your daily attendance requirements.";

            if ("about_to_check_in".equals(reminderType)) {
                title = "Shift Starting Soon!";
                content = "Your check-in window opens in 1 minute. Please prepare to check in.";
            } else if ("late_check_in".equals(reminderType)) {
                title = "Late Check-In Warning!";
                content = "You are late. Please request Resume from options menu to enable check-in.";
            } else if ("about_to_check_out".equals(reminderType)) {
                title = "Shift Ending Soon!";
                content = "Your check-out window opens in 2 minutes. Please prepare to check out.";
            }

            // 3. Build notification properties with high priority for heads-up alert
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.inout) // Standard launcher drawable resource
                    .setContentTitle(title)
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            // 4. Fire notification via the System Notification Service
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, builder.build());
            }

            // 5. Safely launch background voice engine service as a foreground intent [3, 4]
            try {
                Intent serviceIntent = new Intent(context, CheckInVoiceService.class);
                serviceIntent.putExtra("reminder_type", reminderType);
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start background spoken voice engine service", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to build or send near-time shift reminder", e);
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Shift Reminders";
            String description = "Notifications triggered before work shifts start";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}