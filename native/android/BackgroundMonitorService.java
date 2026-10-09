package com.sightline.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BackgroundMonitorService — the native eye-distance / blink monitor that keeps
 * working while another app (e.g. a video the child is watching) is fullscreen.
 *
 * It is a camera-type foreground service, so Android lets it hold the front
 * camera in the background. It uses CameraX (headless ImageAnalysis, no preview)
 * + ML Kit Face Detection (bundled, on-device, offline) to read eye distance and
 * blink, mirroring the web app's thresholds, and fires loud notifications on a
 * breach. Nothing is recorded; frames are analysed and discarded.
 *
 * Lifecycle (driven by BackgroundMonitorPlugin from the WebView):
 *   start()    — foreground service begins (while app is visible), STANDBY (camera closed)
 *   activate() — app went to background → open camera, start analysing
 *   standby()  — app came back → close camera (WebView takes over)
 *   stop()     — session ended → service stops
 */
public class BackgroundMonitorService extends LifecycleService {
    public static final String CH_PERSIST = "sightline-foreground";
    // New id: channel settings can't be changed once created, and the old
    // "sightline-alerts" channel may exist with lower importance on devices.
    public static final String CH_ALERT   = "sightline-alarm";
    public static final int NOTIF_ID = 7788;

    public static volatile boolean RUNNING = false;
    public static volatile BackgroundMonitorService INSTANCE = null;

    // config (snapshot passed from JS)
    private volatile double threshold = 35, safeBlink = 12, refCm = 0, calK = 0;
    private volatile long approachHoldMs = 5000, blinkHoldMs = 15000;
    private volatile boolean notif = true;

    // ML Kit's LEFT_EYE/RIGHT_EYE are eye centres; the web app measures outer eye
    // corners (MediaPipe 33/263). Centre span ≈ 0.70 × corner span.
    private static final double CENTER_TO_CORNER = 0.70;
    // No analysed frame within this window after activation → rebind the camera
    // (the WebView often still holds it for a moment after being backgrounded).
    private static final long WATCHDOG_MS = 3000;
    private static final int MAX_REBINDS = 6;

    // self-calibration: cm = K / px, where K is fixed from the hand-off distance
    private double K = 0;

    // runtime
    private volatile boolean active = false;
    private ExecutorService analysisExec;
    private ProcessCameraProvider cameraProvider;
    private FaceDetector detector;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long lastFrameAt = 0;
    private int rebinds = 0;
    private long lastAnalyze = 0, startedWall = 0, lastSample = 0, activatedAt = 0;
    private String lastPersistText = null; private long lastPersistAt = 0;

    // alerts drawn over other apps + loud alarm
    private static final long DIST_REPEAT_MS = 10000;   // re-sound while still too close
    private static final long NO_FACE_HIDE_MS = 2000;   // face gone this long → drop the cover
    private AlertOverlay overlay;
    private MediaPlayer alarmPlayer;
    private int savedAlarmVol = -1;
    private final Runnable stopAlarmRunnable = this::stopAlarm;
    private double lastCm = 0;
    private long lastDistAlarm = 0;
    private Long noFaceSince = null;

    // breach episode state
    private Long tooCloseSince = null; private boolean distNotified = false;
    private Long blinkLowSince = null; private boolean blinkNotified = false;
    private boolean blinkClosed = false; private final ArrayDeque<Long> blinkTimes = new ArrayDeque<>();

    // summary buffers (read back by JS on return to foreground)
    private JSONArray breaches = new JSONArray();
    private JSONArray samples = new JSONArray();

    @Override public void onCreate() { super.onCreate(); INSTANCE = this; overlay = new AlertOverlay(this); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent != null ? intent.getAction() : null;

        // startForeground must come first even when we are about to stop:
        // a service started with startForegroundService() that never calls it
        // crashes the app.
        createChannels();
        Notification persist = buildPersist(active ? "Watching eye distance" : "Monitoring ready");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, persist, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(NOTIF_ID, persist);
        } catch (Exception e) {
            // e.g. camera permission revoked, or a restart from the background
            stopNow(); return START_NOT_STICKY;
        }

        // Only an explicit start() from a live session arms the service. A null
        // intent is a system restart after the app was killed — there is no
        // session any more, so don't stay (and never open the camera).
        boolean fromSession = intent != null && intent.hasExtra("threshold");
        if ("STOP".equals(action) || (!fromSession && !RUNNING)) { stopNow(); return START_NOT_STICKY; }

        if (fromSession) {
            configure(intent.getDoubleExtra("threshold", 35), intent.getDoubleExtra("approachHold", 5),
                    intent.getDoubleExtra("safeBlink", 12), intent.getDoubleExtra("blinkHold", 15),
                    intent.getDoubleExtra("refCm", 0), intent.getDoubleExtra("calK", 0),
                    intent.getBooleanExtra("notif", true));
        }
        RUNNING = true;
        if (startedWall == 0) startedWall = System.currentTimeMillis();
        return START_NOT_STICKY;
    }

    /** User swiped Sightline away from recents: the session is gone with the
     *  WebView, so release the camera and end the service. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        stopNow();
        super.onTaskRemoved(rootIntent);
    }

    /** Close the camera, clear alerts and end the service immediately. */
    public void stopNow() {
        RUNNING = false;
        active = false;
        main.post(() -> {
            try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
            if (overlay != null) overlay.hideAll();
            stopAlarm();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
            } catch (Exception ignored) {}
            stopSelf();
        });
    }

    /** Update thresholds/calibration in place (no startForegroundService, which
     *  Android 12+ refuses once the app is already in the background). */
    public void configure(double threshold, double approachHoldSec, double safeBlink, double blinkHoldSec,
                          double refCm, double calK, boolean notif) {
        this.threshold = threshold;
        this.safeBlink = safeBlink;
        this.approachHoldMs = (long) (approachHoldSec * 1000);
        this.blinkHoldMs = (long) (blinkHoldSec * 1000);
        if (refCm > 0) this.refCm = refCm;
        if (calK > 0) this.calK = calK;
        this.notif = notif;
    }

    /* ---------- camera control ---------- */
    public void activate() {
        main.post(this::activateOnMain);
    }

    private void activateOnMain() {
        if (active) return;
        active = true; K = 0; rebinds = 0;
        activatedAt = System.currentTimeMillis();
        tooCloseSince = null; distNotified = false; noFaceSince = null;
        overlay.resetDismissed();
        blinkLowSince = null; blinkNotified = false; blinkClosed = false; blinkTimes.clear();
        lastPersistText = null;
        if (detector == null) {
            FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build();
            detector = FaceDetection.getClient(opts);
        }
        if (analysisExec == null) analysisExec = Executors.newSingleThreadExecutor();
        bindCamera();
    }

    private void bindCamera() {
        lastFrameAt = 0;
        final long bindAt = System.currentTimeMillis();
        final ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            if (!active) return;
            try {
                cameraProvider = fut.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(480, 640))
                        .build();
                analysis.setAnalyzer(analysisExec, this::analyze);
                CameraSelector sel = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, sel, analysis);
                updatePersist("Starting camera…", true);
            } catch (Exception e) {
                updatePersist("Camera unavailable — retrying", true);
            }
            // Watchdog: if no frame arrives (camera still held by the WebView, or
            // the bind failed), rebind a few times before giving up.
            main.postDelayed(() -> {
                if (!active || lastFrameAt >= bindAt) return;
                if (rebinds++ < MAX_REBINDS) bindCamera();
                else updatePersist("Camera unavailable in background", true);
            }, WATCHDOG_MS);
        }, ContextCompat.getMainExecutor(this));
    }

    public void standby() {
        active = false;
        main.post(() -> {
            try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
            overlay.hideAll();
            stopAlarm();
            updatePersist("Paused (app in front)", true);
        });
    }

    /* ---------- frame analysis ---------- */
    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(@NonNull ImageProxy proxy) {
        long now = System.currentTimeMillis();
        lastFrameAt = now;
        // ~7 fps: a blink lasts 100–300 ms, so slower sampling misses most of them
        if (!active || now - lastAnalyze < 140 || proxy.getImage() == null) { proxy.close(); return; }
        lastAnalyze = now;
        final double maxDim = Math.max(proxy.getWidth(), proxy.getHeight());
        InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        Task<List<Face>> t = detector.process(img);
        t.addOnSuccessListener(faces -> handleFaces(faces, now, maxDim));
        t.addOnCompleteListener(x -> proxy.close());
    }

    private void handleFaces(List<Face> faces, long now, double maxDim) {
        if (!active) return;
        if (faces == null || faces.isEmpty()) {
            if (noFaceSince == null) noFaceSince = now;
            if (now - noFaceSince >= NO_FACE_HIDE_MS && overlay.distanceShowing()) { overlay.hideDistance(); stopAlarm(); }
            updatePersist("No face in view", false);
            return;
        }
        noFaceSince = null;
        Face face = faces.get(0);

        Double cm = null;
        FaceLandmark le = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark re = face.getLandmark(FaceLandmark.RIGHT_EYE);
        if (le != null && re != null) {
            double dx = le.getPosition().x - re.getPosition().x;
            double dy = le.getPosition().y - re.getPosition().y;
            double px = Math.hypot(dx, dy);
            if (px > 1) {
                if (K <= 0) {
                    if (refCm > 0) K = refCm * px;                              // hand-off distance
                    else if (calK > 0) K = calK * CENTER_TO_CORNER * maxDim;    // saved calibration
                }
                if (K > 0) cm = K / px;
            }
        }

        Float lp = face.getLeftEyeOpenProbability(), rp = face.getRightEyeOpenProbability();
        if (lp != null && rp != null) {
            double avg = (lp + rp) / 2.0;
            if (!blinkClosed && avg < 0.4) { blinkClosed = true; blinkTimes.add(now); }
            else if (blinkClosed && avg > 0.6) { blinkClosed = false; }
        }
        while (!blinkTimes.isEmpty() && now - blinkTimes.peekFirst() > 60000) blinkTimes.pollFirst();
        // measure from activation: blinks seen before the hand-off belong to the WebView
        long elapsed = now - activatedAt;
        Double rate = null;
        if (elapsed >= 20000) rate = elapsed >= 60000 ? (double) blinkTimes.size()
                : Math.round(blinkTimes.size() / (elapsed / 60000.0));

        if (cm != null) {
            lastCm = cm;
            if (cm < threshold) {
                if (tooCloseSince == null) tooCloseSince = now;
                if (now - tooCloseSince >= approachHoldMs && !distNotified) { distNotified = true; fireBreach("distance", now); }
                else if (distNotified) {
                    overlay.showDistance(cm, threshold);   // keep the cover up, live cm
                    if (now - lastDistAlarm >= DIST_REPEAT_MS) { lastDistAlarm = now; alarmSound(4000); vibrate("distance"); }
                }
            } else if (cm > threshold + 4) {
                tooCloseSince = null; distNotified = false;
                overlay.resetDismissed();
                if (overlay.distanceShowing()) { overlay.hideDistance(); stopAlarm(); }
            }
        }
        if (rate != null) {
            if (rate < safeBlink) {
                if (blinkLowSince == null) blinkLowSince = now;
                if (now - blinkLowSince >= blinkHoldMs && !blinkNotified) { blinkNotified = true; fireBreach("blink", now); }
            } else if (rate >= safeBlink + 2) { blinkLowSince = null; blinkNotified = false; }
        }

        String dist = cm != null ? Math.round(cm) + " cm" : "distance not calibrated";
        String blink = rate != null ? Math.round(rate) + " blinks/min" : "measuring blinks…";
        updatePersist(dist + " · " + blink, false);

        if (now - lastSample >= 1000) {
            lastSample = now;
            try {
                JSONObject s = new JSONObject();
                s.put("ts", now);
                s.put("cm", cm == null ? JSONObject.NULL : Math.round(cm * 10) / 10.0);
                s.put("rate", rate == null ? JSONObject.NULL : rate);
                synchronized (this) { if (samples.length() < 1200) samples.put(s); }
            } catch (Exception ignored) {}
        }
    }

    /* ---------- alerts ---------- */
    private void fireBreach(String type, long now) {
        try { JSONObject b = new JSONObject(); b.put("ts", now); b.put("type", type); synchronized (this) { if (breaches.length() < 200) breaches.put(b); } } catch (Exception ignored) {}
        if (notif) {
            boolean dist = "distance".equals(type);
            String title = dist ? "Move away from the screen" : "Blink and look away";
            String text  = dist ? ("Closer than " + (int) threshold + " cm — give the eyes some distance.")
                                 : ("Blink rate dropped below " + (int) safeBlink + "/min — blink and rest.");
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                        .setContentTitle(title).setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setSmallIcon(icon()).setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true)
                        .setColor(dist ? 0xFFC8141E : 0xFFE67800).setColorized(true)
                        .setVibrate(new long[]{0, 400, 150, 400})
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setContentIntent(openAppIntent()).build();
                if (nm != null) nm.notify(dist ? NOTIF_ID + 1 : NOTIF_ID + 2, n);
            } catch (Exception ignored) {}
        }
        if ("distance".equals(type)) { overlay.showDistance(lastCm, threshold); lastDistAlarm = now; }
        else overlay.showBlink(safeBlink);
        alarmSound("distance".equals(type) ? 4000 : 2500);
        vibrate(type);
    }

    /** Alarm-stream sound at full alarm volume (restored afterwards), so it is
     *  heard over a video. Alarm volume is separate from media volume. */
    private void alarmSound(long durMs) {
        stopAlarm();
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) return;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try {
                    savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                } catch (Exception e) { savedAlarmVol = -1; }   // e.g. Do Not Disturb policy
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, u);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            alarmPlayer = mp;
            main.postDelayed(stopAlarmRunnable, durMs);
        } catch (Exception e) { stopAlarm(); }
    }

    private void stopAlarm() {
        main.removeCallbacks(stopAlarmRunnable);
        if (alarmPlayer != null) {
            try { alarmPlayer.stop(); } catch (Exception ignored) {}
            try { alarmPlayer.release(); } catch (Exception ignored) {}
            alarmPlayer = null;
        }
        if (savedAlarmVol >= 0) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0);
            } catch (Exception ignored) {}
            savedAlarmVol = -1;
        }
    }

    private void vibrate(String type) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pat = "blink".equals(type) ? new long[]{0, 200, 100, 200} : new long[]{0, 250, 120, 250, 120, 250};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pat, -1));
            else v.vibrate(pat, -1);
        } catch (Exception ignored) {}
    }

    /* ---------- summary for JS ---------- */
    /** Returns and clears what was gathered since the last call, so repeated
     *  background trips aren't merged into the session report twice. */
    public synchronized String getSummaryJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("breaches", breaches);
            o.put("samples", samples);
            o.put("started", startedWall);
            o.put("ended", System.currentTimeMillis());
            breaches = new JSONArray();
            samples = new JSONArray();
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* ---------- notifications ---------- */
    private int icon() { return getResources().getIdentifier("ic_stat_eye", "drawable", getPackageName()); }

    private PendingIntent openAppIntent() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildPersist(String text) {
        return new NotificationCompat.Builder(this, CH_PERSIST)
                .setContentTitle("Sightline is guarding your eyes")
                .setContentText(text)
                .setSmallIcon(icon())
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    /** Live status in the ongoing notification. Throttled (Android drops ALL of an
     *  app's notifications, alerts included, when it posts too fast). */
    private void updatePersist(String text, boolean force) {
        long now = System.currentTimeMillis();
        if (text.equals(lastPersistText)) return;
        if (!force && now - lastPersistAt < 1500) return;
        lastPersistText = text; lastPersistAt = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildPersist(text));
        } catch (Exception ignored) {}
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_PERSIST) == null) {
            NotificationChannel c = new NotificationChannel(CH_PERSIST, "Sightline session", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false); nm.createNotificationChannel(c);
        }
        if (nm.getNotificationChannel(CH_ALERT) == null) {
            NotificationChannel c = new NotificationChannel(CH_ALERT, "Sightline alarms", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Distance & blink breach alerts (pop up on screen)");
            c.enableVibration(true);
            c.setVibrationPattern(new long[]{0, 400, 150, 400});
            c.setSound(null, null);   // the service plays its own alarm-stream sound
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        stopAlarm();
        if (overlay != null) overlay.hideAll();
        main.removeCallbacksAndMessages(null);
        try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
        try { if (detector != null) detector.close(); } catch (Exception ignored) {}
        if (analysisExec != null) analysisExec.shutdown();
        RUNNING = false; active = false; INSTANCE = null;
        super.onDestroy();
    }
}
