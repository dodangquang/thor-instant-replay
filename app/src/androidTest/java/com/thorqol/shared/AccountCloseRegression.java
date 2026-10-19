package com.thorqol.shared;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Button;
import java.util.HashMap;
import java.util.Map;

/** Uses the device's real lease; never creates licenses or changes server entitlements. */
public final class AccountCloseRegression {
    private final Instrumentation test;
    private final Context context;
    private final String main = "com.thor.instantreplay.MainActivity";
    public AccountCloseRegression(Instrumentation test) { this.test = test; context = test.getTargetContext(); }
    private void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private AccountActivity open() {
        AccountActivity screen = (AccountActivity)test.startActivitySync(new Intent(context, AccountActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        require(!screen.getIntent().hasExtra("return_to_app"), "Regression must cover the missing launch extra");
        long deadline = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            test.runOnMainSync(() -> ready[0] = !screen.pending);
            if (ready[0]) { test.waitForIdleSync(); return screen; }
            SystemClock.sleep(80);
        }
        throw new AssertionError("Account refresh did not finish");
    }
    private void close(AccountActivity screen, boolean controller) {
        test.runOnMainSync(() -> {
            if (controller) {
                long now = SystemClock.uptimeMillis();
                screen.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B,0));
                require(!screen.isFinishing(), "B must wait for release");
                screen.dispatchKeyEvent(new KeyEvent(now,now+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_B,0));
            } else {
                Button close = null;
                for (Button button : screen.buttons)
                    if (button.getText().toString().equals("Đóng") || button.getText().toString().equals("Close")) close = button;
                require(close != null && close.isEnabled(), "Close button missing or disabled");
                require(close.performClick(), "Close button did not handle click");
            }
        });
        test.waitForIdleSync();
        require(screen.isFinishing() || screen.isDestroyed(), "Account remained open after close");
    }
    public String run() throws Exception {
        require(!TrialAccess.enabled(context), "Full-edition account test required");
        require(new LicenseConfig(context).enforce, "Production enforcement must remain enabled");
        if (!LicenseAccess.canUse(context)) {
            AccountActivity refresh = open();
            test.runOnMainSync(refresh::finish); test.waitForIdleSync();
        }
        require(LicenseAccess.canUse(context), "Activate this device normally before testing the valid lease path");
        SharedPreferences secrets = context.getSharedPreferences("thorqol_secrets", 0);
        Map<String, ?> original = new HashMap<>(secrets.getAll());
        Map<String, ?> capture = new HashMap<>(context.getSharedPreferences("thor_replay_prefs", 0).getAll());
        int cases = 0;
        try {
            for (boolean controller : new boolean[]{false, true}) {
                AccountActivity account = open();
                require(LicenseAccess.canUse(context), "Real activation is no longer valid");
                Instrumentation.ActivityMonitor monitor = test.addMonitor(main, null, false);
                try {
                    close(account, controller);
                    Activity entered = test.waitForMonitorWithTimeout(monitor, 5000);
                    require(entered != null, "Activated Close did not enter the main app");
                    require(!entered.isFinishing(), "Main app immediately exited after activated Close");
                    test.runOnMainSync(entered::finish); test.waitForIdleSync(); cases++;
                } finally { test.removeMonitor(monitor); }
            }
            Activity existing = test.startActivitySync(new Intent().setClassName(context, main)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            final AccountActivity[] account = {null};
            Instrumentation.ActivityMonitor accountMonitor = test.addMonitor(AccountActivity.class.getName(), null, false);
            test.runOnMainSync(() -> LicenseAccess.open(existing));
            account[0] = (AccountActivity)test.waitForMonitorWithTimeout(accountMonitor, 5000);
            test.removeMonitor(accountMonitor);
            require(account[0] != null, "Account screen did not open from main app");
            long deadline = SystemClock.uptimeMillis() + 45000;
            while (account[0].pending && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(80);
            close(account[0], false);
            require(!existing.isFinishing() && !existing.isDestroyed(), "Existing main app was recreated or finished");
            test.runOnMainSync(existing::finish); test.waitForIdleSync(); cases++;

            // Keep a signed-in account, but remove its token and grant in memory-backed test setup.
            // No logout/revoke request is sent, and encrypted preferences are restored in finally.
            require(secrets.edit().remove("token").remove("grant").commit(), "Could not prepare inactive state");
            require(!LicenseAccess.canUse(context), "Missing activation must deny access");
            for (boolean controller : new boolean[]{false, true}) {
                AccountActivity inactive = open();
                Instrumentation.ActivityMonitor monitor = test.addMonitor(main, null, false);
                try {
                    close(inactive, controller);
                    require(test.waitForMonitorWithTimeout(monitor, 700) == null, "Inactive Close entered the app"); cases++;
                } finally { test.removeMonitor(monitor); }
            }
        } finally {
            SharedPreferences.Editor restore = secrets.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                require(entry.getValue() instanceof String, "Unexpected secure preference type");
                restore.putString(entry.getKey(), (String)entry.getValue());
            }
            require(restore.commit(), "Could not restore original encrypted account data");
        }
        require(LicenseAccess.canUse(context), "Original activation was not restored");
        require(capture.equals(context.getSharedPreferences("thor_replay_prefs", 0).getAll()), "Recording preferences changed");
        return "PASS " + cases + " account-close cases: activated button/B without extra, existing main retained, inactive button/B denied; activation and capture preferences preserved";
    }
}
