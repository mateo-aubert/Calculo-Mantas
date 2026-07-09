package com.mateo.hojadevuelo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.mateo.hojadevuelo.data.AircraftCatalog;
import com.mateo.hojadevuelo.databinding.ActivityMainBinding;
import com.mateo.hojadevuelo.domain.BlanketCalculator;
import com.mateo.hojadevuelo.domain.RegistrationMatcher;
import com.mateo.hojadevuelo.ocr.RegistrationColumnOcrProcessor;
import com.mateo.hojadevuelo.ocr.ScannedPageLoader;
import com.mateo.hojadevuelo.ui.RegistrationAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String STATE_RESULTS = "state_results";
    private static final String STATE_SUMMARY = "state_summary";
    private static final String STATE_VALUES = "state_values";
    private static final String STATE_DRESSED_AIRCRAFT = "state_dressed_aircraft";
    private static final String STATE_DRESSED_HOLD = "state_dressed_hold";
    private static final String STATE_RETIRED_AIRCRAFT_LOADS = "state_retired_aircraft_loads";
    private static final String STATE_RETIRED_HOLD_LOADS = "state_retired_hold_loads";

    private ActivityMainBinding binding;
    private AircraftCatalog catalog;
    private RegistrationAdapter resultsAdapter;
    private TextRecognizer textRecognizer;
    private RegistrationColumnOcrProcessor ocrProcessor;
    private GmsDocumentScanner documentScanner;
    private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bitmap scannedPage;

    private final Set<String> detectedRegistrations = new LinkedHashSet<>();
    private final Set<String> dressedAircraftRegistrations = new LinkedHashSet<>();
    private final Set<String> dressedHoldRegistrations = new LinkedHashSet<>();
    private final ArrayList<String> retiredAircraftLoads = new ArrayList<>();
    private final ArrayList<String> retiredHoldLoads = new ArrayList<>();
    private boolean showingResults;
    private boolean showingSummary;
    private boolean showingCrop;
    private boolean processing;
    private boolean lastScanCompleted;
    private boolean lastScanTextDetected;

    private final ActivityResultLauncher<IntentSenderRequest> scannerLauncher =
            registerForActivityResult(
                    new StartIntentSenderForResult(),
                    activityResult -> {
                        if (activityResult.getResultCode() != Activity.RESULT_OK) {
                            setProcessing(false);
                            return;
                        }

                        GmsDocumentScanningResult scanResult =
                                GmsDocumentScanningResult.fromActivityResultIntent(
                                        activityResult.getData());
                        if (scanResult == null
                                || scanResult.getPages() == null
                                || scanResult.getPages().isEmpty()) {
                            setProcessing(false);
                            showMessage(R.string.scan_no_page);
                            return;
                        }

                        Uri imageUri = scanResult.getPages().get(0).getImageUri();
                        processScannedPage(imageUri);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        catalog = AircraftCatalog.load(this);
        RegistrationMatcher matcher = new RegistrationMatcher(catalog.getAllRegistrations());
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        ocrProcessor = new RegistrationColumnOcrProcessor(
                textRecognizer,
                matcher,
                catalog.getAllRegistrations());
        resultsAdapter = new RegistrationAdapter(this, catalog, this::updateEmptyState);
        binding.resultsList.setAdapter(resultsAdapter);

        GmsDocumentScannerOptions scannerOptions =
                new GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit(1)
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build();
        documentScanner = GmsDocumentScanning.getClient(scannerOptions);

        configureActions();
        configureBackNavigation();
        restoreState(savedInstanceState);

        if (showingResults) {
            showResults(false);
        } else if (showingSummary) {
            showSummary(false);
        } else {
            showScannerLanding(false);
        }
    }

    private void configureActions() {
        binding.scanButton.setOnClickListener(view -> launchDocumentScanner());
        binding.scanAgainButton.setOnClickListener(view -> {
            showScannerLanding(true);
            launchDocumentScanner();
        });
        binding.cancelCropButton.setOnClickListener(view -> showScannerLanding(true));
        binding.analyzeColumnButton.setOnClickListener(view -> analyzeSelectedColumn());
        binding.addButton.setOnClickListener(view -> {
            resultsAdapter.addEmptyItem();
            binding.resultsList.post(() -> {
                int lastPosition = resultsAdapter.getItemCount() - 1;
                if (lastPosition >= 0) {
                    binding.resultsList.smoothScrollToPosition(lastPosition);
                }
            });
        });
        binding.confirmButton.setOnClickListener(view -> confirmRegistrations());
        binding.editRegistrationsButton.setOnClickListener(view -> showResults(false));
        binding.newScanButton.setOnClickListener(view -> {
            showScannerLanding(true);
            launchDocumentScanner();
        });
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (showingSummary) {
                    showResults(false);
                } else if (showingResults) {
                    showScannerLanding(false);
                } else if (showingCrop) {
                    showScannerLanding(true);
                } else if (!processing) {
                    finish();
                }
            }
        });
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        showingResults = savedInstanceState.getBoolean(STATE_RESULTS, false);
        showingSummary = savedInstanceState.getBoolean(STATE_SUMMARY, false);
        ArrayList<String> savedValues = savedInstanceState.getStringArrayList(STATE_VALUES);
        ArrayList<String> savedDressedAircraft =
                savedInstanceState.getStringArrayList(STATE_DRESSED_AIRCRAFT);
        if (savedDressedAircraft != null) {
            dressedAircraftRegistrations.clear();
            dressedAircraftRegistrations.addAll(savedDressedAircraft);
        }
        ArrayList<String> savedDressedHold =
                savedInstanceState.getStringArrayList(STATE_DRESSED_HOLD);
        if (savedDressedHold != null) {
            dressedHoldRegistrations.clear();
            dressedHoldRegistrations.addAll(savedDressedHold);
        }
        ArrayList<String> savedRetiredAircraft =
                savedInstanceState.getStringArrayList(STATE_RETIRED_AIRCRAFT_LOADS);
        if (savedRetiredAircraft != null) {
            retiredAircraftLoads.clear();
            retiredAircraftLoads.addAll(savedRetiredAircraft);
        }
        ArrayList<String> savedRetiredHold =
                savedInstanceState.getStringArrayList(STATE_RETIRED_HOLD_LOADS);
        if (savedRetiredHold != null) {
            retiredHoldLoads.clear();
            retiredHoldLoads.addAll(savedRetiredHold);
        }
        if (savedValues == null) {
            return;
        }
        if (showingResults || showingSummary) {
            resultsAdapter.replaceAll(savedValues);
        } else {
            detectedRegistrations.addAll(savedValues);
        }
    }

    private void launchDocumentScanner() {
        if (processing) {
            return;
        }
        setProcessing(true);
        lastScanCompleted = false;
        binding.processingText.setText(R.string.preparing_scanner);
        binding.processingProgress.setIndeterminate(true);

        documentScanner.getStartScanIntent(this)
                .addOnSuccessListener(intentSender -> {
                    setProcessing(false);
                    scannerLauncher.launch(
                            new IntentSenderRequest.Builder(intentSender).build());
                })
                .addOnFailureListener(error -> {
                    setProcessing(false);
                    showMessage(R.string.scanner_start_error);
                });
    }

    private void processScannedPage(Uri imageUri) {
        detectedRegistrations.clear();
        setProcessing(true);
        binding.processingText.setText(R.string.loading_scanned_page);
        binding.processingProgress.setIndeterminate(true);

        imageLoader.execute(() -> {
            try {
                Bitmap loadedPage = ScannedPageLoader.load(this, imageUri);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        recycle(loadedPage);
                        return;
                    }
                    releaseScannedPage();
                    scannedPage = loadedPage;
                    binding.columnCropView.setBitmap(scannedPage);
                    setProcessing(false);
                    showCropStep();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setProcessing(false);
                        showMessage(R.string.crop_load_error);
                    }
                });
            }
        });
    }

    private void analyzeSelectedColumn() {
        if (processing || !binding.columnCropView.hasBitmap()) {
            showMessage(R.string.crop_invalid);
            return;
        }

        Bitmap selectedColumn;
        try {
            selectedColumn = binding.columnCropView.createCroppedBitmap();
        } catch (RuntimeException error) {
            showMessage(R.string.crop_invalid);
            return;
        }

        showingCrop = false;
        binding.cropContainer.setVisibility(View.GONE);
        binding.scannerContainer.setVisibility(View.VISIBLE);
        releaseScannedPage();
        setProcessing(true);
        binding.processingText.setText(R.string.analyzing_column);
        binding.processingProgress.setIndeterminate(false);
        binding.processingProgress.setProgressCompat(0, false);

        ocrProcessor.process(this, selectedColumn, new RegistrationColumnOcrProcessor.Listener() {
            @Override
            public void onProgress(int completedPasses, int totalPasses) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                int progress = Math.round((completedPasses * 100f) / totalPasses);
                binding.processingProgress.setProgressCompat(progress, true);
                binding.processingText.setText(
                        getString(
                                R.string.analyzing_progress,
                                completedPasses,
                                totalPasses));
            }

            @Override
            public void onSuccess(
                    List<String> registrations,
                    boolean textWasDetected) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                lastScanCompleted = true;
                lastScanTextDetected = textWasDetected;
                detectedRegistrations.addAll(registrations);
                setProcessing(false);
                showResults(true);
            }

            @Override
            public void onError(Exception error) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setProcessing(false);
                showMessage(R.string.ocr_document_error);
            }
        });
    }

    private void showCropStep() {
        showingResults = false;
        showingSummary = false;
        showingCrop = true;
        binding.scannerContainer.setVisibility(View.GONE);
        binding.resultsContainer.setVisibility(View.GONE);
        binding.summaryContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.crop_column_title);
    }

    private void showResults(boolean replaceWithScan) {
        showingResults = true;
        showingSummary = false;
        showingCrop = false;
        if (replaceWithScan) {
            resultsAdapter.replaceAll(new ArrayList<>(detectedRegistrations));
        }
        binding.scannerContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.GONE);
        binding.summaryContainer.setVisibility(View.GONE);
        binding.resultsContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.results_title);
        updateEmptyState();
    }

    private void confirmRegistrations() {
        ArrayList<String> values = resultsAdapter.getValues();
        if (values.isEmpty()) {
            showMessage(R.string.confirm_empty_results);
            return;
        }
        for (String registration : values) {
            if (!catalog.contains(registration)) {
                showMessage(R.string.confirm_invalid_registrations);
                return;
            }
        }
        captureRetiredLoads(new ArrayList<>(detectedRegistrations), values);
        resultsAdapter.replaceAll(values);
        dressedAircraftRegistrations.retainAll(values);
        dressedHoldRegistrations.retainAll(values);
        showSummary(true);
    }

    private void captureRetiredLoads(List<String> previousValues, List<String> currentValues) {
        Set<String> current = new LinkedHashSet<>(currentValues);
        for (String registration : previousValues) {
            if (current.contains(registration)) {
                continue;
            }
            if (dressedAircraftRegistrations.contains(registration)) {
                retiredAircraftLoads.add(registration);
            }
            if (dressedHoldRegistrations.contains(registration)) {
                retiredHoldLoads.add(registration);
            }
        }
    }

    private void showSummary(boolean recalculateValues) {
        showingResults = false;
        showingSummary = true;
        showingCrop = false;
        if (recalculateValues) {
            detectedRegistrations.clear();
            detectedRegistrations.addAll(resultsAdapter.getValues());
        }

        BlanketCalculator.Summary summary =
                BlanketCalculator.calculate(catalog, resultsAdapter.getValues());

        bindTotalSummary(summary);
        bindChangeImpact(summary);
        bindTrackingList(resultsAdapter.getValues());
        updateRemainingSummary();

        binding.scannerContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.GONE);
        binding.resultsContainer.setVisibility(View.GONE);
        binding.summaryContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.summary_title);
    }

    private void showScannerLanding(boolean clearPreviousScan) {
        boolean comingFromReview = showingResults || showingSummary;
        showingResults = false;
        showingSummary = false;
        showingCrop = false;
        setProcessing(false);
        releaseScannedPage();

        if (clearPreviousScan) {
            detectedRegistrations.clear();
            dressedAircraftRegistrations.clear();
            dressedHoldRegistrations.clear();
            retiredAircraftLoads.clear();
            retiredHoldLoads.clear();
        } else if (comingFromReview) {
            detectedRegistrations.clear();
            detectedRegistrations.addAll(resultsAdapter.getValues());
        }

        binding.resultsContainer.setVisibility(View.GONE);
        binding.summaryContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.GONE);
        binding.scannerContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.scanner_title);
    }

    private void bindTotalSummary(BlanketCalculator.Summary summary) {
        binding.summaryAircraftCount.setText(
                getString(R.string.summary_aircraft_count, summary.getAircraftCount()));
        binding.touristBlanketsValue.setText(
                getString(R.string.summary_blankets_value, summary.getTouristBlankets()));
        binding.thickBlanketsValue.setText(
                getString(R.string.summary_blankets_value, summary.getThickBlankets()));
        binding.toppersValue.setText(
                getString(R.string.summary_blankets_value, summary.getToppers()));
        binding.touristCagesValue.setText(
                getString(R.string.summary_cages_value, summary.getTouristCages()));
        binding.thickCagesValue.setText(
                getString(R.string.summary_cages_value, summary.getThickCages()));
        binding.topperCagesValue.setText(
                getString(R.string.summary_cages_value, summary.getTopperCages()));
    }

    private void bindChangeImpact(BlanketCalculator.Summary activeSummary) {
        ArrayList<String> retiredLoads = new ArrayList<>();
        retiredLoads.addAll(retiredAircraftLoads);
        retiredLoads.addAll(retiredHoldLoads);
        BlanketCalculator.Summary retiredSummary =
                BlanketCalculator.calculateSingleLoad(catalog, retiredLoads);
        if (retiredSummary.getTouristBlankets() == 0
                && retiredSummary.getThickBlankets() == 0
                && retiredSummary.getToppers() == 0) {
            binding.changeImpactText.setVisibility(View.GONE);
            return;
        }

        BlanketCalculator.Summary realSummary =
                BlanketCalculator.combine(activeSummary, retiredSummary);
        binding.changeImpactText.setText(getString(
                R.string.summary_change_impact,
                retiredSummary.getTouristBlankets(),
                retiredSummary.getThickBlankets(),
                retiredSummary.getToppers(),
                realSummary.getTouristBlankets(),
                realSummary.getThickBlankets(),
                realSummary.getToppers(),
                realSummary.getTouristCages(),
                realSummary.getThickCages(),
                realSummary.getTopperCages()));
        binding.changeImpactText.setVisibility(View.VISIBLE);
    }

    private void bindTrackingList(List<String> registrations) {
        binding.trackingListContainer.removeAllViews();
        dressedAircraftRegistrations.retainAll(registrations);
        dressedHoldRegistrations.retainAll(registrations);

        for (String registration : registrations) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 10, 0, 10);

            TextView title = new TextView(this);
            title.setText(registration + " · " + catalog.categoryFor(registration));
            title.setTextSize(16f);
            title.setTextColor(getColor(R.color.ink));
            title.setTypeface(null, android.graphics.Typeface.BOLD);

            LinearLayout checks = new LinearLayout(this);
            checks.setOrientation(LinearLayout.HORIZONTAL);
            checks.setPadding(0, 6, 0, 0);

            CheckBox aircraftCheckBox = createTrackingCheckBox(R.string.tracking_aircraft);
            CheckBox holdCheckBox = createTrackingCheckBox(R.string.tracking_hold);

            aircraftCheckBox.setChecked(dressedAircraftRegistrations.contains(registration));
            holdCheckBox.setChecked(dressedHoldRegistrations.contains(registration));
            updateTrackingRowStyle(title, aircraftCheckBox, holdCheckBox);

            aircraftCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    dressedAircraftRegistrations.add(registration);
                } else {
                    dressedAircraftRegistrations.remove(registration);
                }
                updateTrackingRowStyle(title, aircraftCheckBox, holdCheckBox);
                updateRemainingSummary();
            });
            holdCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    dressedHoldRegistrations.add(registration);
                } else {
                    dressedHoldRegistrations.remove(registration);
                }
                updateTrackingRowStyle(title, aircraftCheckBox, holdCheckBox);
                updateRemainingSummary();
            });

            checks.addView(aircraftCheckBox);
            checks.addView(holdCheckBox);
            row.addView(title);
            row.addView(checks);
            binding.trackingListContainer.addView(row);
        }
    }

    private CheckBox createTrackingCheckBox(int labelRes) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(labelRes);
        checkBox.setTextSize(15f);
        checkBox.setTextColor(getColor(R.color.ink));
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.blue_700)));
        checkBox.setPadding(0, 0, 18, 0);
        checkBox.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
        return checkBox;
    }

    private void updateTrackingRowStyle(
            TextView title,
            CheckBox aircraftCheckBox,
            CheckBox holdCheckBox) {
        updateTrackingCheckBoxStyle(aircraftCheckBox);
        updateTrackingCheckBoxStyle(holdCheckBox);
        if (aircraftCheckBox.isChecked() && holdCheckBox.isChecked()) {
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            title.setAlpha(0.55f);
        } else {
            title.setPaintFlags(title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            title.setAlpha(1f);
        }
    }

    private void updateTrackingCheckBoxStyle(CheckBox checkBox) {
        if (checkBox.isChecked()) {
            checkBox.setPaintFlags(checkBox.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            checkBox.setAlpha(0.65f);
        } else {
            checkBox.setPaintFlags(checkBox.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            checkBox.setAlpha(1f);
        }
    }

    private void updateRemainingSummary() {
        ArrayList<String> remaining = new ArrayList<>();
        for (String registration : resultsAdapter.getValues()) {
            if (!dressedAircraftRegistrations.contains(registration)) {
                remaining.add(registration);
            }
            if (!dressedHoldRegistrations.contains(registration)) {
                remaining.add(registration);
            }
        }
        BlanketCalculator.Summary remainingSummary =
                BlanketCalculator.calculateSingleLoad(catalog, remaining);

        binding.remainingTouristBlanketsValue.setText(
                getString(R.string.summary_blankets_value, remainingSummary.getTouristBlankets()));
        binding.remainingThickBlanketsValue.setText(
                getString(R.string.summary_blankets_value, remainingSummary.getThickBlankets()));
        binding.remainingToppersValue.setText(
                getString(R.string.summary_blankets_value, remainingSummary.getToppers()));
        binding.remainingTouristCagesValue.setText(
                getString(R.string.summary_cages_value, remainingSummary.getTouristCages()));
        binding.remainingThickCagesValue.setText(
                getString(R.string.summary_cages_value, remainingSummary.getThickCages()));
        binding.remainingTopperCagesValue.setText(
                getString(R.string.summary_cages_value, remainingSummary.getTopperCages()));
    }

    private void setProcessing(boolean isProcessing) {
        processing = isProcessing;
        if (binding == null) {
            return;
        }
        binding.processingCard.setVisibility(isProcessing ? View.VISIBLE : View.GONE);
        binding.scanContent.setAlpha(isProcessing ? 0.35f : 1f);
        binding.scanButton.setEnabled(!isProcessing);
    }

    private void updateEmptyState() {
        if (binding == null || resultsAdapter == null) {
            return;
        }
        boolean empty = resultsAdapter.getItemCount() == 0;
        if (empty) {
            int emptyMessage = R.string.empty_results;
            if (lastScanCompleted) {
                emptyMessage = lastScanTextDetected
                        ? R.string.empty_results_text_detected
                        : R.string.empty_results_no_text;
            }
            binding.emptyResults.setText(emptyMessage);
        }
        binding.emptyResults.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.resultsList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.confirmButton.setEnabled(!empty);
    }

    private void showMessage(int messageRes) {
        Snackbar.make(binding.getRoot(), messageRes, Snackbar.LENGTH_LONG).show();
    }

    private void releaseScannedPage() {
        if (binding != null) {
            binding.columnCropView.setBitmap(null);
        }
        recycle(scannedPage);
        scannedPage = null;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_RESULTS, showingResults);
        outState.putBoolean(STATE_SUMMARY, showingSummary);
        outState.putStringArrayList(
                STATE_VALUES,
                showingResults || showingSummary
                        ? resultsAdapter.getValues()
                        : new ArrayList<>(detectedRegistrations));
        outState.putStringArrayList(
                STATE_DRESSED_AIRCRAFT,
                new ArrayList<>(dressedAircraftRegistrations));
        outState.putStringArrayList(
                STATE_DRESSED_HOLD,
                new ArrayList<>(dressedHoldRegistrations));
        outState.putStringArrayList(
                STATE_RETIRED_AIRCRAFT_LOADS,
                new ArrayList<>(retiredAircraftLoads));
        outState.putStringArrayList(
                STATE_RETIRED_HOLD_LOADS,
                new ArrayList<>(retiredHoldLoads));
    }

    @Override
    protected void onDestroy() {
        releaseScannedPage();
        imageLoader.shutdownNow();
        if (ocrProcessor != null) {
            ocrProcessor.close();
        }
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        super.onDestroy();
    }
}
