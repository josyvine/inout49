package com.inout.app.utils;

/**
 * Configuration holder for the App Developer's Central Firebase Project.
 * This is used strictly to handle Google Sign-In and identity verification.
 * 
 * Replace the placeholder strings below with your actual Firebase project values
 * obtained from your developer Firebase console.
 */
public final class CentralConfig {

    private CentralConfig() {
        // Prevent instantiation
    }

    /**
     * The Web Client ID (Client Type 3) from your developer project's OAuth credentials.
     * This is required to request the Google ID Token during sign-in.
     */
    public static final String WEB_CLIENT_ID = "YOUR_CENTRAL_WEB_CLIENT_ID_HERE.apps.googleusercontent.com";

    /**
     * The API Key from your developer Firebase project.
     */
    public static final String API_KEY = "YOUR_CENTRAL_API_KEY_HERE";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your developer Firebase project.
     * Format is typically: 1:XXXXX:android:XXXXXX
     */
    public static final String APPLICATION_ID = "YOUR_CENTRAL_APP_ID_HERE";

    /**
     * The Project ID of your developer Firebase project.
     */
    public static final String PROJECT_ID = "YOUR_CENTRAL_PROJECT_ID_HERE";

    /**
     * The Storage Bucket URL of your developer Firebase project.
     * Format is typically: project-id.appspot.com
     */
    public static final String STORAGE_BUCKET = "YOUR_CENTRAL_STORAGE_BUCKET_HERE.appspot.com";
}