package com.inout.app.utils;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Manages the dynamic initialization of the Firebase backend.
 * This allows the app to connect to different Firebase projects based on the
 * configuration uploaded by the Admin or scanned by the Employee.
 * 
 * UPDATED: 
 * 1. Default instance ([DEFAULT]) points permanently to CentralConfig (developer project) for Auth.
 * 2. Scanned configurations point to a named secondary instance ("admin_app") for Database.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    public static final String ADMIN_APP_NAME = "admin_app";

    /**
     * Initializes Firebase using the configuration stored in EncryptionHelper.
     * This is called automatically by InOutApplication.
     */
    public static void initialize(Context context) {
        // 1. Initialize the Developer's Central Project as the default [DEFAULT] app
        try {
            FirebaseOptions defaultOptions = new FirebaseOptions.Builder()
                    .setApiKey(CentralConfig.API_KEY)
                    .setApplicationId(CentralConfig.APPLICATION_ID)
                    .setProjectId(CentralConfig.PROJECT_ID)
                    .setStorageBucket(CentralConfig.STORAGE_BUCKET)
                    .build();

            boolean defaultAppExists = false;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp app : apps) {
                if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
                    defaultAppExists = true;
                    break;
                }
            }

            if (!defaultAppExists) {
                FirebaseApp.initializeApp(context, defaultOptions);
                Log.d(TAG, "Central Firebase [DEFAULT] initialized successfully for Google Auth.");
            } else {
                Log.d(TAG, "Central Firebase [DEFAULT] already exists.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize central default Firebase app.", e);
        }

        // 2. Initialize the Admin's Project dynamically as the secondary named "admin_app"
        String jsonConfig = EncryptionHelper.getInstance(context).getFirebaseConfig();

        if (jsonConfig != null && !jsonConfig.isEmpty()) {
            try {
                FirebaseOptions options = buildOptionsFromJson(jsonConfig);
                
                boolean adminAppExists = false;
                List<FirebaseApp> apps = FirebaseApp.getApps(context);
                for (FirebaseApp app : apps) {
                    if (ADMIN_APP_NAME.equals(app.getName())) {
                        adminAppExists = true;
                        break;
                    }
                }

                if (adminAppExists) {
                    FirebaseApp app = FirebaseApp.getInstance(ADMIN_APP_NAME);
                    // If the project ID changed, re-initialize named app
                    if (!app.getOptions().getProjectId().equals(options.getProjectId())) {
                         Log.w(TAG, "admin_app project ID mismatch. Re-initializing secondary app.");
                         app.delete();
                         FirebaseApp.initializeApp(context, options, ADMIN_APP_NAME);
                    } else {
                         Log.d(TAG, "admin_app already initialized and matches current configuration.");
                    }
                } else {
                    FirebaseApp.initializeApp(context, options, ADMIN_APP_NAME);
                    Log.d(TAG, "Secondary Firebase '" + ADMIN_APP_NAME + "' initialized successfully.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse or initialize secondary dynamic Firebase config.", e);
            }
        } else {
            Log.d(TAG, "No secondary Admin Firebase config found. Waiting for Admin/QR setup.");
        }
    }

    /**
     * Forces re-initialization of Firebase with a new JSON string.
     * Used when Admin switches companies or Employee scans a new QR.
     */
    public static boolean setConfiguration(Context context, String jsonConfig, String companyName, String projectId) {
        try {
            // Validate JSON by trying to build options
            FirebaseOptions options = buildOptionsFromJson(jsonConfig);

            // Save to encrypted storage
            EncryptionHelper.getInstance(context).saveFirebaseConfig(jsonConfig, companyName, projectId);
            
            // Immediately mount or update the named app instance so the app can start using it
            boolean adminAppExists = false;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp app : apps) {
                if (ADMIN_APP_NAME.equals(app.getName())) {
                    adminAppExists = true;
                    break;
                }
            }

            if (adminAppExists) {
                FirebaseApp app = FirebaseApp.getInstance(ADMIN_APP_NAME);
                app.delete();
            }
            
            FirebaseApp.initializeApp(context, options, ADMIN_APP_NAME);
            Log.d(TAG, "New Admin Firebase configuration saved and secondary app mounted.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Invalid Firebase JSON provided.", e);
            return false;
        }
    }

    /**
     * Parses the google-services.json content string and builds FirebaseOptions.
     */
    private static FirebaseOptions buildOptionsFromJson(String jsonString) throws Exception {
        JSONObject root = new JSONObject(jsonString);
        
        // Extract project info
        JSONObject projectInfo = root.getJSONObject("project_info");
        String projectId = projectInfo.getString("project_id");
        String storageBucket = projectInfo.getString("storage_bucket");

        // Extract client info (usually the first client in the array is the Android one)
        JSONArray clientArray = root.getJSONArray("client");
        JSONObject client = clientArray.getJSONObject(0);
        JSONObject clientInfo = client.getJSONObject("client_info");
        String applicationId = clientInfo.getString("mobilesdk_app_id");

        // Extract API Key
        JSONArray apiKeyArray = client.getJSONArray("api_key");
        JSONObject apiKeyObject = apiKeyArray.getJSONObject(0);
        String apiKey = apiKeyObject.getString("current_key");

        return new FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(applicationId)
                .setProjectId(projectId)
                .setStorageBucket(storageBucket)
                .build();
    }
}