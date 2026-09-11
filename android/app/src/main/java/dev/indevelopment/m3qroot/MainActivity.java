package dev.indevelopment.m3qroot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.ImageView;
import android.widget.TextView;
import android.text.method.ScrollingMovementMethod;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 0x4d33;
    private static final String KSU_MANAGER_PACKAGE = "me.weishu.kernelsu";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final String KSU_RELEASES_URL = "https://github.com/tiann/KernelSU/releases";
    /** Bundled LKM driver version (must match M3qRootEngine's pins). */
    private static final int KSU_DRIVER_VERSION = 32525;
    /** Escape sequences the payloads emit for terminals; the log view is not one. */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final int STATUS_SUCCESS = 0xff18753c;
    private static final int STATUS_WORKING = 0xff9a6700;
    private static final int STATUS_WARNING = 0xffb3261e;
    private static final int STATUS_NEUTRAL = 0xff5f6b76;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean shizukuPermissionPending = new AtomicBoolean();
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
                if (!shizukuPermissionPending.compareAndSet(true, false)) return;
                ui.post(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        append("Shizuku shell permission granted");
                        beginExploit(true);
                    } else {
                        abortPendingRun("Not running: Shizuku permission denied.");
                    }
                });
            };

    private M3qRootEngine engine;
    private MaterialCardView statusCard;
    private MaterialCardView diagnosticsCard;
    private MaterialCardView bannerCard;
    private TextView status;
    private TextView statusDetail;
    private TextView statusVersion;
    private TextView miniShizuku;
    private TextView miniBoot;
    private TextView bannerText;
    private TextView infoManager;
    private TextView infoKernel;
    private TextView infoDevice;
    private TextView infoFingerprint;
    private TextView infoSelinux;
    private TextView checkDevice;
    private TextView checkShizuku;
    private TextView checkKsu;
    private TextView checkBoot;
    private TextView log;
    private ImageView checkIcon;
    private int bannerAction;
    private String selinuxCache;
    private int defaultSurface;
    private int defaultOnSurfaceVariant;
    private boolean showingSettle;
    private final Runnable settleTicker = new Runnable() {
        @Override
        public void run() {
            if (running.get() || !showingSettle) return;
            long remain = M3qRootEngine.bootSettleRemainingMillis();
            if (remain <= 0) {
                showingSettle = false;
                worker.execute(() -> refreshRootState());
                return;
            }
            long seconds = (remain + 999) / 1000;
            String text = "Ready in ~" + seconds + "s";
            miniBoot.setText(text);
            checkBoot.setText(getString(R.string.check_format, "○", "This boot", text));
            setStatusDetail("Supported device confirmed - ready in ~"
                    + seconds + "s. Tap Recheck status then.");
            ui.postDelayed(this, 1000);
        }
    };
    private MaterialButton run;
    private MaterialButton reapplyModules;
    private MaterialButton restartZygote;
    private MaterialButton statusRefresh;
    private MaterialButton copyInfo;
    private MaterialButton diagnosticsToggle;
    private SwitchMaterial bypassSettle;
    private LinearProgressIndicator runProgress;
    private boolean diagnosticsVisible;

    @Override
    protected void onCreate(Bundle state) {
        // Material You: use the wallpaper-based dynamic palette on Android 12+,
        // fall back to the branded m3q_* colors on older releases.
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.page_scroll));
        bindViews();
        bindActions();
        defaultSurface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        defaultOnSurfaceVariant = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF808080);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (running.get()) {
                        append("Cannot close the app until the kernel work finishes.");
                    } else {
                        finish();
                    }
                });

        engine = new M3qRootEngine(this, new M3qRootEngine.Listener() {
            @Override
            public void onStatus(String text, int color) {
                setStatus(text, color);
            }

            @Override
            public void onLog(String line) {
                append(line);
            }
        });
        setupBypassToggle();
        // One session file per app launch; every log line lands here so any
        // run (including oracle-only failures) is shareable and pullable.
        resetSessionLog();

        append("==== device diagnostics ====");
        append("Model: " + Build.MODEL);
        append("Kernel: " + System.getProperty("os.version", "unknown"));
        append("Firmware: " + Build.FINGERPRINT);

        if (!engine.isSupported()) {
            setStatus("Unsupported firmware", STATUS_WARNING);
            setStatusDetail("This app only runs on the SM-S948U AZF1 firmware.");
            run.setEnabled(false);
            append("Only the exact SM-S948U AZF1 build is supported.");
        } else {
            setStatus(getString(R.string.status_checking), STATUS_WORKING);
            setStatusDetail(getString(R.string.status_checking_detail));
        }
    }

    private void bindViews() {
        statusCard = findViewById(R.id.status_card);
        diagnosticsCard = findViewById(R.id.diagnostics_card);
        status = findViewById(R.id.status);
        statusDetail = findViewById(R.id.status_detail);
        statusVersion = findViewById(R.id.status_version);
        checkIcon = findViewById(R.id.check_icon);
        miniShizuku = findViewById(R.id.mini_shizuku_value);
        miniBoot = findViewById(R.id.mini_boot_value);
        bannerCard = findViewById(R.id.banner_card);
        bannerText = findViewById(R.id.banner_text);
        infoManager = findViewById(R.id.info_manager_value);
        infoKernel = findViewById(R.id.info_kernel_value);
        infoDevice = findViewById(R.id.info_device_value);
        infoFingerprint = findViewById(R.id.info_fingerprint_value);
        infoSelinux = findViewById(R.id.info_selinux_value);
        checkDevice = findViewById(R.id.check_device);
        checkShizuku = findViewById(R.id.check_shizuku);
        checkKsu = findViewById(R.id.check_ksu);
        checkBoot = findViewById(R.id.check_boot);
        log = findViewById(R.id.log);
        log.setMovementMethod(new ScrollingMovementMethod());
        // Let the log view scroll internally instead of fighting the page scroll.
        log.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        run = findViewById(R.id.run);
        reapplyModules = findViewById(R.id.reapply_modules);
        restartZygote = findViewById(R.id.restart_zygote);
        statusRefresh = findViewById(R.id.status_refresh);
        copyInfo = findViewById(R.id.copy_info);
        runProgress = findViewById(R.id.run_progress);
        bypassSettle = findViewById(R.id.bypass_settle);
        diagnosticsToggle = findViewById(R.id.diagnostics_toggle);
    }

    private static void applySystemBarInsets(View view) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            target.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return windowInsets;
        });
        view.requestApplyInsets();
    }

    private void bindActions() {
        run.setOnClickListener(v -> onRunClicked());
        reapplyModules.setOnClickListener(v -> confirmModuleReload());
        restartZygote.setOnClickListener(v -> confirmSoftBoot());
        statusRefresh.setOnClickListener(v -> worker.execute(this::refreshRootState));
        copyInfo.setOnClickListener(v -> copyDeviceInfo());
        findViewById(R.id.root_manager).setOnClickListener(v ->
                openPackage(KSU_MANAGER_PACKAGE,
                        "KernelSU Manager is not installed."));
        findViewById(R.id.shizuku_manager).setOnClickListener(v ->
                openPackage(SHIZUKU_MANAGER_PACKAGE,
                        "Shizuku Manager is not installed."));
        findViewById(R.id.share_log).setOnClickListener(v -> shareLastLog());
        findViewById(R.id.copy_log).setOnClickListener(v -> copySessionLog());
        diagnosticsToggle.setOnClickListener(v -> toggleDiagnostics());
        bannerCard.setOnClickListener(v -> onBannerClicked());
    }

    private void setupBypassToggle() {
        // Deliberately not persisted: bypass resets every app launch so it
        // always requires a conscious opt-in.
        bypassSettle.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                engine.setBypassBootSettle(false);
                append("Settle gate bypass disabled.");
                if (!running.get()) worker.execute(this::refreshRootState);
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bypass_warning_title)
                    .setMessage(R.string.bypass_warning_message)
                    .setNegativeButton(R.string.cancel,
                            (dialog, which) -> bypassSettle.setChecked(false))
                    .setPositiveButton(R.string.bypass_warning_confirm,
                            (dialog, which) -> {
                                engine.setBypassBootSettle(true);
                                append(getString(R.string.bypass_enabled_log));
                                if (!running.get()) worker.execute(this::refreshRootState);
                            })
                    .setOnCancelListener(dialog -> bypassSettle.setChecked(false))
                    .show();
        });
    }

    private void onBannerClicked() {
        if (bannerAction == 2) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KSU_RELEASES_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException error) {
                append("Cannot open releases page: " + error.getMessage());
            }
            return;
        }
        if (bannerAction == 1) {
            openPackage(KSU_MANAGER_PACKAGE,
                    "KernelSU Manager is not installed.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null && engine.isSupported() && !running.get()) {
            worker.execute(this::refreshRootState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(settleTicker);
    }

    private void toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible;
        diagnosticsCard.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
        diagnosticsToggle.setText(diagnosticsVisible
                ? R.string.hide_diagnostics : R.string.show_diagnostics);
        if (diagnosticsVisible) {
            scrollLogToBottom();
        }
    }

    private void onRunClicked() {
        if (!running.compareAndSet(false, true)) return;
        worker.execute(() -> {
            M3qRootEngine.RootState current = engine.checkRoot(false);
            if (current.terminationUnconfirmed()) {
                finishUnconfirmedRun();
                return;
            }
            if (current.ready()) {
                finishRun(current);
                return;
            }
            if (current.bootstrap()) {
                append("Bootstrap root detected - activating KernelSU only, without rerunning the exploit.");
                setStatus("Activating KernelSU", STATUS_WORKING);
                setStatusDetail("Finishing KernelSU setup without repeating kernel writes.");
                ui.post(this::lockUiForRun);
                int code = engine.activateKernelSu();
                append("KernelSU activation exit=" + code);
                if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                    finishUnconfirmedRun();
                    return;
                }
                finishRun(engine.checkRoot(true));
                return;
            }
            if (engine.hasAttemptedThisBoot()) {
                running.set(false);
                ui.post(() -> {
                    run.setVisibility(View.VISIBLE);
                    run.setEnabled(false);
                    setStatus("Already ran in this boot", STATUS_WARNING);
                    setStatusDetail("For safety, cannot run again before rebooting.");
                    append("Blocked a retry with the same boot ID.");
                    renderDashboard(current);
                });
                return;
            }
            running.set(false);
            ui.post(this::confirmFreshRoot);
        });
    }

    private void confirmFreshRoot() {
        String message = getString(R.string.root_dialog_message);
        if (engine.isBypassBootSettle()
                && M3qRootEngine.bootSettleRemainingMillis() > 0) {
            message += "\n\n" + getString(R.string.root_dialog_bypass_warning);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.root_dialog_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.root_dialog_confirm,
                        (dialog, which) -> startExploit())
                .show();
    }

    private void startExploit() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        append("==== fresh-root start ====");

        // Shizuku-only: the unprivileged physical-P0 fallback is disabled on
        // AZF1 after repeated 45-120s scan timeouts and kernel panics.
        if (!ShizukuShell.isRunning()) {
            abortPendingRun(getString(R.string.shizuku_required_message));
            return;
        }
        int uid = ShizukuShell.uid();
        append("Shizuku detected: uid=" + uid + " - tracefs fast path");
        if (!ShizukuShell.isGranted()) {
            setStatus("Shizuku permission approval needed", STATUS_WORKING);
            setStatusDetail("Approve the Shizuku permission request shown.");
            try {
                shizukuPermissionPending.set(true);
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
            } catch (RuntimeException error) {
                shizukuPermissionPending.set(false);
                abortPendingRun("Shizuku permission request error: " + error.getMessage());
            }
            return;
        }
        beginExploit(true);
    }

    private void beginExploit(boolean useShizuku) {
        long settleMillis = M3qRootEngine.bootSettleRemainingMillis();
        if (settleMillis > 0 && !engine.isBypassBootSettle()) {
            long settleSeconds = (settleMillis + 999) / 1000;
            abortPendingRun("For post-boot system settling, retry in about " + settleSeconds
                    + "s. This boot's attempt was not consumed.");
            return;
        }
        if (settleMillis > 0) {
            append("Settle gate bypassed with ~" + ((settleMillis + 999) / 1000)
                    + "s remaining - panic risk is higher.");
        }
        if (!engine.markAttemptForThisBoot()) {
            abortPendingRun("Refused kernel run: boot state could not be verified.");
            return;
        }
        setStatus("Activating temporary root", STATUS_WORKING);
        setStatusDetail(useShizuku
                ? "Verifying safety conditions over the Shizuku connection."
                : "Checking device security state, then applying temporary root.");
        worker.execute(() -> {
            int code = engine.runFreshRoot(useShizuku);
            append("fresh-root exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishRun(engine.checkRoot(true));
        });
    }

    private void confirmModuleReload() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soft_root_dialog_title)
                .setMessage(R.string.soft_root_dialog_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.soft_root_dialog_confirm,
                        (dialog, which) -> startModuleReload())
                .show();
    }

    private void startModuleReload() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        setStatus("Reloading KernelSU modules", STATUS_WORKING);
        setStatusDetail("Rerunning KernelSU module startup stages.");
        append("==== KernelSU module reapply start ====");
        worker.execute(() -> {
            M3qRootEngine.RootState state = engine.checkRoot(false);
            if (!state.ready()) {
                append("Not running: KernelSU temporary root is not active.");
                finishMaintenance(126, state, "", "");
                return;
            }
            int code = engine.reapplyKernelSuModules();
            append("module reapply exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishMaintenance(code, engine.checkRoot(false),
                    "Module reload complete",
                    "KernelSU modules reapplied. Proceed with soft boot.");
        });
    }

    private void confirmSoftBoot() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lsposed_dialog_title)
                .setMessage(R.string.lsposed_dialog_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.lsposed_dialog_confirm,
                        (dialog, which) -> startSoftBoot())
                .show();
    }

    private void startSoftBoot() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        setStatus("Preparing soft boot", STATUS_WORKING);
        setStatusDetail("Restarting the Android app runtime (Zygote).");
        append("Requesting Zygote restart. This app will also exit on success.");
        worker.execute(() -> {
            M3qRootEngine.RootState state = engine.checkRoot(false);
            if (!state.ready()) {
                append("Not running: KernelSU temporary root is not active.");
                finishMaintenance(126, state, "", "");
                return;
            }
            int code = engine.restartZygote();
            append("zygote restart exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishMaintenance(code, engine.checkRoot(false),
                    "Soft boot requested",
                    "Check active status in the LSPosed manager shortly.");
        });
    }

    private void finishMaintenance(int code, M3qRootEngine.RootState state,
                                   String successText, String successDetail) {
        running.set(false);
        hideProgress();
        ui.post(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusRefresh.setEnabled(true);
            copyInfo.setEnabled(true);
            renderRootState(state);
            if (code == 0) {
                setStatus(successText, STATUS_SUCCESS);
                setStatusDetail(successDetail);
                return;
            }
            String reason = switch (code) {
                case 124 -> "Could not confirm task completion.";
                case 125 -> "KernelSU setup verification failed.";
                case 126 -> "KernelSU root permission required.";
                default -> "Command execution failed. code=" + code;
            };
            setStatus("Task failed", STATUS_WARNING);
            setStatusDetail(reason + " Check status again.");
        });
    }

    private void lockUiForRun() {
        showingSettle = false;
        ui.removeCallbacks(settleTicker);
        run.setEnabled(false);
        reapplyModules.setEnabled(false);
        restartZygote.setEnabled(false);
        statusRefresh.setEnabled(false);
        copyInfo.setEnabled(false);
        showProgress();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showProgress() {
        ui.post(() -> runProgress.setVisibility(View.VISIBLE));
    }

    private void hideProgress() {
        ui.post(() -> runProgress.setVisibility(View.GONE));
    }

    private void abortPendingRun(String message) {
        append(message);
        running.set(false);
        hideProgress();
        ui.post(() -> {
            run.setVisibility(View.VISIBLE);
            run.setEnabled(engine.isSupported());
            reapplyModules.setEnabled(false);
            restartZygote.setEnabled(false);
            statusRefresh.setEnabled(true);
            copyInfo.setEnabled(true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("Not run", STATUS_NEUTRAL);
            setStatusDetail(message);
        });
    }

    private void refreshRootState() {
        M3qRootEngine.RootState state = engine.checkRoot(false);
        ui.post(() -> renderRootState(state));
    }

    private void renderRootState(M3qRootEngine.RootState state) {
        showingSettle = false;
        ui.removeCallbacks(settleTicker);
        if (state.terminationUnconfirmed()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Task status unknown", STATUS_WARNING);
            setStatusDetail("For safety, reboot the device and check again.");
            setStatusVersion(getString(R.string.version_unknown));
            styleStatusCard(false);
            run.setText(R.string.run_reboot_check);
            run.setEnabled(false);
        } else if (state.ready()) {
            setStatus("Working <LKM>", STATUS_SUCCESS);
            setStatusDetail("[Temp root mode] · removed on reboot");
            setStatusVersion(getString(R.string.version_format, KSU_DRIVER_VERSION));
            styleStatusCard(true);
            run.setVisibility(View.GONE);
        } else if (state.bootstrap()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Root ready", STATUS_WORKING);
            setStatusDetail("Only the KernelSU activation step remains.");
            setStatusVersion(getString(R.string.version_unknown));
            styleStatusCard(false);
            run.setText(R.string.run_kernel_su_activate);
            run.setEnabled(true);
        } else if (engine.hasAttemptedThisBoot()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Already ran in this boot", STATUS_WARNING);
            setStatusDetail("For safety, cannot run again before rebooting.");
            setStatusVersion(getString(R.string.version_unknown));
            styleStatusCard(false);
            run.setText(R.string.run_reboot_retry);
            run.setEnabled(false);
        } else {
            run.setVisibility(View.VISIBLE);
            setStatus("Not rooted", STATUS_NEUTRAL);
            long settleMillis = M3qRootEngine.bootSettleRemainingMillis();
            if (settleMillis > 0 && engine.isBypassBootSettle()) {
                setStatusDetail("Supported device confirmed - ready to run "
                        + "(settle gate bypassed, higher panic risk).");
            } else if (settleMillis > 0) {
                long settleSeconds = (settleMillis + 999) / 1000;
                long uptimeSeconds = SystemClock.elapsedRealtime() / 1000;
                setStatusDetail("Supported device confirmed - ready in ~"
                        + settleSeconds + "s (uptime " + uptimeSeconds
                        + "s of 180s). Tap Recheck status then.");
                // Live countdown: tick every second until the settle window
                // passes, then auto-refresh once.
                showingSettle = true;
                ui.postDelayed(settleTicker, 1000);
            } else {
                setStatusDetail("Supported device confirmed - ready to run");
            }
            setStatusVersion(getString(R.string.version_unknown));
            styleStatusCard(false);
            run.setText(R.string.root_activate);
            run.setEnabled(engine.isSupported());
        }
        boolean maintenanceReady = state.ready() && !running.get();
        reapplyModules.setEnabled(maintenanceReady);
        restartZygote.setEnabled(maintenanceReady);
        statusRefresh.setEnabled(!running.get());
        copyInfo.setEnabled(!running.get());
        hideProgress();
        renderDashboard(state);
    }

    private void renderDashboard(M3qRootEngine.RootState state) {
        styleStatusCard(state.ready());
        boolean shizukuRunning = ShizukuShell.isRunning();
        boolean shizukuGranted = ShizukuShell.isGranted();
        int shizukuUid = ShizukuShell.uid();
        String shizuku = !shizukuRunning ? "Not connected"
                : !shizukuGranted ? "Approval needed"
                : (shizukuUid == 2000 || shizukuUid == 0)
                ? "Connected" : "Restricted";
        PackageInfo managerInfo = kernelSuManagerInfo();
        String manager = managerInfo == null ? "Needs install" : "Installed";
        miniShizuku.setText(shizuku);
        long settleMillis = M3qRootEngine.bootSettleRemainingMillis();
        String boot;
        if (engine.hasAttemptedThisBoot()) {
            boot = "Ran";
        } else if (settleMillis > 0 && engine.isBypassBootSettle()) {
            boot = getString(R.string.boot_bypassed);
        } else if (settleMillis > 0) {
            boot = "Ready in ~" + ((settleMillis + 999) / 1000) + "s";
        } else {
            boot = "Ready";
        }
        miniBoot.setText(boot);
        infoManager.setText(managerInfo == null
                ? getString(R.string.info_manager_missing)
                : formatManagerVersion(managerInfo));
        infoKernel.setText(System.getProperty("os.version", "unknown"));
        infoDevice.setText(deviceMarketingName());
        infoFingerprint.setText(Build.FINGERPRINT);
        infoSelinux.setText(selinuxStatus());
        updateBanner(managerInfo);
        renderChecklist(shizukuRunning, shizukuGranted, shizukuUid, manager);
    }

    private void styleStatusCard(boolean active) {
        if (active) {
            statusCard.setCardBackgroundColor(0xFF1D5C34);
            statusCard.setStrokeColor(0xFF1D5C34);
            status.setTextColor(0xFFFFFFFF);
            statusDetail.setTextColor(0xFFE8F5E9);
            statusVersion.setTextColor(0xFFB9E6C5);
            checkIcon.setVisibility(View.VISIBLE);
            return;
        }
        statusCard.setCardBackgroundColor(defaultSurface);
        statusDetail.setTextColor(defaultOnSurfaceVariant);
        statusVersion.setTextColor(defaultOnSurfaceVariant);
        checkIcon.setVisibility(View.GONE);
    }

    private void updateBanner(PackageInfo managerInfo) {
        if (managerInfo == null) {
            bannerAction = 1;
            bannerText.setText(R.string.banner_manager_missing);
            bannerCard.setVisibility(View.VISIBLE);
            return;
        }
        if (managerInfo.getLongVersionCode() != KSU_DRIVER_VERSION) {
            bannerAction = 2;
            bannerText.setText(getString(R.string.banner_manager_mismatch,
                    formatManagerVersion(managerInfo), KSU_DRIVER_VERSION));
            bannerCard.setVisibility(View.VISIBLE);
            return;
        }
        bannerAction = 0;
        bannerCard.setVisibility(View.GONE);
    }

    private static String formatManagerVersion(PackageInfo info) {
        String name = info.versionName == null ? "?" : info.versionName;
        if (!name.startsWith("v")) name = "v" + name;
        return name + " (" + info.getLongVersionCode() + ")";
    }

    private PackageInfo kernelSuManagerInfo() {
        try {
            return getPackageManager().getPackageInfo(KSU_MANAGER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String deviceMarketingName() {
        if ("SM-S948U".equals(Build.MODEL)) return "Samsung Galaxy S26 Ultra";
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!manufacturer.isEmpty()) {
            manufacturer = manufacturer.substring(0, 1).toUpperCase()
                    + manufacturer.substring(1);
        }
        return (manufacturer + " " + Build.MODEL).trim();
    }

    private String selinuxStatus() {
        if (selinuxCache != null) return selinuxCache;
        String result = readEnforceFile();
        if (result == null) result = reflectEnforce();
        if (result == null) result = "Unknown";
        selinuxCache = result;
        return result;
    }

    private static String readEnforceFile() {
        try (BufferedReader in = new BufferedReader(new FileReader("/sys/fs/selinux/enforce"))) {
            String line = in.readLine();
            if (line != null) {
                line = line.trim();
                if ("1".equals(line)) return "Enforcing";
                if ("0".equals(line)) return "Permissive";
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String reflectEnforce() {
        // /sys/fs/selinux/enforce is often unreadable to apps; fall back to
        // the platform SELinux flag via reflection.
        try {
            Class<?> selinux = Class.forName("android.os.SELinux");
            Object enforced = selinux.getMethod("isSELinuxEnforced").invoke(null);
            if (enforced instanceof Boolean) return (Boolean) enforced ? "Enforcing" : "Permissive";
            Object enabled = selinux.getMethod("isSELinuxEnabled").invoke(null);
            if (enabled instanceof Boolean && !(Boolean) enabled) return "Disabled";
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private void renderChecklist(boolean shizukuRunning, boolean shizukuGranted,
                                 int shizukuUid, String manager) {
        boolean supported = engine.isSupported();
        checkDevice.setText(getString(R.string.check_format,
                supported ? "✓" : "✕", "Device", supported ? "Supported" : "Not supported"));
        String shizukuMark;
        String shizukuValue;
        if (!shizukuRunning) {
            shizukuMark = "○";
            shizukuValue = "Not connected";
        } else if (!shizukuGranted) {
            shizukuMark = "○";
            shizukuValue = "Approval needed";
        } else if (shizukuUid == 2000 || shizukuUid == 0) {
            shizukuMark = "✓";
            shizukuValue = "Connected";
        } else {
            shizukuMark = "○";
            shizukuValue = "Restricted";
        }
        checkShizuku.setText(getString(R.string.check_format,
                shizukuMark, "Shizuku", shizukuValue));
        boolean ksuInstalled = !"Needs install".equals(manager);
        checkKsu.setText(getString(R.string.check_format,
                ksuInstalled ? "✓" : "○", "KernelSU app",
                ksuInstalled ? "Installed" : "Needs install"));
        long settleMillis = M3qRootEngine.bootSettleRemainingMillis();
        String bootMark;
        String bootValue;
        if (engine.hasAttemptedThisBoot()) {
            bootMark = "○";
            bootValue = "Already ran — reboot to retry";
        } else if (settleMillis > 0 && engine.isBypassBootSettle()) {
            bootMark = "○";
            bootValue = getString(R.string.boot_bypassed);
        } else if (settleMillis > 0) {
            bootMark = "○";
            bootValue = "Ready in ~" + ((settleMillis + 999) / 1000) + "s";
        } else {
            bootMark = "✓";
            bootValue = "Ready";
        }
        checkBoot.setText(getString(R.string.check_format,
                bootMark, "This boot", bootValue));
    }

    private void finishRun(M3qRootEngine.RootState state) {
        if (state.terminationUnconfirmed()) {
            finishUnconfirmedRun();
            return;
        }
        running.set(false);
        hideProgress();
        ui.post(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusRefresh.setEnabled(true);
            copyInfo.setEnabled(true);
            if (state.ready()) {
                setStatus("Working <LKM>", STATUS_SUCCESS);
                setStatusDetail("[Temp root mode] · removed on reboot");
                setStatusVersion(getString(R.string.version_format, KSU_DRIVER_VERSION));
                run.setVisibility(View.GONE);
                openPackage(KSU_MANAGER_PACKAGE,
                        "KernelSU Manager is not installed.");
            } else if (state.bootstrap()) {
                run.setVisibility(View.VISIBLE);
                setStatus("Root ready", STATUS_WORKING);
                setStatusDetail("You can retry KernelSU activation.");
                run.setText(R.string.run_kernel_su_reactivate);
                run.setEnabled(true);
            } else {
                run.setVisibility(View.VISIBLE);
                setStatus("Temporary root activation failed", STATUS_WARNING);
                setStatusDetail("Reboot the device and check status again.");
                run.setEnabled(false);
            }
            reapplyModules.setEnabled(state.ready());
            restartZygote.setEnabled(state.ready());
            renderDashboard(state);
        });
    }

    private void finishUnconfirmedRun() {
        running.set(false);
        hideProgress();
        append("Process control lost; termination unproven. Do not retry before rebooting.");
        ui.post(() -> {
            run.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("Task status unknown", STATUS_WARNING);
            setStatusDetail("Do not run the same task again before rebooting.");
            run.setText(R.string.run_reboot_check);
            run.setEnabled(false);
            reapplyModules.setEnabled(false);
            restartZygote.setEnabled(false);
            statusRefresh.setEnabled(true);
            copyInfo.setEnabled(true);
        });
    }

    private void openPackage(String packageName, String missingMessage) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        append(missingMessage);
        // More useful: offer a one-tap install/update route instead of a dead end.
        String storeUrl = null;
        String webUrl = null;
        if (SHIZUKU_MANAGER_PACKAGE.equals(packageName)) {
            storeUrl = "market://details?id=" + packageName;
            webUrl = "https://play.google.com/store/apps/details?id=" + packageName;
        } else if (KSU_MANAGER_PACKAGE.equals(packageName)) {
            webUrl = KSU_RELEASES_URL;
        }
        if (storeUrl != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the web URL.
            }
        }
        if (webUrl != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the status message.
            }
        }
        setStatus("Cannot open manager app", STATUS_NEUTRAL);
        setStatusDetail(missingMessage);
    }

    private static String stripAnsi(String line) {
        return ANSI_ESCAPE.matcher(line).replaceAll("");
    }

    private File sessionLogFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, "session.log");
    }

    private void resetSessionLog() {
        worker.execute(() -> {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(sessionLogFile(), false), StandardCharsets.UTF_8))) {
                out.write("=== session " + Build.MODEL + " "
                        + System.currentTimeMillis());
                out.newLine();
            } catch (IOException ignored) {
            }
        });
    }

    private void writeSessionLine(String line) {
        worker.execute(() -> {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(sessionLogFile(), true), StandardCharsets.UTF_8))) {
                out.write(line);
                out.newLine();
            } catch (IOException ignored) {
            }
        });
    }

    private void copySessionLog() {
        File file = sessionLogFile();
        if (!file.isFile() || file.length() == 0) {
            append("No session log yet.");
            return;
        }
        try {
            String text = LogRedactor.redact(readLogTail(file));
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                append("Clipboard is not available on this device.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.share_subject), text));
            append(getString(R.string.log_copied));
        } catch (IOException error) {
            append("Log read failed: " + error.getMessage());
        }
    }

    private void copyDeviceInfo() {
        PackageInfo managerInfo = kernelSuManagerInfo();
        String info = "Model: " + Build.MODEL + "\n"
                + "Kernel: " + System.getProperty("os.version", "unknown") + "\n"
                + "Firmware: " + Build.FINGERPRINT + "\n"
                + "Manager: " + (managerInfo == null ? "not installed"
                        : formatManagerVersion(managerInfo)) + "\n"
                + "SELinux: " + selinuxStatus() + "\n"
                + "Status: " + status.getText() + " / " + statusDetail.getText();
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            append("Clipboard is not available on this device.");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.share_subject), info));
        append(getString(R.string.device_info_copied));
    }

    private void shareLastLog() {
        File session = sessionLogFile();
        File file = (session.isFile() && session.length() > 0)
                ? session : engine.lastRootLog();
        if (!file.isFile()) {
            append("No run log to share yet.");
            setStatus("No diagnostics report", STATUS_NEUTRAL);
            setStatusDetail("Run temporary root once, then create a report.");
            return;
        }
        try {
            String text = LogRedactor.redact(readLogTail(file));
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                    .putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(share, getString(R.string.share_chooser)));
        } catch (IOException error) {
            append("Log read failed: " + error.getMessage());
        }
    }

    private String readLogTail(File file) throws IOException {
        final int limit = 64 * 1024;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long skipped = Math.max(0, input.length() - limit);
            input.seek(skipped);
            byte[] bytes = new byte[(int) Math.min(limit, input.length())];
            input.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (skipped == 0) return text;
            return "[Earlier part and truncated first line omitted]\n"
                    + LogRedactor.dropPartialFirstLine(text);
        }
    }

    private void setStatus(String text, int semanticColor) {
        ui.post(() -> {
            int color = resolveStatusColor(semanticColor);
            status.setText(text);
            status.setTextColor(color);
            statusCard.setStrokeColor(color);
        });
    }

    private int resolveStatusColor(int semanticColor) {
        if (semanticColor == STATUS_SUCCESS) return getColor(R.color.m3q_success);
        if (semanticColor == STATUS_WORKING) return getColor(R.color.m3q_warning);
        if (semanticColor == STATUS_WARNING) return getColor(R.color.m3q_error);
        return getColor(R.color.m3q_neutral);
    }

    private void setStatusDetail(String text) {
        ui.post(() -> statusDetail.setText(text));
    }

    private void setStatusVersion(String text) {
        ui.post(() -> statusVersion.setText(text));
    }

    private void append(String line) {
        String clean = stripAnsi(line);
        writeSessionLine(clean);
        ui.post(() -> {
            log.append(clean + "\n");
            if (diagnosticsVisible) {
                scrollLogToBottom();
            }
        });
    }

    private void scrollLogToBottom() {
        log.post(() -> {
            if (log.getLayout() == null) return;
            int scroll = log.getLayout().getLineTop(log.getLineCount())
                    - log.getHeight();
            log.scrollTo(0, Math.max(0, scroll));
        });
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        worker.shutdown();
        super.onDestroy();
    }
}
