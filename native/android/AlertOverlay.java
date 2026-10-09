package com.sightline.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AlertOverlay — draws Sightline's alerts on top of whatever app is in front
 * (e.g. a fullscreen video), because heads-up notifications are easy to miss or
 * get suppressed there. Needs "Display over other apps" (SYSTEM_ALERT_WINDOW);
 * without it every method is a silent no-op and the notification still fires.
 *
 *   showDistance(cm) — full-screen red cover; stays until hideDistance() (the
 *                      child moved back) or a tap
 *   showBlink()      — large banner at the top, auto-hides after a few seconds
 *
 * All methods must be called on the main thread.
 */
public class AlertOverlay {
    private static final long BLINK_SHOW_MS = 6000;

    private final Context ctx;
    private final WindowManager wm;
    private View distView, blinkView;
    private TextView distCm;
    private boolean dismissed = false;
    private final Runnable hideBlinkRunnable = this::hideBlink;

    public AlertOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    /* ---------- distance: full-screen cover ---------- */
    public void showDistance(double cm, double threshold) {
        if (dismissed || !canDraw(ctx) || wm == null) return;
        if (distView == null) {
            LinearLayout box = column(Color.argb(235, 200, 20, 30));
            box.setGravity(Gravity.CENTER);
            box.addView(text("⚠", 96, true));
            box.addView(text("Move back from the screen", 40, true));
            distCm = text("", 30, false);
            box.addView(distCm);
            box.addView(text("Keep at least " + (int) threshold + " cm away", 22, false));
            TextView hint = text("Tap to dismiss", 16, false);
            hint.setAlpha(0.75f);
            hint.setPadding(0, dp(28), 0, 0);
            box.addView(hint);
            box.setOnClickListener(v -> { dismissed = true; hideDistance(); });
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.MATCH_PARENT, true);
            try { wm.addView(box, lp); distView = box; } catch (Exception e) { return; }
        }
        if (distCm != null) distCm.setText(Math.round(cm) + " cm — too close");
    }

    public void hideDistance() {
        if (distView != null) { try { wm.removeView(distView); } catch (Exception ignored) {} }
        distView = null; distCm = null;
    }

    public boolean distanceShowing() { return distView != null; }

    /** A tap hides the cover for the rest of this too-close episode; call this
     *  once the child has moved back so the next episode shows it again. */
    public void resetDismissed() { dismissed = false; }

    /* ---------- blink: top banner ---------- */
    public void showBlink(double safeBlink) {
        if (!canDraw(ctx) || wm == null) return;
        if (blinkView == null) {
            LinearLayout box = column(Color.argb(240, 230, 120, 0));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(240, 230, 120, 0));
            bg.setCornerRadius(dp(24));
            box.setBackground(bg);
            box.setPadding(dp(24), dp(20), dp(24), dp(20));
            box.addView(text("Blink and look away", 32, true));
            box.addView(text("Blinking under " + (int) safeBlink + "/min — rest your eyes for a moment", 18, false));
            box.setOnClickListener(v -> hideBlink());
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT, false);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(32);
            try { wm.addView(box, lp); blinkView = box; } catch (Exception e) { return; }
        }
        blinkView.removeCallbacks(hideBlinkRunnable);
        blinkView.postDelayed(hideBlinkRunnable, BLINK_SHOW_MS);
    }

    public void hideBlink() {
        if (blinkView != null) {
            blinkView.removeCallbacks(hideBlinkRunnable);
            try { wm.removeView(blinkView); } catch (Exception ignored) {}
        }
        blinkView = null;
    }

    public void hideAll() { hideDistance(); hideBlink(); }

    /* ---------- helpers ---------- */
    private WindowManager.LayoutParams params(int height, boolean fullScreen) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (fullScreen) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                fullScreen ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT,
                height, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private LinearLayout column(int color) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setBackgroundColor(color);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        return l;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
