package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * "Family photos" screensaver: the same Pictures/Wallpapers photos as the home screen, full screen with a
 * slow zoom and crossfade, and a small clock that swaps corners with each photo so nothing burns in.
 * Any button on the remote wakes the TV.
 */
public class PhotoDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::nextPhoto;
    private final Runnable tick = this::updateClock;
    private ImageView front, back;
    private LinearLayout clockBox;
    private TextView clock, date;
    private String[] photos = new String[0];
    private int index, seq;
    private long intervalMs;
    private boolean clockLeft = true;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        intervalMs = getResources().getInteger(R.integer.screensaver_seconds) * 1000L;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photoLayer();
        front = photoLayer();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x00000000, 0x80000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clock = label(44, "sans-serif-thin");
        date = label(12, "sans-serif-medium");
        date.setLetterSpacing(0.2f);
        date.setAlpha(0.8f);
        clockBox.addView(clock);
        clockBox.addView(date);
        root.addView(clockBox, clockParams());
        setContentView(root);

        photos = Photos.list(this);
        index = photos.length > 0 ? new Random().nextInt(photos.length) - 1 : 0;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        updateClock();
        nextPhoto();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    private void nextPhoto() {
        handler.removeCallbacks(next);
        handler.postDelayed(next, intervalMs);
        if (photos.length == 0) return;
        index = (index + 1) % photos.length;
        String path = photos[index];
        int s = ++seq;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap bm = null;
            try {
                bm = Photos.load(this, path, sw, sh);
            } catch (Exception | OutOfMemoryError ignored) { }
            Bitmap shown = bm;
            handler.post(() -> { if (s == seq && shown != null) show(shown); });
        }).start();
    }

    private void show(Bitmap bm) {
        front.animate().cancel();
        front.setAlpha(1f);
        ImageView old = front;
        front = back;
        back = old;
        FrameLayout root = (FrameLayout) front.getParent();
        root.removeView(front);
        root.addView(front, 1, new FrameLayout.LayoutParams(-1, -1));
        front.setImageBitmap(bm);
        front.setAlpha(0f);
        // Slow zoom across the photo's whole time on screen.
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.animate().scaleX(1.08f).scaleY(1.08f).setDuration(intervalMs + 2000).start();
        front.animate().alpha(1f).setDuration(2000).withEndAction(() -> back.setImageDrawable(null)).start();
        // Clock to the other corner, faded across.
        clockBox.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            clockLeft = !clockLeft;
            clockBox.setLayoutParams(clockParams());
            clockBox.setGravity(clockLeft ? Gravity.START : Gravity.END);
            clockBox.animate().alpha(1f).setDuration(600).start();
        }).start();
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime())
                .toUpperCase(Locale.getDefault()));
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000 - now.get(Calendar.SECOND) * 1000L);
    }

    private FrameLayout.LayoutParams clockParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | (clockLeft ? Gravity.START : Gravity.END));
        p.setMargins(dp(48), 0, dp(48), dp(36));
        return p;
    }

    private ImageView photoLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView label(int sp, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL));
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(dp(12), 0, dp(1), 0xB3000000);
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
