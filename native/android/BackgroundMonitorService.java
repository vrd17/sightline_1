package com.sightline.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
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
    public static final String CH_ALERT   = "sightline-alerts";
    public static final int NOTIF_ID = 7788;

    public static volatile boolean RUNNING = false;
    public static volatile BackgroundMonitorService INSTANCE = null;

    // config (snapshot passed from JS)
    private double threshold = 35, safeBlink = 12, refCm = 0;
    private long approachHoldMs = 5000, blinkHoldMs = 15000;
    private boolean notif = true;

    // self-calibration: cm = K / px, where K is fixed from the hand-off distance
    private double K = 0;

    // runtime
    private boolean active = false;
    private ExecutorService analysisExec;
    private ProcessCameraProvider cameraProvider;
    private FaceDetector detector;
    private long lastAnalyze = 0, startedWall = 0, lastSample = 0;

    // breach episode state
    private Long tooCloseSince = null; private boolean distNotified = false;
    private Long blinkLowSince = null; private boolean blinkNotified = false;
    private boolean blinkClosed = false; private final ArrayDeque<Long> blinkTimes = new ArrayDeque<>();

    // summary buffers (read back by JS on return to foreground)
    private JSONArray breaches = new JSONArray();
    private JSONArray samples = new JSONArray();

    @Override public void onCreate() { super.onCreate(); INSTANCE = this; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent != null ? intent.getAction() : null;

        if ("STOP".equals(action)) { stopSelf(); return START_NOT_STICKY; }

        if (intent != null && intent.hasExtra("threshold")) {
            threshold     = intent.getDoubleExtra("threshold", 35);
            safeBlink     = intent.getDoubleExtra("safeBlink", 12);
            approachHoldMs = (long) (intent.getDoubleExtra("approachHold", 5) * 1000);
            blinkHoldMs    = (long) (intent.getDoubleExtra("blinkHold", 15) * 1000);
            refCm         = intent.getDoubleExtra("refCm", 0);
            notif         = intent.getBooleanExtra("notif", true);
        }

        createChannels();
        startForeground(NOTIF_ID, buildPersist("Monitoring ready"));
        RUNNING = true;
        if (startedWall == 0) startedWall = System.currentTimeMillis();

        if ("ACTIVATE".equals(action)) activate();
        else if ("STANDBY".equals(action)) standby();
        return START_STICKY;
    }

    /* ---------- camera control ---------- */
    public void activate() {
        if (active) return;
        active = true; K = 0;
        if (detector == null) {
            FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build();
            detector = FaceDetection.getClient(opts);
        }
        if (analysisExec == null) analysisExec = Executors.newSingleThreadExecutor();
        final ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
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
                updatePersist("Watching eye distance");
            } catch (Exception e) {
                updatePersist("Camera unavailable");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    public void standby() {
        active = false;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
            updatePersist("Paused (app in front)");
        });
    }

    /* ---------- frame analysis ---------- */
    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(@NonNull ImageProxy proxy) {
        long now = System.currentTimeMillis();
        if (!active || now - lastAnalyze < 600 || proxy.getImage() == null) { proxy.close(); return; }
        lastAnalyze = now;
        InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        Task<List<Face>> t = detector.process(img);
        t.addOnSuccessListener(faces -> handleFaces(faces, now));
        t.addOnCompleteListener(x -> proxy.close());
    }

    private void handleFaces(List<Face> faces, long now) {
        if (faces == null || faces.isEmpty()) { updatePersist("No face in view"); return; }
        Face face = faces.get(0);

        Double cm = null;
        FaceLandmark le = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark re = face.getLandmark(FaceLandmark.RIGHT_EYE);
        if (le != null && re != null) {
            double dx = le.getPosition().x - re.getPosition().x;
            double dy = le.getPosition().y - re.getPosition().y;
            double px = Math.hypot(dx, dy);
            if (px > 1) {
                if (K <= 0 && refCm > 0) K = refCm * px;   // self-calibrate from hand-off distance
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
        long elapsed = now - startedWall;
        Double rate = null;
        if (elapsed >= 12000) rate = elapsed >= 60000 ? (double) blinkTimes.size()
                : Math.round(blinkTimes.size() / (elapsed / 60000.0));

        if (cm != null) {
            if (cm < threshold) {
                if (tooCloseSince == null) tooCloseSince = now;
                if (now - tooCloseSince >= approachHoldMs && !distNotified) { distNotified = true; fireBreach("distance", now); }
            } else if (cm > threshold + 4) { tooCloseSince = null; distNotified = false; }
            updatePersist(Math.round(cm) + " cm from screen");
        }
        if (rate != null) {
            if (rate < safeBlink) {
                if (blinkLowSince == null) blinkLowSince = now;
                if (now - blinkLowSince >= blinkHoldMs && !blinkNotified) { blinkNotified = true; fireBreach("blink", now); }
            } else if (rate >= safeBlink + 2) { blinkLowSince = null; blinkNotified = false; }
        }

        if (now - lastSample >= 1000) {
            lastSample = now;
            try {
                JSONObject s = new JSONObject();
                s.put("ts", now);
                s.put("cm", cm == null ? JSONObject.NULL : Math.round(cm * 10) / 10.0);
                s.put("rate", rate == null ? JSONObject.NULL : rate);
                if (samples.length() < 1200) samples.put(s);
            } catch (Exception ignored) {}
        }
    }

    /* ---------- alerts ---------- */
    private void fireBreach(String type, long now) {
        try { JSONObject b = new JSONObject(); b.put("ts", now); b.put("type", type); if (breaches.length() < 200) breaches.put(b); } catch (Exception ignored) {}
        if (notif) {
            boolean dist = "distance".equals(type);
            String title = dist ? "Move away from the screen" : "Blink and look away";
            String text  = dist ? ("Closer than " + (int) threshold + " cm — give the eyes some distance.")
                                 : ("Blink rate dropped below " + (int) safeBlink + "/min — blink and rest.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                    .setContentTitle(title).setContentText(text)
                    .setSmallIcon(icon()).setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true)
                    .setContentIntent(openAppIntent()).build();
            if (nm != null) nm.notify((int) (now % 100000), n);
        }
        alarmSound();
        vibrate(type);
    }

    private void alarmSound() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            final Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), u);
            if (r != null) {
                r.play();
                new Handler(Looper.getMainLooper()).postDelayed(() -> { try { r.stop(); } catch (Exception ignored) {} }, 1600);
            }
        } catch (Exception ignored) {}
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
    public String getSummaryJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("breaches", breaches);
            o.put("samples", samples);
            o.put("started", startedWall);
            o.put("ended", System.currentTimeMillis());
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
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    private void updatePersist(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildPersist(text));
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
            NotificationChannel c = new NotificationChannel(CH_ALERT, "Sightline alerts", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Distance & blink breach alerts");
            c.enableVibration(true);
            nm.createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
        try { if (detector != null) detector.close(); } catch (Exception ignored) {}
        if (analysisExec != null) analysisExec.shutdown();
        RUNNING = false; active = false; INSTANCE = null;
        super.onDestroy();
    }
}
