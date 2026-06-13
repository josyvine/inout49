package com.inout.app;

import android.app.AlertDialog;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.inout.app.databinding.FragmentAdminLocationsBinding;
import com.inout.app.models.CompanyConfig;
import com.inout.app.utils.FirebaseManager;
import com.inout.app.utils.LocationHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Updated Fragment for Office Locations.
 * Features: Remote Search, GPS Capture, Map Selection, and Interactive Selection/Deletion.
 * DYNAMIC BYPASS:
 * - Redirects all Firestore reads and writes to the secondary named app instance "admin_app".
 */
public class AdminLocationsFragment extends Fragment implements LocationAdapter.OnLocationActionListener {

    private static final String TAG = "AdminLocationsFrag";
    private FragmentAdminLocationsBinding binding;
    private FirebaseFirestore db;
    private LocationHelper locationHelper;
    
    private LocationAdapter adapter;
    private List<CompanyConfig> savedLocations;
    
    private double capturedLat = 0;
    private double capturedLng = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAdminLocationsBinding.inflate(inflater, container, false);
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
        
        locationHelper = new LocationHelper(requireContext());
        savedLocations = new ArrayList<>();

        setupRecyclerView();
        setupClickListeners();
        listenForLocations();
    }

    private void setupRecyclerView() {
        binding.rvLocations.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LocationAdapter(savedLocations, this);
        binding.rvLocations.setAdapter(adapter);
    }

    private void setupClickListeners() {
        // Search Button Logic
        binding.btnSearchLocation.setOnClickListener(v -> {
            String address = binding.etSearchAddress.getText().toString().trim();
            if (!TextUtils.isEmpty(address)) {
                searchLocationByAddress(address);
            } else {
                Toast.makeText(getContext(), "Please enter an address to search", Toast.LENGTH_SHORT).show();
            }
        });

        // Capture current GPS logic
        binding.btnCaptureGps.setOnClickListener(v -> captureCurrentLocation());

        // Open Map Selection Logic
        binding.btnOpenMap.setOnClickListener(v -> openMapSelectionDialog());

        // Save logic
        binding.btnSaveLocation.setOnClickListener(v -> saveLocationToFirestore());
    }

    /**
     * Opens the Full Screen Map Dialog to pick a location manually.
     * Receives Latitude, Longitude, and detailed Address Name (including Postal Code).
     */
    private void openMapSelectionDialog() {
        MapSelectionDialog dialog = new MapSelectionDialog();
        dialog.setOnLocationSelectedListener((lat, lng, addressName) -> {
            // Update local variables
            this.capturedLat = lat;
            this.capturedLng = lng;

            // Update UI with the detailed address (Place + Area + Postal Code)
            if (addressName != null && !addressName.isEmpty()) {
                binding.etLocationName.setText(addressName);
            } else {
                binding.etLocationName.setText("Map Point Location");
            }
            
            // Display captured coordinates in the sub-label
            binding.tvCapturedCoords.setText(String.format("Map Selected:\nLat: %.6f | Lng: %.6f", capturedLat, capturedLng));
            binding.tvCapturedCoords.setVisibility(View.VISIBLE);
        });
        dialog.show(getChildFragmentManager(), "MapSelectionDialog");
    }

    private void searchLocationByAddress(String addressString) {
        binding.progressBar.setVisibility(View.VISIBLE);
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocationName(addressString, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address result = addresses.get(0);
                capturedLat = result.getLatitude();
                capturedLng = result.getLongitude();

                String foundName = result.getFeatureName(); 
                binding.etLocationName.setText(foundName);
                
                binding.tvCapturedCoords.setText(String.format("Found: %s\nLat: %.6f | Lng: %.6f", 
                        result.getAddressLine(0), capturedLat, capturedLng));
                binding.tvCapturedCoords.setVisibility(View.VISIBLE);
                
                Toast.makeText(getContext(), "Location Found", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Address not found.", Toast.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoder error", e);
            Toast.makeText(getContext(), "Search error. Check connection.", Toast.LENGTH_SHORT).show();
        } finally {
            binding.progressBar.setVisibility(View.GONE);
        }
    }

    private void captureCurrentLocation() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnCaptureGps.setEnabled(false);

        locationHelper.getCurrentLocation(new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationResult(Location location) {
                binding.progressBar.setVisibility(View.GONE);
                binding.btnCaptureGps.setEnabled(true);

                if (location != null) {
                    capturedLat = location.getLatitude();
                    capturedLng = location.getLongitude();
                    binding.tvCapturedCoords.setText(String.format("Current GPS:\nLat: %.6f | Lng: %.6f", capturedLat, capturedLng));
                    binding.tvCapturedCoords.setVisibility(View.VISIBLE);
                    
                    // Trigger reverse geocoding for GPS capture to also get postal code if possible
                    updateLocationNameFromGps(location);
                }
            }

            @Override
            public void onError(String errorMsg) {
                binding.progressBar.setVisibility(View.GONE);
                binding.btnCaptureGps.setEnabled(true);
                Toast.makeText(getContext(), "GPS Error: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateLocationNameFromGps(Location loc) {
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                if (addr.getFeatureName() != null && !addr.getFeatureName().contains("+")) {
                    sb.append(addr.getFeatureName()).append(", ");
                }
                if (addr.getLocality() != null) sb.append(addr.getLocality()).append(" ");
                if (addr.getPostalCode() != null) sb.append("- ").append(addr.getPostalCode());
                
                binding.etLocationName.setText(sb.toString().trim());
            }
        } catch (IOException e) {
            Log.e(TAG, "Reverse Geocode failed", e);
        }
    }

    private void saveLocationToFirestore() {
        String locName = binding.etLocationName.getText().toString().trim();
        if (TextUtils.isEmpty(locName)) {
            binding.etLocationName.setError("Location Name required");
            return;
        }
        if (capturedLat == 0 || capturedLng == 0) {
            Toast.makeText(getContext(), "Capture coordinates first", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        CompanyConfig config = new CompanyConfig(locName, capturedLat, capturedLng);

        db.collection("locations")
                .add(config)
                .addOnSuccessListener(doc -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Location Saved", Toast.LENGTH_SHORT).show();
                    clearInputs();
                });
    }

    private void clearInputs() {
        binding.etLocationName.setText("");
        binding.etSearchAddress.setText("");
        binding.tvCapturedCoords.setText("");
        binding.tvCapturedCoords.setVisibility(View.GONE);
        capturedLat = 0; capturedLng = 0;
    }

    private void listenForLocations() {
        db.collection("locations")
                .addSnapshotListener((value, error) -> {
                    if (error != null) return;
                    if (value != null) {
                        savedLocations.clear();
                        for (DocumentSnapshot doc : value) {
                            CompanyConfig config = doc.toObject(CompanyConfig.class);
                            if (config != null) {
                                config.setId(doc.getId());
                                savedLocations.add(config);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                });
    }

    @Override
    public void onDeleteRequested(List<CompanyConfig> selectedLocations) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Locations")
                .setMessage("Delete " + selectedLocations.size() + " selected office locations?")
                .setPositiveButton("Delete All", (dialog, which) -> {
                    performBulkDelete(selectedLocations);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performBulkDelete(List<CompanyConfig> selections) {
        binding.progressBar.setVisibility(View.VISIBLE);
        WriteBatch batch = db.batch();
        
        for (CompanyConfig loc : selections) {
            batch.delete(db.collection("locations").document(loc.getId()));
        }

        batch.commit().addOnSuccessListener(aVoid -> {
            binding.progressBar.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Locations deleted successfully.", Toast.LENGTH_SHORT).show();
            adapter.clearSelection();
        }).addOnFailureListener(e -> {
            binding.progressBar.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}