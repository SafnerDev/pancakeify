package com.pancakeify.stable;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import top.canyie.pine.Pine;

/**
 * Bridge to Spotify's own MediaSession (it lives in the main process, the same one that runs the
 * Now Playing screen). We capture the session object when Spotify updates it, then use its
 * MediaController to read the current track / playback position and to drive playback
 * (play, pause, next, previous, seek, and the custom actions Spotify exposes for shuffle,
 * repeat and like). No permissions are needed for a session that belongs to our own app.
 */
public final class MediaBridge {
    private MediaBridge() {}

    public interface Listener {
        void onMetadata(MediaMetadata m);
        void onState(PlaybackState s);
    }

    private static volatile MediaSession session;
    private static MediaController controller;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static MediaController.Callback callback;

    // Last values seen through the hooks (valid even before a controller callback fires).
    private static volatile MediaMetadata lastMeta;
    private static volatile PlaybackState lastState;

    /** Hook the session updates so we learn about Spotify's MediaSession as early as possible. */
    public static void install() {
        try {
            for (Method m : MediaSession.class.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("setPlaybackState") || n.equals("setMetadata") || n.equals("setActive")) {
                    HookEngine.hook(m, new HookEngine.Callback() {
                        @Override public void after(Pine.CallFrame frame) {
                            if (frame.thisObject instanceof MediaSession) capture((MediaSession) frame.thisObject);
                        }
                    });
                }
            }
            Log.i(PancakeBootstrap.TAG, "MediaBridge hooks installed");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "MediaBridge install failed", t);
        }
    }

    private static synchronized void capture(MediaSession s) {
        if (session == s) return;
        session = s;
        controller = null;
        Log.i(PancakeBootstrap.TAG, "MediaBridge: captured Spotify MediaSession");
        main.post(MediaBridge::attachController);
    }

    private static synchronized void attachController() {
        try {
            if (session == null) return;
            if (controller != null && callback != null) controller.unregisterCallback(callback);
            controller = session.getController();
            callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { lastMeta = m; fire(m, null, true); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { lastState = s; fire(null, s, false); }
            };
            controller.registerCallback(callback, main);
            lastMeta = controller.getMetadata();
            lastState = controller.getPlaybackState();
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "MediaBridge attach failed", t);
        }
    }

    private static void fire(MediaMetadata m, PlaybackState s, boolean meta) {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) {
            try { if (meta) l.onMetadata(m); else l.onState(s); } catch (Throwable ignored) {}
        }
    }

    public static void addListener(Listener l) {
        synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); }
        if (controller == null) attachController();
    }

    public static void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    public static boolean isAvailable() { return session != null; }

    public static MediaMetadata metadata() {
        MediaController c = controller;
        MediaMetadata m = c != null ? c.getMetadata() : null;
        return m != null ? m : lastMeta;
    }

    public static PlaybackState state() {
        MediaController c = controller;
        PlaybackState s = c != null ? c.getPlaybackState() : null;
        return s != null ? s : lastState;
    }

    public static boolean isPlaying() {
        PlaybackState s = state();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Estimated current position in ms (extrapolated from the last state update). */
    public static long positionMs() {
        PlaybackState s = state();
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            long dt = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            pos += (long) (dt * (s.getPlaybackSpeed() == 0f ? 1f : s.getPlaybackSpeed()));
        }
        return Math.max(0, pos);
    }

    // ------------------------------------------------------------------------ transport

    private static MediaController.TransportControls tc() {
        MediaController c = controller;
        return c == null ? null : c.getTransportControls();
    }

    public static void play() { MediaController.TransportControls t = tc(); if (t != null) t.play(); }
    public static void pause() { MediaController.TransportControls t = tc(); if (t != null) t.pause(); }
    public static void next() { MediaController.TransportControls t = tc(); if (t != null) t.skipToNext(); }
    public static void previous() { MediaController.TransportControls t = tc(); if (t != null) t.skipToPrevious(); }
    public static void seekTo(long ms) { MediaController.TransportControls t = tc(); if (t != null) t.seekTo(ms); }

    public static void togglePlay() { if (isPlaying()) pause(); else play(); }

    public static void skipToQueueItem(long id) { MediaController.TransportControls t = tc(); if (t != null) t.skipToQueueItem(id); }

    /** Spotify's play queue as exposed through the session (may be empty). */
    public static java.util.List<MediaSession.QueueItem> queue() {
        MediaController c = controller;
        return c == null ? null : c.getQueue();
    }

    public static long activeQueueId() {
        PlaybackState s = state();
        return s == null ? -1 : s.getActiveQueueItemId();
    }

    /** "cachyos-safner" from the media notification's sub-text ("Listening on cachyos-safner"). */
    public static String deviceName(Context ctx) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                android.os.Bundle e = sbn.getNotification().extras;
                if (e == null || !android.app.Notification.CATEGORY_TRANSPORT.equals(sbn.getNotification().category)) continue;
                CharSequence sub = e.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT);
                if (sub == null) continue;
                String s = sub.toString();
                int i = s.toLowerCase().indexOf("on ");
                // "Listening on <device>"; any other sub-text (e.g. the album while playing on this phone) is not a device
                return i >= 0 ? s.substring(i + 3).trim() : "";
            }
            Log.i(PancakeBootstrap.TAG, "deviceName: no transport notification with sub-text among "
                    + nm.getActiveNotifications().length);
        } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "deviceName failed: " + t); }
        return "";
    }

    // -------------------------------------------------------------------- custom actions

    /** Finds one of Spotify's custom actions by a (lower-case) fragment of its label. */
    public static PlaybackState.CustomAction findAction(String... fragments) {
        PlaybackState s = state();
        if (s == null || s.getCustomActions() == null) return null;
        for (PlaybackState.CustomAction a : s.getCustomActions()) {
            String n = String.valueOf(a.getName()).toLowerCase();
            for (String f : fragments) if (n.contains(f)) return a;
        }
        return null;
    }

    /** Finds a custom action by a fragment of its id (e.g. "SHUFFLE", "REPEAT"). */
    public static PlaybackState.CustomAction findActionById(String... fragments) {
        PlaybackState s = state();
        if (s == null || s.getCustomActions() == null) return null;
        for (PlaybackState.CustomAction a : s.getCustomActions()) {
            String id = String.valueOf(a.getAction()).toUpperCase();
            for (String f : fragments) if (id.contains(f)) return a;
        }
        return null;
    }

    public static void sendAction(PlaybackState.CustomAction a) {
        MediaController.TransportControls t = tc();
        if (t != null && a != null) t.sendCustomAction(a, a.getExtras());
    }

    /** Debug dump of what Spotify exposes (temporary). */
    public static void dump(Context c) {
        try {
            MediaMetadata m = metadata();
            if (m != null) {
                for (String k : m.keySet()) {
                    Object v = null;
                    try { v = m.getText(k); } catch (Throwable ignored) {}
                    if (v == null) { try { v = m.getLong(k); } catch (Throwable ignored) {} }
                    Log.i(PancakeBootstrap.TAG, "META " + k + " = " + v + (m.getBitmap(k) != null ? " [bitmap]" : ""));
                }
            }
            PlaybackState s = state();
            if (s != null) {
                Log.i(PancakeBootstrap.TAG, "STATE " + s.getState() + " pos=" + s.getPosition() + " speed="
                        + s.getPlaybackSpeed() + " actions=" + s.getActions());
                if (s.getCustomActions() != null) for (PlaybackState.CustomAction a : s.getCustomActions()) {
                    android.os.Bundle e = a.getExtras();
                    StringBuilder sb = new StringBuilder();
                    if (e != null) for (String k : e.keySet()) sb.append(k).append('=').append(e.get(k)).append(' ');
                    Log.i(PancakeBootstrap.TAG, "ACTION id=" + a.getAction() + " name=" + a.getName() + " extras=" + sb);
                }
            }
        } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "dump failed " + t); }
    }
}
