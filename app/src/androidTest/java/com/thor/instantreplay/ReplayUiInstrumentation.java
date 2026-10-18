package com.thor.instantreplay;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Device regression exercises real input dispatch, locale and original settings. */
public final class ReplayUiInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void runOnMainSync(Runnable runner) {
        AtomicReference<Throwable> problem = new AtomicReference<>();
        super.runOnMainSync(() -> { try { runner.run(); } catch (Throwable error) { problem.set(error); } });
        if (problem.get() != null) throw new RuntimeException(problem.get());
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        Context ctx = getTargetContext();
        Map<String, ?> before = new HashMap<>(ctx.getSharedPreferences("thor_replay_prefs", 0).getAll());
        String originalLanguage = AppLanguage.get(ctx);
        try {
            String expectedPrefs = arguments.getString("capturePrefsSha256");
            if (expectedPrefs != null) require(expectedPrefs.equals(preferencesHash(before)), "Capture preferences changed during APK upgrade");
            for (String lang : new String[]{"vi", "en"}) {
                AppLanguage.set(ctx, lang);
                activity = startActivitySync(new Intent().setClassName(ctx, "com.thor.instantreplay.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                final Activity screen = activity;
                waitForIdleSync();
                final String expected = "en".equals(lang) ? "CAPTURE" : "GHI HÌNH";
                runOnMainSync(() -> {
                    TextView tab = screen.findViewById(ctx.getResources().getIdentifier("tabCapture", "id", ctx.getPackageName()));
                    require(expected.equals(tab.getText().toString()), "Locale did not apply: " + lang);
                    require(screen.getResources().getConfiguration().orientation == 2, "Landscape required");
                });
                if ("en".equals(lang)) checkController(screen);
                runOnMainSync(screen::finish);
                waitForIdleSync();
                activity = null;
            }
            require(before.equals(ctx.getSharedPreferences("thor_replay_prefs", 0).getAll()), "Replay settings changed during UI migration test");
            result.putString("stream", "PASS: VI/EN resources, landscape, A release gate, stick initial delay/repeat, original replay preferences unchanged\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            AppLanguage.set(ctx, originalLanguage);
            if (activity != null) { Activity screen = activity; runOnMainSync(screen::finish); }
        }
    }

    private String preferencesHash(Map<String, ?> preferences) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, ?> entry : new TreeMap<>(preferences).entrySet()) {
            Object value = entry.getValue();
            canonical.append(entry.getKey()).append('=').append(value.getClass().getName())
                    .append('=').append(value.toString()).append('\n');
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for(byte b : hash) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }

    private void checkController(Activity screen) {
        AtomicInteger clicks = new AtomicInteger();
        LinearLayout row = new LinearLayout(screen);
        Button[] buttons = new Button[8];
        final ViewGroup content = screen.findViewById(android.R.id.content);
        runOnMainSync(() -> {
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i=0;i<buttons.length;i++) {
                Button button = new Button(screen); buttons[i] = button;
                button.setText("QA " + i); button.setFocusable(true); button.setFocusableInTouchMode(true); button.setId(View.generateViewId());
                row.addView(button, new LinearLayout.LayoutParams(130, 90));
            }
            for (int i=0;i<buttons.length-1;i++) buttons[i].setNextFocusRightId(buttons[i+1].getId());
            buttons[0].setOnClickListener(v -> clicks.incrementAndGet());
            content.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,100));
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            require(buttons[0].requestFocus(), "Test button could not receive focus");
            require(screen.getCurrentFocus()==buttons[0],"QA focus fixture was not ready");
            long now = SystemClock.uptimeMillis();
            screen.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A,0));
            for(int repeat=1;repeat<60;repeat++) screen.dispatchKeyEvent(new KeyEvent(now,now+repeat,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A,repeat));
            require(clicks.get()==0,"A activated before release");
            screen.dispatchKeyEvent(new KeyEvent(now,now+80,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_A,0));
            screen.dispatchKeyEvent(new KeyEvent(now,now+81,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_A,0));
            require(clicks.get()==1,"A hold/orphan release caused duplicate activation: " + clicks.get());
            buttons[0].requestFocus();
            for(int i=0;i<100;i++) stick(screen, 1f);
            require(screen.getCurrentFocus()==buttons[1],"Analog event burst skipped multiple items");
        });
        SystemClock.sleep(180);
        runOnMainSync(() -> require(screen.getCurrentFocus()==buttons[1],"Analog repeated before initial hold delay"));
        SystemClock.sleep(220);
        runOnMainSync(() -> {
            require(screen.getCurrentFocus()==buttons[2],"Held analog did not repeat once after delay");
            stick(screen,0f);
        });
        SystemClock.sleep(200);
        runOnMainSync(() -> {
            require(screen.getCurrentFocus()==buttons[2],"Centered analog kept moving");
            content.removeView(row);
        });
    }
    private void stick(Activity screen, float x) {
        MotionEvent.PointerProperties props=new MotionEvent.PointerProperties(); props.id=0;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords(); coords.setAxisValue(MotionEvent.AXIS_X,x);
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{props},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_JOYSTICK,0);
        screen.dispatchGenericMotionEvent(event); event.recycle();
    }
}
