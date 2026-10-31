package com.giorgos.shadewidget;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Receives every change to the notification shade and asks the widget to redraw.
 * Redraws are batched (a burst of notifications produces one redraw) and run on a
 * background thread so icon loading never blocks anything.
 */
public class NotifListener extends NotificationListenerService {

    static volatile NotifListener sInstance;

    private static final long DEBOUNCE_MS = 200;
    private static final long TICK_MS = 60_000; // keeps "5m"-style timestamps fresh

    private static HandlerThread sThread;
    private static Handler sWorker;
    private static Context sAppContext;

    private static final Runnable RENDER = new Runnable() {
        @Override public void run() {
            if (sAppContext != null) WidgetRenderer.render(sAppContext);
        }
    };

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            Context ctx = sAppContext;
            if (ctx == null || sInstance == null) return;
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm == null || pm.isInteractive()) WidgetRenderer.render(ctx);
            worker().postDelayed(this, TICK_MS);
        }
    };

    static synchronized Handler worker() {
        if (sWorker == null) {
            sThread = new HandlerThread("shade-widget");
            sThread.start();
            sWorker = new Handler(sThread.getLooper());
        }
        return sWorker;
    }

    /** Schedule a widget redraw. Safe to call from anywhere. */
    static void requestRender(Context ctx, boolean immediate) {
        sAppContext = ctx.getApplicationContext();
        Handler h = worker();
        h.removeCallbacks(RENDER);
        h.postDelayed(RENDER, immediate ? 0 : DEBOUNCE_MS);
    }

    /** Ask the system to reconnect us if access is granted but we are not running. */
    static void ensureConnected(Context ctx) {
        if (sInstance == null && WidgetRenderer.hasAccess(ctx)) {
            try {
                requestRebind(new ComponentName(ctx, NotifListener.class));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
        requestRender(this, true);
        Handler h = worker();
        h.removeCallbacks(TICK);
        h.postDelayed(TICK, TICK_MS);
    }

    @Override
    public void onListenerDisconnected() {
        if (sInstance == this) sInstance = null;
        worker().removeCallbacks(TICK);
        requestRender(this, true);
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        requestRender(this, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        requestRender(this, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        requestRender(this, false);
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        requestRender(this, false);
    }
}
