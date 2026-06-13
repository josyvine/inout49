package com.inout.app.utils;

/**
 * Configuration holder for the App Developer's Central Firebase Project.
 * This is used strictly to handle Google Sign-In and identity verification.
 * 
 * Configured with active developer project values extracted from your google-services.json file.
 */
public final class CentralConfig {

    private CentralConfig() {
        // Prevent instantiation
    }

    /**
     * The Web Client ID (Client Type 3) from your developer project's OAuth credentials.
     * This is required to request the Google ID Token during sign-in.
     */
    public static final String WEB_CLIENT_ID = "508928966890-1lvcph6h5tvmi2hfp06ksk1m69hgr3mg.apps.googleusercontent.com";

    /**
     * The API Key from your developer Firebase project.
     */
    public static final String API_KEY = "AIzaSyDPQ4in6TlQH9GM5pwfeoACdKmX7SZHQHM";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your developer Firebase project.
     */
    public static final String APPLICATION_ID = "1:508928966890:android:9e0c34b995176c22655793";

    /**
     * The Project ID of your developer Firebase project.
     */
    public static final String PROJECT_ID = "mycompany-inout-app";

    /**
     * The Storage Bucket URL of your developer Firebase project.
     */
    public static final String STORAGE_BUCKET = "mycompany-inout-app.firebasestorage.app";
}