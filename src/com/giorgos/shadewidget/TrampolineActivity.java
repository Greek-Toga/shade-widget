package com.giorgos.shadewidget;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Every tap on the widget lands here first. The activity has no window and finishes at once.
 * Launching through an activity matters: Android only lets an app start another app's screen
 * while the app itself is in the foreground, and this activity is what puts it there.
 */
public class TrampolineActivity extends Activity {

    private static final String TAG = "ShadeWidget";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } catch (RuntimeException e) {
            Log.w(TAG, "tap failed", e);
        }
        finish();
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
    }

    private void handle(Intent in) {
        if (in == null) return;
        String op = in.getStringExtra(WidgetRenderer.EXTRA_OP);
        if (op == null) return;
        switch (op) {
            case WidgetRenderer.OP_TOGGLE: {
                String group = in.getStringExtra(WidgetRenderer.EXTRA_GROUP);
                if (group != null) {
                    WidgetRenderer.toggleExpanded(this, group);
                    NotifListener.requestRender(this, true);
                }
                break;
            }
            case WidgetRenderer.OP_APP: {
                String key = in.getStringExtra(WidgetRenderer.EXTRA_KEY);
                StatusBarNotification sbn = find(key);
                launchApp(in.getStringExtra(WidgetRenderer.EXTRA_PKG),
                        sbn != null ? sbn.getUser() : null);
                break;
            }
            case WidgetRenderer.OP_DISMISS: {
                dismiss(in.getStringExtra(WidgetRenderer.EXTRA_KEY));
                NotifListener.requestRender(this, true);
                break;
            }
            case WidgetRenderer.OP_OPEN: {
                String key = in.getStringExtra(WidgetRenderer.EXTRA_KEY);
                String pkg = in.getStringExtra(WidgetRenderer.EXTRA_PKG);
                StatusBarNotification sbn = find(key);
                if (sbn == null) {
                    // Notification is gone (already handled elsewhere): open its app instead.
                    launchApp(pkg, null);
                    NotifListener.requestRender(this, true);
                    break;
                }
                openNotification(sbn);
                break;
            }
        }
    }

    private StatusBarNotification find(String key) {
        NotifListener l = NotifListener.sInstance;
        if (l == null || key == null) return null;
        try {
            StatusBarNotification[] arr = l.getActiveNotifications(new String[]{key});
            if (arr != null) {
                for (StatusBarNotification s : arr) if (s != null && key.equals(s.getKey())) return s;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "lookup failed", e);
        }
        return null;
    }

    /** Do exactly what tapping the notification in the shade does. */
    private void openNotification(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        PendingIntent pi = n.contentIntent;
        boolean launched = false;
        if (pi != null) {
            try {
                ActivityOptions opts = ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 36) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                } else {
                    opts.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                pi.send(this, 0, null, null, null, null, opts.toBundle());
                launched = true;
            } catch (PendingIntent.CanceledException | RuntimeException e) {
                Log.w(TAG, "contentIntent failed", e);
            }
        }
        if (!launched) launchApp(sbn.getPackageName(), sbn.getUser());

        // Clear it the same way the shade does: only if the app marked it auto-cancel.
        boolean autoCancel = (n.flags & Notification.FLAG_AUTO_CANCEL) != 0;
        boolean sticky = (n.flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
        if (autoCancel && !sticky) {
            NotifListener l = NotifListener.sInstance;
            if (l != null) {
                try {
                    l.cancelNotification(sbn.getKey());
                } catch (RuntimeException e) {
                    Log.w(TAG, "cancel failed", e);
                }
            }
        }
        NotifListener.requestRender(this, false);
    }

    /**
     * Delete one notification, in the shade too. If it was the last notification left in its
     * group, also delete the group's summary so the whole group goes away, as it does in the shade.
     */
    private void dismiss(String key) {
        NotifListener l = NotifListener.sInstance;
        if (l == null || key == null) return;
        StatusBarNotification target = find(key);
        if (target == null) return;
        if (!WidgetRenderer.isDismissEnabled(this) || !target.isClearable()) return;
        try {
            l.cancelNotification(key);
            if (!target.isGroup()) return;
            String gk = target.getGroupKey();
            java.util.List<String> summaries = new java.util.ArrayList<>();
            int remaining = 0;
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification s : all) {
                    if (s == null || !gk.equals(s.getGroupKey()) || key.equals(s.getKey())) continue;
                    if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                        summaries.add(s.getKey());
                    } else {
                        remaining++;
                    }
                }
            }
            if (remaining == 0) {
                for (String sk : summaries) l.cancelNotification(sk);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "dismiss failed", e);
        }
    }

    private void launchApp(String pkg, UserHandle user) {
        if (pkg == null) return;
        if (user != null && !user.equals(android.os.Process.myUserHandle())) {
            // Work-profile app.
            try {
                LauncherApps la = getSystemService(LauncherApps.class);
                java.util.List<android.content.pm.LauncherActivityInfo> acts =
                        la.getActivityList(pkg, user);
                if (acts != null && !acts.isEmpty()) {
                    la.startMainActivity(acts.get(0).getComponentName(), user, null, null);
                    return;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "profile launch failed", e);
            }
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (RuntimeException e) {
                Log.w(TAG, "launch failed", e);
            }
        }
    }
}
