package com.giorgos.shadewidget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the widget's RemoteViews from the current shade contents and pushes them to the launcher. */
final class WidgetRenderer {

    private static final String TAG = "ShadeWidget";

    static final String EXTRA_OP = "op";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_GROUP = "group";
    static final String OP_OPEN = "open";
    static final String OP_APP = "app";
    static final String OP_TOGGLE = "toggle";

    private static final String PREFS = "state";
    private static final String PREF_EXPANDED = "expanded";

    /** Children shown as one-line previews under a collapsed group header (same as the shade). */
    private static final int COLLAPSED_PREVIEW_LINES = 2;
    private static final int MAX_ROWS = 150;

    // Corner kinds for card pieces.
    private static final int LARGE = 0, SMALL = 1, FLAT = 2;
    private static final int[][] CARD_BG = {
            {R.drawable.card_ll, R.drawable.card_ls, R.drawable.card_lf},
            {R.drawable.card_sl, R.drawable.card_ss, R.drawable.card_sf},
            {R.drawable.card_fl, R.drawable.card_fs, R.drawable.card_ff},
    };

    private static final Map<String, CharSequence> sLabelCache = new HashMap<>();

    private WidgetRenderer() {}

    // ---------------------------------------------------------------- entry point

    static boolean hasAccess(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(
                new ComponentName(ctx, NotifListener.class));
    }

    static void render(Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, ShadeWidgetProvider.class));
        if (ids == null || ids.length == 0) return;

        if (!hasAccess(ctx)) {
            push(awm, ids, message(ctx, ctx.getString(R.string.setup_title),
                    ctx.getString(R.string.setup_body)));
            return;
        }
        NotifListener listener = NotifListener.sInstance;
        StatusBarNotification[] active = null;
        RankingMap ranking = null;
        if (listener != null) {
            try {
                active = listener.getActiveNotifications();
                ranking = listener.getCurrentRanking();
            } catch (RuntimeException e) {
                Log.w(TAG, "listener not ready", e);
                listener = null;
            }
        }
        if (listener == null) {
            NotifListener.ensureConnected(ctx);
            push(awm, ids, message(ctx, ctx.getString(R.string.connecting), ""));
            return;
        }

        List<ShadeModel.TopItem> items = ShadeModel.build(active, ranking);
        Set<String> expanded = pruneExpanded(ctx, items);

        // Try with pictures first; if the launcher rejects the size, fall back to lighter versions.
        int[][] attempts = {{1, MAX_ROWS}, {0, MAX_ROWS}, {0, 40}};
        for (int[] a : attempts) {
            try {
                RemoteViews rv = buildList(ctx, items, expanded, a[0] == 1, a[1]);
                awm.updateAppWidget(ids, rv);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "widget update failed, retrying lighter", e);
            }
        }
    }

    private static void push(AppWidgetManager awm, int[] ids, RemoteViews rv) {
        try {
            awm.updateAppWidget(ids, rv);
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed", e);
        }
    }

    private static RemoteViews message(Context ctx, String title, String body) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_message);
        rv.setTextViewText(R.id.message_title, title);
        rv.setTextViewText(R.id.message_body, body);
        rv.setViewVisibility(R.id.message_body, TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.message_root, PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return rv;
    }

    // ---------------------------------------------------------------- expand state

    static void toggleExpanded(Context ctx, String groupKey) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> s = new HashSet<>(p.getStringSet(PREF_EXPANDED, new HashSet<String>()));
        if (!s.remove(groupKey)) s.add(groupKey);
        p.edit().putStringSet(PREF_EXPANDED, s).apply();
    }

    private static Set<String> pruneExpanded(Context ctx, List<ShadeModel.TopItem> items) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = p.getStringSet(PREF_EXPANDED, new HashSet<String>());
        Set<String> live = new HashSet<>();
        for (ShadeModel.TopItem t : items) {
            if (t.isGroup() && saved.contains(t.groupKey)) live.add(t.groupKey);
        }
        if (live.size() != saved.size()) p.edit().putStringSet(PREF_EXPANDED, live).apply();
        return live;
    }

    // ---------------------------------------------------------------- list building

    private static final class Ctx {
        Context c;
        boolean bitmaps;
        long bitmapBytes;
        long now;
        int dp1;
        Set<Long> usedIds = new HashSet<>();
        RemoteViews.RemoteCollectionItems.Builder list;
        int rows;
        int maxRows;
        boolean firstPiece = true;

        int dp(int v) { return Math.round(v * dp1 / 100f); }

        long id(String s) {
            long id = s.hashCode();
            while (!usedIds.add(id)) id++;
            return id;
        }
    }

    private static RemoteViews buildList(Context context, List<ShadeModel.TopItem> items,
                                         Set<String> expanded, boolean bitmaps, int maxRows) {
        // Re-read icons each redraw so an app update that changes its icon shows up.
        synchronized (sIconCache) {
            sIconCache.clear();
            sNoIcon.clear();
        }
        Ctx x = new Ctx();
        x.c = context;
        x.bitmaps = bitmaps;
        x.now = System.currentTimeMillis();
        x.dp1 = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100,
                context.getResources().getDisplayMetrics()));
        x.maxRows = maxRows;
        x.list = new RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(3);

        int prevSection = -1;
        for (int i = 0; i < items.size() && x.rows < x.maxRows; i++) {
            ShadeModel.TopItem t = items.get(i);
            boolean firstInSection = t.section != prevSection;
            boolean lastInSection = i == items.size() - 1 || items.get(i + 1).section != t.section;
            int gap = x.firstPiece ? 0 : (firstInSection ? x.dp(14) : x.dp(2));
            int top = firstInSection ? LARGE : SMALL;
            int bottom = lastInSection ? LARGE : SMALL;
            boolean wasFirst = x.firstPiece;
            try {
                if (t.isGroup()) {
                    addGroup(x, t, expanded.contains(t.groupKey), gap, top, bottom);
                } else {
                    addSingle(x, t.single, gap, top, bottom);
                }
            } catch (RuntimeException ex) {
                // One app's malformed notification must not take the whole widget down.
                Log.w(TAG, "skipping notification", ex);
                x.firstPiece = wasFirst;
                continue;
            }
            prevSection = t.section;
        }

        RemoteViews root = new RemoteViews(context.getPackageName(), R.layout.widget_root);
        root.setRemoteAdapter(R.id.list, x.list.build());
        root.setEmptyView(R.id.list, R.id.empty);
        Intent tramp = new Intent(context, TrampolineActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        root.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(context, 1, tramp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        return root;
    }

    private static RemoteViews piece(Ctx x, int layout, int gap, int top, int bottom) {
        RemoteViews rv = new RemoteViews(x.c.getPackageName(), layout);
        rv.setViewPadding(R.id.item_root, 0, gap, 0, 0);
        rv.setInt(R.id.card, "setBackgroundResource", CARD_BG[top][bottom]);
        resetRow(x, rv, layout);
        x.firstPiece = false;
        return rv;
    }

    /**
     * The launcher reuses row views while scrolling and after groups expand or collapse. Anything
     * a row doesn't set explicitly keeps whatever the previous occupant left behind (a profile
     * photo, a hidden icon...). So every row starts by putting every view back to its default.
     */
    private static void resetRow(Ctx x, RemoteViews rv, int layout) {
        if (layout == R.layout.item_notif) {
            rv.setViewVisibility(R.id.icon_frame, View.VISIBLE);
            rv.setViewVisibility(R.id.icon_bg, View.VISIBLE);
            rv.setViewVisibility(R.id.small_icon, View.VISIBLE);
            rv.setViewVisibility(R.id.app_icon, View.GONE);
            rv.setViewVisibility(R.id.avatar, View.GONE);
            rv.setViewVisibility(R.id.large_icon, View.GONE);
            rv.setViewVisibility(R.id.progress, View.GONE);
            rv.setViewVisibility(R.id.chrono, View.GONE);
            rv.setChronometer(R.id.chrono, 0, null, false);
            rv.setViewVisibility(R.id.header_text, View.VISIBLE);
            rv.setViewVisibility(R.id.title, View.VISIBLE);
            rv.setViewVisibility(R.id.text, View.VISIBLE);
            // Colors resolved by the launcher at draw time, so they still follow light/dark mode.
            rv.setColor(R.id.icon_bg, "setColorFilter", R.color.icon_circle);
            rv.setColor(R.id.small_icon, "setColorFilter", R.color.on_icon_circle);
            rv.setViewPadding(R.id.card, x.dp(12), x.dp(12), x.dp(14), x.dp(12));
        } else if (layout == R.layout.item_group_header) {
            rv.setViewVisibility(R.id.icon_bg, View.VISIBLE);
            rv.setViewVisibility(R.id.small_icon, View.VISIBLE);
            rv.setViewVisibility(R.id.app_icon, View.GONE);
            rv.setViewVisibility(R.id.header_text, View.VISIBLE);
            // Colors resolved by the launcher at draw time, so they still follow light/dark mode.
            rv.setColor(R.id.icon_bg, "setColorFilter", R.color.icon_circle);
            rv.setColor(R.id.small_icon, "setColorFilter", R.color.on_icon_circle);
            rv.setViewPadding(R.id.card, x.dp(12), x.dp(12), x.dp(10), x.dp(6));
        } else if (layout == R.layout.item_child_line) {
            rv.setViewPadding(R.id.card, x.dp(60), x.dp(3), x.dp(14), x.dp(3));
        }
    }

    private static void add(Ctx x, String idKey, RemoteViews rv) {
        x.list.addItem(x.id(idKey), rv);
        x.rows++;
    }

    // ---------------------------------------------------------------- standalone notification

    private static void addSingle(Ctx x, ShadeModel.Entry e, int gap, int top, int bottom) {
        RemoteViews rv = piece(x, R.layout.item_notif, gap, top, bottom);
        fillNotification(x, rv, e, false);
        rv.setOnClickFillInIntent(R.id.card, openIntent(e));
        add(x, e.key, rv);
    }

    // ---------------------------------------------------------------- group

    private static void addGroup(Ctx x, ShadeModel.TopItem t, boolean isExpanded,
                                 int gap, int top, int bottom) {
        List<ShadeModel.Entry> kids = t.children;
        ShadeModel.Entry rep = t.summary != null ? t.summary : kids.get(0);
        Notification repN = rep.sbn.getNotification();

        // Header
        RemoteViews h = piece(x, R.layout.item_group_header, gap, top, FLAT);
        // The shade's group header shows the time of the newest notification in the group.
        // (Not the summary's time: many apps, e.g. Reddit and X, never update their summary.)
        long newest = 0;
        for (ShadeModel.Entry k : kids) newest = Math.max(newest, k.recency);
        String time = relTime(newest, x.now);

        if (t.bundleChannel != null) {
            h.setTextViewText(R.id.title, bundleLabel(t.bundleChannel));
            h.setImageViewResource(R.id.small_icon, bundleIcon(t.bundleChannel));
            h.setTextViewText(R.id.header_text, time);
            if (TextUtils.isEmpty(time)) h.setViewVisibility(R.id.header_text, View.GONE);
            // A bundle has no single app, so tapping the header expands it instead.
            h.setOnClickFillInIntent(R.id.card, toggleIntent(t.groupKey));
        } else {
            h.setTextViewText(R.id.title, appName(x.c, rep.sbn));
            setAppIcon(x, h, rep.sbn);
            CharSequence sub = repN.extras != null
                    ? repN.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) : null;
            CharSequence line = join(sub, time);
            h.setTextViewText(R.id.header_text, line);
            if (TextUtils.isEmpty(line)) h.setViewVisibility(R.id.header_text, View.GONE);
            h.setOnClickFillInIntent(R.id.card, appIntent(rep));
        }
        h.setTextViewText(R.id.count, String.valueOf(kids.size()));
        h.setImageViewResource(R.id.chevron, isExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        h.setOnClickFillInIntent(R.id.chevron_pill, toggleIntent(t.groupKey));
        add(x, t.groupKey + "#h", h);

        if (isExpanded) {
            for (int i = 0; i < kids.size() && x.rows < x.maxRows; i++) {
                ShadeModel.Entry k = kids.get(i);
                boolean last = i == kids.size() - 1;
                RemoteViews rv = piece(x, R.layout.item_notif, 0, FLAT, last ? bottom : FLAT);
                fillNotification(x, rv, k, true);
                rv.setViewPadding(R.id.card, x.dp(12), x.dp(8), x.dp(14), last ? x.dp(14) : x.dp(8));
                rv.setOnClickFillInIntent(R.id.card, openIntent(k));
                add(x, k.key + "#c", rv);
            }
        } else {
            int n = Math.min(COLLAPSED_PREVIEW_LINES, kids.size());
            for (int i = 0; i < n && x.rows < x.maxRows; i++) {
                ShadeModel.Entry k = kids.get(i);
                boolean last = i == n - 1;
                RemoteViews rv = piece(x, R.layout.item_child_line, 0, FLAT, last ? bottom : FLAT);
                Content c = content(k.sbn);
                rv.setTextViewText(R.id.line, oneLine(c.title, c.text));
                Notification kn = k.sbn.getNotification();
                rv.setTextViewText(R.id.time, showsTime(kn) ? relTime(kn.when, x.now) : "");
                rv.setViewPadding(R.id.card, x.dp(60), x.dp(3), x.dp(14), last ? x.dp(14) : x.dp(3));
                rv.setOnClickFillInIntent(R.id.card, openIntent(k));
                add(x, k.key + "#l", rv);
            }
        }
    }

    // ---------------------------------------------------------------- one notification's content

    private static void fillNotification(Ctx x, RemoteViews rv, ShadeModel.Entry e, boolean asChild) {
        StatusBarNotification sbn = e.sbn;
        Notification n = sbn.getNotification();
        Bundle ex = n.extras != null ? n.extras : new Bundle();
        Content c = content(sbn);
        boolean minimized = e.ranking != null
                && e.ranking.getImportance() == NotificationManager.IMPORTANCE_MIN;

        // Icon: conversation avatar if there is one, otherwise the app's small icon in a circle.
        boolean showedAvatar = false;
        if (!asChild && c.avatar != null && x.bitmaps) {
            Bitmap b = loadBitmap(x, c.avatar, x.dp(36), true);
            if (b != null) {
                rv.setImageViewBitmap(R.id.avatar, b);
                rv.setViewVisibility(R.id.avatar, View.VISIBLE);
                rv.setViewVisibility(R.id.icon_bg, View.GONE);
                rv.setViewVisibility(R.id.small_icon, View.GONE);
                showedAvatar = true;
            }
        }
        if (asChild) {
            rv.setViewVisibility(R.id.icon_frame, View.GONE);
            rv.setViewPadding(R.id.card, x.dp(60), x.dp(8), x.dp(14), x.dp(8));
        } else if (!showedAvatar) {
            setAppIcon(x, rv, sbn);
        }

        // Header line: "App • subtext • 5m" (children skip the app name, as in the shade).
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
        boolean chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.when != 0;
        String time = (!chrono && showsTime(n)) ? relTime(n.when, x.now) : null;
        CharSequence header = asChild ? join(sub, time) : join(join(appName(x.c, sbn), sub), time);
        if (minimized && !asChild) header = join(header, c.title);
        if (chrono) {
            header = TextUtils.isEmpty(header) ? header : header + " •";
            long base = SystemClock.elapsedRealtime() - (x.now - n.when);
            rv.setChronometer(R.id.chrono, base, null, true);
            rv.setChronometerCountDown(R.id.chrono, ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
            rv.setViewVisibility(R.id.chrono, View.VISIBLE);
        }
        rv.setTextViewText(R.id.header_text, header == null ? "" : header);
        rv.setViewVisibility(R.id.header_text, TextUtils.isEmpty(header) && !chrono ? View.GONE : View.VISIBLE);

        if (minimized && !asChild) {
            rv.setViewVisibility(R.id.title, View.GONE);
            rv.setViewVisibility(R.id.text, View.GONE);
            return;
        }

        rv.setTextViewText(R.id.title, c.title == null ? "" : c.title);
        rv.setViewVisibility(R.id.title, TextUtils.isEmpty(c.title) ? View.GONE : View.VISIBLE);
        rv.setTextViewText(R.id.text, c.text == null ? "" : c.text);
        rv.setViewVisibility(R.id.text, TextUtils.isEmpty(c.text) ? View.GONE : View.VISIBLE);

        // Progress bar (downloads, uploads, timers...).
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE);
        if (max > 0 || indeterminate) {
            rv.setProgressBar(R.id.progress, max, ex.getInt(Notification.EXTRA_PROGRESS, 0), indeterminate);
            rv.setViewVisibility(R.id.progress, View.VISIBLE);
        }

        // Large picture on the right (not for conversations, whose picture is the avatar).
        if (x.bitmaps && !c.conversation) {
            Icon large = n.getLargeIcon();
            if (large != null) {
                Bitmap b = loadBitmap(x, large, x.dp(40), false);
                if (b != null) {
                    rv.setImageViewBitmap(R.id.large_icon, b);
                    rv.setViewVisibility(R.id.large_icon, View.VISIBLE);
                }
            }
        }
    }

    /**
     * Show the app's own full-color launcher icon, as the shade does. Apps with no launcher icon
     * (Android System, System UI and similar) keep the notification's small icon in a circle.
     */
    private static void setAppIcon(Ctx x, RemoteViews rv, StatusBarNotification sbn) {
        Icon app = launcherIcon(x.c, sbn.getPackageName());
        if (app != null) {
            rv.setImageViewIcon(R.id.app_icon, app);
            rv.setViewVisibility(R.id.app_icon, View.VISIBLE);
            rv.setViewVisibility(R.id.icon_bg, View.GONE);
            rv.setViewVisibility(R.id.small_icon, View.GONE);
        } else {
            Notification n = sbn.getNotification();
            rv.setImageViewIcon(R.id.small_icon, n.getSmallIcon());
            applyIconColor(rv, n.color);
        }
    }

    private static final Map<String, Icon> sIconCache = new HashMap<>();
    private static final Set<String> sNoIcon = new HashSet<>();

    /**
     * The icon of the app's launcher entry, passed as a resource reference so the launcher draws
     * it itself (in its own shape) and no bitmap has to be copied into the widget.
     */
    static Icon launcherIcon(Context ctx, String pkg) {
        synchronized (sIconCache) {
            if (sNoIcon.contains(pkg)) return null;
            Icon cached = sIconCache.get(pkg);
            if (cached != null) return cached;
        }
        Icon icon = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                android.content.pm.ResolveInfo ri = pm.resolveActivity(launch, 0);
                int res = ri != null && ri.activityInfo != null ? ri.activityInfo.getIconResource() : 0;
                if (res == 0) res = pm.getApplicationInfo(pkg, 0).icon;
                if (res != 0) icon = Icon.createWithResource(pkg, res);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        synchronized (sIconCache) {
            if (icon != null) sIconCache.put(pkg, icon); else sNoIcon.add(pkg);
        }
        return icon;
    }

    private static void applyIconColor(RemoteViews rv, int color) {
        if (color == Notification.COLOR_DEFAULT || Color.alpha(color) == 0) return;
        int solid = color | 0xFF000000;
        rv.setInt(R.id.icon_bg, "setColorFilter", solid);
        double lum = Color.luminance(solid);
        rv.setInt(R.id.small_icon, "setColorFilter", lum > 0.5 ? 0xFF1B1B1F : 0xFFFFFFFF);
    }

    static final class Content {
        CharSequence title;
        CharSequence text;
        Icon avatar;
        boolean conversation;
    }

    static Content content(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras != null ? n.extras : new Bundle();
        Content c = new Content();
        String template = ex.getString(Notification.EXTRA_TEMPLATE);

        if (template != null && template.endsWith("$MessagingStyle")) {
            c.conversation = true;
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
            CharSequence convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            boolean group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || convTitle != null;
            CharSequence senderName = null;
            CharSequence body = null;
            Person sender = null;
            if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                Bundle last = (Bundle) msgs[msgs.length - 1];
                body = last.getCharSequence("text");
                sender = last.getParcelable("sender_person", Person.class);
                senderName = sender != null ? sender.getName() : last.getCharSequence("sender");
                if (TextUtils.isEmpty(body) && last.containsKey("uri")) body = "Photo";
                if (senderName == null) {
                    Person self = ex.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class);
                    senderName = self != null && self.getName() != null ? self.getName() : "You";
                }
            }
            if (body == null) body = ex.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence title = group && convTitle != null ? convTitle : ex.getCharSequence(Notification.EXTRA_TITLE);
            if (title == null) title = senderName;
            c.title = title;
            if (group && senderName != null && body != null) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(senderName.toString()).append(": ");
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length() - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(body.toString());
                c.text = sb;
            } else {
                c.text = body;
            }
            c.avatar = n.getLargeIcon();
            if (c.avatar == null && sender != null && !group) c.avatar = sender.getIcon();
            c.text = plain(c.text, true);
            c.title = plain(c.title, false);
            return c;
        }

        c.title = ex.getCharSequence(Notification.EXTRA_TITLE);
        c.text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(c.text)) {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) c.text = lines[0];
        }
        if (TextUtils.isEmpty(c.text)) c.text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(c.title) && TextUtils.isEmpty(c.text) && n.tickerText != null) {
            c.text = n.tickerText;
        }
        c.title = plain(c.title, false);
        c.text = plain(c.text, false);
        return c;
    }

    /** Drop app-supplied text styling (it can carry colors that clash with the theme). */
    private static CharSequence plain(CharSequence s, boolean keepBold) {
        if (s == null) return null;
        if (keepBold && s instanceof Spanned) return s;
        return s.toString();
    }

    private static CharSequence oneLine(CharSequence title, CharSequence text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(title)) {
            sb.append(title.toString());
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.isEmpty(text)) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(text.toString().replace('\n', ' '));
        }
        return sb;
    }

    // ---------------------------------------------------------------- click intents

    private static Intent openIntent(ShadeModel.Entry e) {
        return new Intent()
                .putExtra(EXTRA_OP, OP_OPEN)
                .putExtra(EXTRA_KEY, e.key)
                .putExtra(EXTRA_PKG, e.sbn.getPackageName());
    }

    private static Intent appIntent(ShadeModel.Entry e) {
        return new Intent()
                .putExtra(EXTRA_OP, OP_APP)
                .putExtra(EXTRA_KEY, e.key)
                .putExtra(EXTRA_PKG, e.sbn.getPackageName());
    }

    private static Intent toggleIntent(String groupKey) {
        return new Intent()
                .putExtra(EXTRA_OP, OP_TOGGLE)
                .putExtra(EXTRA_GROUP, groupKey);
    }

    // ---------------------------------------------------------------- helpers

    static boolean showsTime(Notification n) {
        return n.when != 0 && n.extras != null && n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN);
    }

    static String relTime(long when, long now) {
        if (when <= 0) return "";
        long d = now - when;
        boolean future = d < -60_000;
        long a = Math.abs(d);
        long m = a / 60_000, h = m / 60, days = h / 24;
        String s;
        if (m < 1) return "now";
        else if (m < 60) s = m + "m";
        else if (h < 24) s = h + "h";
        else if (days < 7) s = days + "d";
        else if (days < 365) s = (days / 7) + "w";
        else s = (days / 365) + "y";
        return future ? "in " + s : s;
    }

    private static CharSequence join(CharSequence a, CharSequence b) {
        if (TextUtils.isEmpty(a)) return TextUtils.isEmpty(b) ? "" : b;
        if (TextUtils.isEmpty(b)) return a;
        return a.toString() + " • " + b;
    }

    static CharSequence appName(Context ctx, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n.extras != null) {
            String subst = n.extras.getString("android.substName");
            if (!TextUtils.isEmpty(subst)) return subst;
        }
        String pkg = sbn.getPackageName();
        synchronized (sLabelCache) {
            CharSequence cached = sLabelCache.get(pkg);
            if (cached != null) return cached;
        }
        CharSequence label = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        synchronized (sLabelCache) {
            sLabelCache.put(pkg, label);
        }
        return label;
    }

    private static String bundleLabel(String channel) {
        switch (channel) {
            case "android.app.promotions": return "Promotions";
            case "android.app.news": return "News";
            case "android.app.social": return "Social";
            default: return "Recommendations";
        }
    }

    private static int bundleIcon(String channel) {
        switch (channel) {
            case "android.app.promotions": return R.drawable.ic_bundle_promotions;
            case "android.app.news": return R.drawable.ic_bundle_news;
            case "android.app.social": return R.drawable.ic_bundle_social;
            default: return R.drawable.ic_bundle_recs;
        }
    }

    /** Load an icon into a small bitmap (circle or rounded square), within a total memory budget. */
    private static Bitmap loadBitmap(Ctx x, Icon icon, int sizePx, boolean circle) {
        long bytes = (long) sizePx * sizePx * 4;
        if (x.bitmapBytes + bytes > 900_000) return null;
        Drawable d;
        try {
            d = icon.loadDrawable(x.c);
        } catch (RuntimeException e) {
            return null;
        }
        if (d == null) return null;
        Bitmap src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cs = new Canvas(src);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            // centre-crop
            float scale = Math.max(sizePx / (float) iw, sizePx / (float) ih);
            int w = Math.round(iw * scale), h = Math.round(ih * scale);
            int l = (sizePx - w) / 2, t = (sizePx - h) / 2;
            d.setBounds(l, t, l + w, t + h);
        } else {
            d.setBounds(0, 0, sizePx, sizePx);
        }
        d.draw(cs);

        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas co = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        RectF r = new RectF(0, 0, sizePx, sizePx);
        if (circle) co.drawOval(r, p);
        else co.drawRoundRect(r, sizePx * 0.22f, sizePx * 0.22f, p);
        src.recycle();
        x.bitmapBytes += bytes;
        return out;
    }
}
