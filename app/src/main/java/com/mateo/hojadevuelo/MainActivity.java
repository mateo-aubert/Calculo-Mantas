package com.mateo.hojadevuelo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

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
    private static final String STATE_VALUES = "state_values";

    private ActivityMainBinding binding;
    private RegistrationAdapter resultsAdapter;
    private TextRecognizer textRecognizer;
    private RegistrationColumnOcrProcessor ocrProcessor;
    private GmsDocumentScanner documentScanner;
    private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bitmap scannedPage;

    private final Set<String> detectedRegistrations = new LinkedHashSet<>();
    private boolean showingResults;
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

        AircraftCatalog catalog = AircraftCatalog.load(this);
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
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (showingResults) {
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
        ArrayList<String> savedValues = savedInstanceState.getStringArrayList(STATE_VALUES);
        if (savedValues == null) {
            return;
        }
        if (showingResults) {
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
        showingCrop = true;
        binding.scannerContainer.setVisibility(View.GONE);
        binding.resultsContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.crop_column_title);
    }

    private void showResults(boolean replaceWithScan) {
        showingResults = true;
        showingCrop = false;
        if (replaceWithScan) {
            resultsAdapter.replaceAll(new ArrayList<>(detectedRegistrations));
        }
        binding.scannerContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.GONE);
        binding.resultsContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.results_title);
        updateEmptyState();
    }

    private void showScannerLanding(boolean clearPreviousScan) {
        boolean comingFromResults = showingResults;
        showingResults = false;
        showingCrop = false;
        setProcessing(false);
        releaseScannedPage();

        if (clearPreviousScan) {
            detectedRegistrations.clear();
        } else if (comingFromResults) {
            detectedRegistrations.clear();
            detectedRegistrations.addAll(resultsAdapter.getValues());
        }

        binding.resultsContainer.setVisibility(View.GONE);
        binding.cropContainer.setVisibility(View.GONE);
        binding.scannerContainer.setVisibility(View.VISIBLE);
        binding.header.setText(R.string.scanner_title);
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
        outState.putStringArrayList(
                STATE_VALUES,
                showingResults
                        ? resultsAdapter.getValues()
                        : new ArrayList<>(detectedRegistrations));
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
