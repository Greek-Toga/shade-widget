package com.giorgos.shadewidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds what the notification shade shows from the raw list of active notifications:
 * which ones are visible, how they are grouped, and in what order.
 *
 * The rules mirror SystemUI's shade list builder:
 *  - order comes from the system ranking, split into sections
 *    (calls, priority conversations, conversations, alerting, silent);
 *  - a group with two or more children shows as one group card;
 *  - a group with one child shows just that child; a lone summary shows as a normal notification;
 *  - notifications the system has moved into a Promotions/News/Social/Recommendations bundle
 *    are pulled out of their app groups and shown under that bundle;
 *  - media-player notifications are left out (the shade shows them in Quick Settings instead),
 *    as are bubble-only and Do-Not-Disturb-hidden notifications.
 */
final class ShadeModel {

    static final int SECTION_CALLS = 0;
    static final int SECTION_PRIORITY_PEOPLE = 1;
    static final int SECTION_PEOPLE = 2;
    static final int SECTION_ALERTING = 3;
    static final int SECTION_SILENT = 4;

    // Channel ids the system assigns when it files a notification into a bundle (Android 16+).
    static final String[] BUNDLE_CHANNELS = {
            "android.app.promotions", "android.app.news", "android.app.social", "android.app.recs"
    };

    static final class Entry {
        StatusBarNotification sbn;
        Ranking ranking;
        int rank;
        int section;
        long recency;
        String key;
    }

    static final class TopItem {
        Entry single;            // standalone notification, or null for a group
        Entry summary;           // group summary (may be null for bundles)
        List<Entry> children;    // group children, in shade order
        String groupKey;         // expand/collapse key
        String bundleChannel;    // non-null for a system bundle
        int section;
        int rank;
        long recency;            // newest visible member's time

        boolean isGroup() { return single == null; }
    }

    private ShadeModel() {}

    static List<TopItem> build(StatusBarNotification[] active, RankingMap rankingMap) {
        List<TopItem> out = new ArrayList<>();
        if (active == null) return out;

        // 1. Visible entries.
        List<Entry> entries = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            Ranking r = new Ranking();
            boolean hasRanking = rankingMap != null && rankingMap.getRanking(sbn.getKey(), r);
            if (!isVisibleInShade(sbn, hasRanking ? r : null)) continue;
            Entry e = new Entry();
            e.sbn = sbn;
            e.ranking = hasRanking ? r : null;
            e.rank = hasRanking ? r.getRank() : Integer.MAX_VALUE;
            e.section = sectionOf(sbn, hasRanking ? r : null);
            e.recency = recencyOf(sbn);
            e.key = sbn.getKey();
            entries.add(e);
        }

        // 2. Split into bundles, app groups and singles.
        Map<String, List<Entry>> bundles = new LinkedHashMap<>();
        Map<String, Entry> summaries = new HashMap<>();
        Map<String, List<Entry>> groupChildren = new LinkedHashMap<>();
        Map<String, Integer> bundledFromGroup = new HashMap<>();
        List<Entry> singles = new ArrayList<>();

        for (Entry e : entries) {
            String bundle = bundleChannelOf(e);
            Notification n = e.sbn.getNotification();
            boolean isSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (bundle != null && !isSummary) {
                List<Entry> list = bundles.get(bundle);
                if (list == null) { list = new ArrayList<>(); bundles.put(bundle, list); }
                list.add(e);
                if (e.sbn.isGroup()) {
                    String gk = e.sbn.getGroupKey();
                    Integer c = bundledFromGroup.get(gk);
                    bundledFromGroup.put(gk, c == null ? 1 : c + 1);
                }
                continue;
            }
            if (e.sbn.isGroup()) {
                String gk = e.sbn.getGroupKey();
                if (isSummary) {
                    summaries.put(gk, e);
                } else {
                    List<Entry> list = groupChildren.get(gk);
                    if (list == null) { list = new ArrayList<>(); groupChildren.put(gk, list); }
                    list.add(e);
                }
            } else {
                singles.add(e);
            }
        }

        Comparator<Entry> byRank = new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Integer.compare(a.rank, b.rank); }
        };

        // 3. App groups.
        for (Map.Entry<String, Entry> s : summaries.entrySet()) {
            String gk = s.getKey();
            Entry summary = s.getValue();
            List<Entry> kids = groupChildren.remove(gk);
            int kidCount = kids == null ? 0 : kids.size();
            if (kidCount >= 2) {
                Collections.sort(kids, byRank);
                out.add(group(gk, summary, kids, null));
            } else if (kidCount == 1) {
                out.add(single(kids.get(0)));
            } else if (!bundledFromGroup.containsKey(gk)) {
                // Summary with no children: the shade shows it as a normal notification.
                out.add(single(summary));
            }
        }
        // Children whose summary is missing: shown individually.
        for (List<Entry> kids : groupChildren.values()) {
            for (Entry e : kids) out.add(single(e));
        }
        for (Entry e : singles) out.add(single(e));

        // 4. Bundles.
        for (Map.Entry<String, List<Entry>> b : bundles.entrySet()) {
            List<Entry> kids = b.getValue();
            Collections.sort(kids, byRank);
            if (kids.size() == 1) {
                out.add(single(kids.get(0)));
            } else {
                out.add(group("bundle:" + b.getKey(), null, kids, b.getKey()));
            }
        }

        // 5. Order: section, then newest first (as the shade shows it), then system rank as tie-break.
        Collections.sort(out, new Comparator<TopItem>() {
            @Override public int compare(TopItem a, TopItem b) {
                if (a.section != b.section) return Integer.compare(a.section, b.section);
                if (a.recency != b.recency) return Long.compare(b.recency, a.recency);
                return Integer.compare(a.rank, b.rank);
            }
        });
        return out;
    }

    /**
     * When a notification counts as "arrived": its own timestamp, unless that is missing or set in
     * the future (calendar reminders do this), in which case the time it was posted.
     */
    private static long recencyOf(StatusBarNotification sbn) {
        long posted = sbn.getPostTime();
        long when = sbn.getNotification().when;
        return (when > 0 && when <= posted + 1000) ? when : posted;
    }

    private static TopItem single(Entry e) {
        TopItem t = new TopItem();
        t.single = e;
        t.section = e.section;
        t.rank = e.rank;
        t.recency = e.recency;
        return t;
    }

    private static TopItem group(String gk, Entry summary, List<Entry> kids, String bundle) {
        TopItem t = new TopItem();
        t.summary = summary;
        t.children = kids;
        t.groupKey = gk;
        t.bundleChannel = bundle;
        // Position comes from the notifications you actually see, not the hidden summary:
        // apps often post the summary on a silent channel even when the messages alert.
        int section = Integer.MAX_VALUE;
        int rank = Integer.MAX_VALUE;
        long recency = 0;
        for (Entry k : kids) {
            section = Math.min(section, k.section);
            rank = Math.min(rank, k.rank);
            recency = Math.max(recency, k.recency);
        }
        t.section = section;
        t.rank = rank;
        t.recency = recency;
        return t;
    }

    private static String bundleChannelOf(Entry e) {
        if (e.ranking == null) return null;
        NotificationChannel ch = e.ranking.getChannel();
        String id = ch != null ? ch.getId() : e.sbn.getNotification().getChannelId();
        if (id == null) return null;
        for (String b : BUNDLE_CHANNELS) if (b.equals(id)) return b;
        return null;
    }

    static boolean isVisibleInShade(StatusBarNotification sbn, Ranking r) {
        Notification n = sbn.getNotification();
        if (r != null) {
            if (r.isSuspended()) return false;
            if (r.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
            if ((r.getSuppressedVisualEffects()
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0) return false;
        }
        // Bubble-only notifications live in the bubble, not the shade.
        Notification.BubbleMetadata bubble = n.getBubbleMetadata();
        if (bubble != null && bubble.isNotificationSuppressed()
                && (n.flags & Notification.FLAG_BUBBLE) != 0) return false;
        // Media players are shown in Quick Settings, not in the notification list.
        if (isMedia(n)) return false;
        return true;
    }

    static boolean isMedia(Notification n) {
        if (n.extras == null) return false;
        String template = n.extras.getString(Notification.EXTRA_TEMPLATE);
        boolean mediaTemplate = template != null && template.contains("MediaStyle")
                || template != null && template.contains("MediaCustomViewStyle");
        return mediaTemplate && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    static boolean isCall(Notification n) {
        if (n.extras == null) return false;
        String template = n.extras.getString(Notification.EXTRA_TEMPLATE);
        return template != null && template.endsWith("$CallStyle");
    }

    private static int sectionOf(StatusBarNotification sbn, Ranking r) {
        Notification n = sbn.getNotification();
        if (isCall(n) && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return SECTION_CALLS;
        int importance = r != null ? r.getImportance() : NotificationManager.IMPORTANCE_DEFAULT;
        boolean alerting = importance >= NotificationManager.IMPORTANCE_DEFAULT;
        if (r != null && r.isConversation() && alerting) {
            NotificationChannel ch = r.getChannel();
            if (ch != null && ch.isImportantConversation()) return SECTION_PRIORITY_PEOPLE;
            return SECTION_PEOPLE;
        }
        return alerting ? SECTION_ALERTING : SECTION_SILENT;
    }
}
