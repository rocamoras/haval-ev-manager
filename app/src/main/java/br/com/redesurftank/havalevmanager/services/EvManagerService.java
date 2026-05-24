package br.com.redesurftank.havalevmanager.services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService;
import com.beantechs.intelligentvehiclecontrol.sdk.IListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import br.com.redesurftank.havalevmanager.App;
import br.com.redesurftank.havalevmanager.EvStateHolder;
import br.com.redesurftank.havalevmanager.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalevmanager.utils.IPTablesUtils;
import br.com.redesurftank.havalevmanager.utils.TelnetClientWrapper;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

@SuppressLint("PrivateApi")
public class EvManagerService extends Service implements Shizuku.OnBinderDeadListener {

    private static final String TAG = "EvManagerService";

    private static final String CHANNEL_ID         = "EvManagerChannel";
    private static final int    NOTIFICATION_ID     = 1;
    private static final String PREFS_NAME          = "ev_manager_prefs";
    private static final String KEY_SHIZUKU_LIB     = "shizuku_lib_location";
    private static final String KEY_AUTO_ENABLED    = "auto_enabled";
    private static final String KEY_LAST_SOC_TARGET = "last_soc_target";
    private static final int    DEFAULT_SOC_TARGET  = 80;   // start in charging phase

    private static final String PROP_POWER_MODEL_CONFIG       = "car.ev_setting.power_model_config";
    private static final String PROP_CHARGE_SOC_TARGET_CONFIG = "car.ev_setting.charge_soc_target_config";
    private static final String PROP_POWER_RESERVE_CONFIG     = "car.ev_setting.power_reserve_config";
    private static final String PROP_BATTERY_CURRENT          = "car.ev_info.battery_charge_percentage";

    private static final String[] ALL_PROPS = {
        PROP_POWER_MODEL_CONFIG,
        PROP_CHARGE_SOC_TARGET_CONFIG,
        PROP_POWER_RESERVE_CONFIG,
        PROP_BATTERY_CURRENT
    };

    private static Method getServiceMethod;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getServiceMethod = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, "Failed to get android.os.ServiceManager.getService", e);
        }
    }

    private static IBinder getServiceBinder(String serviceName) {
        try {
            return (IBinder) Objects.requireNonNull(getServiceMethod.invoke(null, serviceName));
        } catch (IllegalAccessException | InvocationTargetException | NullPointerException e) {
            throw new RuntimeException("Failed to get system service: " + serviceName, e);
        }
    }

    private HandlerThread     handlerThread;
    private Handler           backgroundHandler;
    private final Handler     mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private boolean isShizukuInitialized = false;
    private boolean isServiceRunning     = false;

    private IIntelligentVehicleControlService controlService;
    private final Map<String, String> dataCache = new HashMap<>();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            backgroundHandler.post(EvManagerService.this::pushState);
        }
    };

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        prefs = App.getDeviceProtectedContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        handlerThread = new HandlerThread("EvManagerThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, skipping start.");
            return START_STICKY;
        }

        try {
            isServiceRunning = true;
            Log.w(TAG, "Service started");

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Haval EV Manager")
                    .setContentText("Monitorando configurações EV")
                    .setSmallIcon(android.R.drawable.ic_notification_overlay)
                    .build();
            startForeground(NOTIFICATION_ID, notification);

            // Restore persisted autoEnabled to StateHolder immediately on start
            boolean savedAutoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false);
            mainHandler.post(() -> EvStateHolder.INSTANCE.setAutoEnabled(savedAutoEnabled));

            boolean needsBootstrap = true;
            try {
                var selfInfo = getApplicationContext().getPackageManager()
                        .getApplicationInfo(getApplicationContext().getPackageName(), 0);
                if (selfInfo.uid > 10999) {
                    Log.w(TAG, "UID > 10999, skipping Shizuku bootstrap, waiting for existing binder...");
                    needsBootstrap = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get application info: " + e.getMessage(), e);
            }

            final String cachedLibLocation = prefs.getString(KEY_SHIZUKU_LIB, "");

            final Runnable timeoutRunnable = () -> {
                if (!isShizukuInitialized) {
                    Log.w(TAG, "Timeout waiting for Shizuku binder, restarting...");
                    restart();
                }
            };

            if (!needsBootstrap) {
                Shizuku.addBinderReceivedListenerSticky(this::onShizukuBinderReceived);
                backgroundHandler.postDelayed(timeoutRunnable, 10000);
            } else {
                backgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            TelnetClientWrapper telnetClient = new TelnetClientWrapper();
                            telnetClient.connect("127.0.0.1", 23);
                            String filePath = cachedLibLocation;
                            if (filePath.isEmpty()) {
                                filePath = telnetClient.executeCommand("find /data/app -name libshizuku.so");
                                if (filePath.isEmpty()) throw new RuntimeException("libshizuku.so not found");
                                prefs.edit().putString(KEY_SHIZUKU_LIB, filePath).apply();
                                Log.w(TAG, "libshizuku.so found at: " + filePath);
                            }

                            String result = telnetClient.executeCommand(filePath);
                            if (Pattern.compile("killed \\d+ \\(shizuku_server\\)").matcher(result).find()) {
                                Log.w(TAG, "Old Shizuku process killed, waiting 5s...");
                                Thread.sleep(5000);
                            }
                            telnetClient.disconnect();

                            Shizuku.addBinderReceivedListenerSticky(EvManagerService.this::onShizukuBinderReceived);
                            backgroundHandler.postDelayed(timeoutRunnable, 5000);
                        } catch (Exception e) {
                            Log.e(TAG, "Error bootstrapping Shizuku: " + e.getMessage(), e);
                            backgroundHandler.postDelayed(this, 1000);
                        }
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand: " + e.getMessage(), e);
            isServiceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (handlerThread != null) handlerThread.quitSafely();
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        try {
            if (controlService != null)
                controlService.unRegisterDataChangedListener(getPackageName(), vehicleDataListener);
        } catch (Exception ignored) {}
        mainHandler.post(() -> {
            EvStateHolder.INSTANCE.updateEvData(false, null, null, null, null);
            EvStateHolder.INSTANCE.commandCallback = null;
        });
        Log.w(TAG, "Service destroyed");
        super.onDestroy();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shizuku bootstrap
    // ──────────────────────────────────────────────────────────────────────────

    private synchronized void onShizukuBinderReceived() {
        if (!isServiceRunning) return;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Log.w(TAG, "Shizuku binder received");
        isShizukuInitialized = true;
        backgroundHandler.removeCallbacksAndMessages(null);
        checkAndInitialize();
    }

    private void checkAndInitialize() {
        if (!isShizukuInitialized) return;

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Requesting Shizuku permission...");
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == 0 && grantResult == PackageManager.PERMISSION_GRANTED) {
                    checkAndInitialize();
                } else {
                    Log.e(TAG, "Shizuku permission denied");
                }
            });
            Shizuku.requestPermission(0);
            return;
        }

        try {
            IPTablesUtils.unlockInputOutputAll();
        } catch (Exception e) {
            Log.e(TAG, "Error unlocking iptables: " + e.getMessage(), e);
        }

        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    IPTablesUtils.unlockInputOutputAll();
                    backgroundHandler.postDelayed(this, 15000);
                } catch (Exception e) {
                    backgroundHandler.postDelayed(this, 5000);
                }
            }
        });

        if (!connectToVehicleService()) {
            Log.e(TAG, "Failed to connect to vehicle service, restarting...");
            restart();
            return;
        }

        IntentFilter filter = new IntentFilter("com.beantechs.intelligentvehiclecontrol.INIT_COMPLETED");
        ContextCompat.registerReceiver(App.getContext(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isServiceRunning) {
                    Log.w(TAG, "intelligentvehiclecontrol restarted, restarting service...");
                    restart();
                } else {
                    checkAndInitialize();
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Vehicle service connection
    // ──────────────────────────────────────────────────────────────────────────

    private boolean connectToVehicleService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return false;
            }

            IBinder controlBinder = new ShizukuBinderWrapper(
                    getServiceBinder("com.beantechs.intelligentvehiclecontrol"));
            if (!controlBinder.pingBinder()) {
                Log.e(TAG, "IntelligentVehicleControlService binder not alive");
                return false;
            }
            controlService = IIntelligentVehicleControlService.Stub.asInterface(controlBinder);
            controlService.addListenerKey(getPackageName(), ALL_PROPS);
            controlService.registerDataChangedListener(getPackageName(), vehicleDataListener);

            String[] values = controlService.fetchDatas(ALL_PROPS);
            if (values != null) {
                for (int i = 0; i < ALL_PROPS.length && i < values.length; i++) {
                    if (values[i] != null) dataCache.put(ALL_PROPS[i], values[i]);
                }
            }

            Log.w(TAG, "Connected to vehicle service — powerModel=" + dataCache.get(PROP_POWER_MODEL_CONFIG)
                    + " socTarget=" + dataCache.get(PROP_CHARGE_SOC_TARGET_CONFIG)
                    + " powerReserve=" + dataCache.get(PROP_POWER_RESERVE_CONFIG)
                    + " battery=" + dataCache.get(PROP_BATTERY_CURRENT));

            EvStateHolder.INSTANCE.commandCallback = (key, value) ->
                    backgroundHandler.post(() -> {
                        try {
                            if ("auto_enabled".equals(key)) {
                                handleAutoToggle("true".equals(value));
                            } else {
                                sendEvCommand(key, value);
                                dataCache.put(key, value);
                                Log.w(TAG, "Command sent: " + key + " = " + value);
                                pushState();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error handling command: " + e.getMessage(), e);
                        }
                    });

            Shizuku.addBinderDeadListener(this);
            pushState();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to vehicle service: " + e.getMessage(), e);
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Auto toggle
    // ──────────────────────────────────────────────────────────────────────────

    /** Called on background thread. Persists the new value and, on enable, sets initial state. */
    private void handleAutoToggle(boolean enabled) throws Exception {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply();
        mainHandler.post(() -> EvStateHolder.INSTANCE.setAutoEnabled(enabled));
        Log.w(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));

        if (enabled) {
            // 1. Enforce base settings: HEV + priority reserve
            sendEvCommand(PROP_POWER_MODEL_CONFIG, "0", true);
            dataCache.put(PROP_POWER_MODEL_CONFIG, "0");
            sendEvCommand(PROP_POWER_RESERVE_CONFIG, "0", true);
            dataCache.put(PROP_POWER_RESERVE_CONFIG, "0");

            // 2. Pick initial SOC target from current battery level
            String batteryStr = dataCache.get(PROP_BATTERY_CURRENT);
            int initialSoc = DEFAULT_SOC_TARGET;
            if (batteryStr != null) {
                try {
                    int battery = Integer.parseInt(batteryStr.trim());
                    if (battery <= 20) {
                        initialSoc = 80;   // battery low → charge first
                    } else if (battery >= 75) {
                        initialSoc = 20;   // battery high → discharge first
                    } else {
                        // Middle range: resume saved phase, or default to charging
                        initialSoc = prefs.getInt(KEY_LAST_SOC_TARGET, DEFAULT_SOC_TARGET);
                    }
                } catch (NumberFormatException ignored) {}
            }
            prefs.edit().putInt(KEY_LAST_SOC_TARGET, initialSoc).apply();
            sendEvCommand(PROP_CHARGE_SOC_TARGET_CONFIG, String.valueOf(initialSoc), true);
            dataCache.put(PROP_CHARGE_SOC_TARGET_CONFIG, String.valueOf(initialSoc));
        }
        pushState();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State push & automation evaluation (always on background thread)
    // ──────────────────────────────────────────────────────────────────────────

    private void pushState() {
        String powerModel   = dataCache.get(PROP_POWER_MODEL_CONFIG);
        String socTarget    = dataCache.get(PROP_CHARGE_SOC_TARGET_CONFIG);
        String powerReserve = dataCache.get(PROP_POWER_RESERVE_CONFIG);
        String battery      = dataCache.get(PROP_BATTERY_CURRENT);
        boolean autoOn      = prefs != null && prefs.getBoolean(KEY_AUTO_ENABLED, false);

        mainHandler.post(() -> {
            EvStateHolder.INSTANCE.updateEvData(true, powerModel, socTarget, powerReserve, battery);
            EvStateHolder.INSTANCE.setAutoEnabled(autoOn);
        });
        evaluateAutomation();
    }

    /**
     * Checks the descida/subida state machine and fires SOC target changes when thresholds
     * are crossed. Must be called on the background thread.
     *
     * Phase tracking via {@code KEY_LAST_SOC_TARGET}:
     *   80 → charging phase  → wait battery ≥ 75 % → set SOC = 20, switch to discharging
     *   20 → discharging phase → wait battery ≤ 20 % → set SOC = 80, switch to charging
     */
    private void evaluateAutomation() {
        if (prefs == null || !prefs.getBoolean(KEY_AUTO_ENABLED, false)) return;

        // Always enforce base settings while auto is on
        try {
            if (!"0".equals(dataCache.get(PROP_POWER_MODEL_CONFIG))) {
                sendEvCommand(PROP_POWER_MODEL_CONFIG, "0", true);
                dataCache.put(PROP_POWER_MODEL_CONFIG, "0");
            }
            if (!"0".equals(dataCache.get(PROP_POWER_RESERVE_CONFIG))) {
                sendEvCommand(PROP_POWER_RESERVE_CONFIG, "0", true);
                dataCache.put(PROP_POWER_RESERVE_CONFIG, "0");
            }
        } catch (Exception e) {
            Log.e(TAG, "evaluateAutomation: error enforcing base settings", e);
        }

        // Battery SOC cycle
        String batteryStr = dataCache.get(PROP_BATTERY_CURRENT);
        if (batteryStr == null) return;
        int battery;
        try {
            battery = Integer.parseInt(batteryStr.trim());
        } catch (NumberFormatException e) {
            return;
        }

        int lastSocTarget = prefs.getInt(KEY_LAST_SOC_TARGET, DEFAULT_SOC_TARGET);

        try {
            if (lastSocTarget == 80 && battery >= 75) {
                // Charging phase complete → switch to discharging
                Log.w(TAG, "AUTO: battery " + battery + "% ≥ 75 → soc_target = 20");
                prefs.edit().putInt(KEY_LAST_SOC_TARGET, 20).apply();
                sendEvCommand(PROP_CHARGE_SOC_TARGET_CONFIG, "20", true);
                dataCache.put(PROP_CHARGE_SOC_TARGET_CONFIG, "20");
                pushState();   // re-push updated soc value to UI
            } else if (lastSocTarget == 20 && battery <= 20) {
                // Discharging phase complete → switch to charging
                Log.w(TAG, "AUTO: battery " + battery + "% ≤ 20 → soc_target = 80");
                prefs.edit().putInt(KEY_LAST_SOC_TARGET, 80).apply();
                sendEvCommand(PROP_CHARGE_SOC_TARGET_CONFIG, "80", true);
                dataCache.put(PROP_CHARGE_SOC_TARGET_CONFIG, "80");
                pushState();   // re-push updated soc value to UI
            }
        } catch (Exception e) {
            Log.e(TAG, "evaluateAutomation: error sending SOC command", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void sendEvCommand(String key, String value) throws Exception {
        sendEvCommand(key, value, false);
    }

    private void sendEvCommand(String key, String value, boolean isAuto) throws Exception {
        controlService.request("cmd.common.request.set", key, value);
        String label = keyLabel(key);
        String prefix = isAuto ? "[AUTO] " : "";
        final String logEntry = timeFormat.format(new Date()) + "  " + prefix + label + " → " + value;
        mainHandler.post(() -> EvStateHolder.INSTANCE.addLog(logEntry));
    }

    private static String keyLabel(String key) {
        switch (key) {
            case PROP_POWER_MODEL_CONFIG:       return "power_model_config";
            case PROP_CHARGE_SOC_TARGET_CONFIG: return "charge_soc_target_config";
            case PROP_POWER_RESERVE_CONFIG:     return "power_reserve_config";
            case PROP_BATTERY_CURRENT:          return "battery_charge_percentage";
            default:                            return key;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "EV Manager", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Restart
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onBinderDead() {
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        Log.w(TAG, "Shizuku binder dead, restarting...");
        restart();
    }

    private synchronized void restart() {
        isShizukuInitialized = false;
        isServiceRunning     = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        mainHandler.post(() -> EvStateHolder.INSTANCE.updateEvData(false, null, null, null, null));
        Log.w(TAG, "Scheduling service restart...");
        Intent broadcastIntent = new Intent(this, RestartReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pendingIntent);
        stopSelf();
    }
}
