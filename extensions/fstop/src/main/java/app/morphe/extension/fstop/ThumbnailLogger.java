/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.util.Log;

/**
 * Lightweight logger injected into F-Stop's thumbnail pipeline by the
 * {@code Thumbnail cache logger} patch.
 *
 * <h2>Purpose</h2>
 * The stock F-Stop app is essentially silent in logcat — even with
 * {@code adb logcat --pid=$(adb shell pidof -s com.fstop.photo.morphe)}
 * running, no useful app-side activity is recorded. This makes it
 * impossible to diagnose thumbnail-load latency, cache-hit rates, or
 * first-open behavior.
 *
 * <h2>What is logged</h2>
 * <ul>
 *   <li>{@code APP_ONCREATE} — fired once per process start, from
 *       {@code MyApplication.onCreate()}. Records the absolute timestamp
 *       so subsequent events can be measured relative to app start.</li>
 *   <li>{@code VIEWPOINT_ENTER} — fired from {@code MainActivity.onResume()}.
 *       This is the moment the user "enters the viewpoint" (the folder
 *       grid / home screen). Records the elapsed time since
 *       {@code APP_ONCREATE} so you can see how long app-startup-to-
 *       viewpoint takes.</li>
 *   <li>{@code BITMAP_LOADED} — fired from {@code y1.h(String, int, Bitmap, boolean, int)}
 *       whenever a thumbnail bitmap is stored in the in-memory cache.
 *       This is the "image actually loaded" signal. Each event records
 *       the per-bitmap path, the elapsed time since the most recent
 *       {@code VIEWPOINT_ENTER}, and a running counter so you can see
 *       how many thumbnails were loaded in the first N seconds.</li>
 *   <li>{@code BITMAP_CACHE_HIT} — fired from {@code y1.f(String, int, h3.b, boolean, int)}
 *       when a thumbnail lookup hits the in-memory cache (no DB/disk
 *       read needed). Useful for measuring cache effectiveness after
 *       the Persist folder thumbnails patch is applied.</li>
 *   <li>{@code CACHE_CLEARED} — fired from {@code y1.b()} whenever the
 *       cache is cleared. Lets you verify that the only clears happening
 *       are the user-triggered ones (Settings -> Main -> Cache ->
 *       "Refresh thumbnail cache").</li>
 * </ul>
 *
 * <h2>Logcat filter</h2>
 * All messages are tagged {@code MorpheFstop}. To capture them on a
 * device:
 * <pre>
 *   adb logcat -c
 *   adb logcat -s MorpheFstop:I &gt; thumbnail-log.txt
 * </pre>
 * Or, to capture all activity for the patched package only:
 * <pre>
 *   adb logcat -c
 *   adb logcat --pid=$(adb shell pidof -s com.fstop.photo.morphe) &gt; log.txt
 * </pre>
 *
 * <h2>Thread safety</h2>
 * All state is in static fields updated under the class monitor. The
 * F-Stop thumbnail pipeline is multi-threaded (the prescan runs on a
 * background thread, the UI reads on the main thread), so we need to
 * be careful with read-modify-write on the counter and timestamp
 * fields. {@code synchronized} blocks are used for the non-atomic
 * updates; the {@code long} timestamp fields are written atomically
 * on 64-bit JVMs but we still synchronize to keep the counter and
 * timestamp consistent with each other.
 */
@SuppressWarnings("unused")
public final class ThumbnailLogger {

    private static final String TAG = "MorpheFstop";

    /** Set by {@link #onAppCreate()}. 0 before app startup. */
    private static long sAppStartTime = 0L;

    /**
     * Set by {@link #onViewpointEnter()}. 0 before the first viewpoint
     * entry, and reset to the latest entry time on every subsequent
     * {@code onResume} call (so bitmap-load deltas are measured against
     * the most recent viewpoint entry, not the first one).
     */
    private static long sViewpointEntryTime = 0L;

    /** Running counter of bitmaps loaded since process start. */
    private static int sBitmapLoadCount = 0;

    /** Running counter of cache hits since process start. */
    private static int sCacheHitCount = 0;

    private ThumbnailLogger() {
        // No instances.
    }

    // ════════════════════════════════════════════════════════════════════
    // App lifecycle
    // ════════════════════════════════════════════════════════════════════

    /**
     * Called from {@code MyApplication.onCreate()} (injected by the
     * {@code Thumbnail cache logger} patch). Records the process start
     * time so all subsequent events can compute their delta from app
     * start. Safe to call multiple times — only the first call sets
     * {@link #sAppStartTime}; subsequent calls just log.
     */
    public static void onAppCreate() {
        long now = System.currentTimeMillis();
        synchronized (ThumbnailLogger.class) {
            if (sAppStartTime == 0L) {
                sAppStartTime = now;
            }
        }
        Log.i(TAG, "APP_ONCREATE t=" + now);
    }

    /**
     * Called from {@code MainActivity.onResume()} (injected by the
     * {@code Thumbnail cache logger} patch). Records the moment the
     * user enters the viewpoint (folder grid). The bitmap-load delta
     * is measured against this timestamp.
     */
    public static void onViewpointEnter() {
        long now = System.currentTimeMillis();
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            sViewpointEntryTime = now;
            deltaFromAppStart = (sAppStartTime != 0L) ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "VIEWPOINT_ENTER t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms");
    }

    // ════════════════════════════════════════════════════════════════════
    // Thumbnail pipeline
    // ════════════════════════════════════════════════════════════════════

    /**
     * Called from {@code y1.h(String, int, Bitmap, boolean, int)} (injected
     * by the patch) whenever a bitmap is stored in the in-memory cache.
     * This is the "image actually loaded" signal — the bitmap is now
     * available for display.
     *
     * @param fullPath the file path of the image whose thumbnail was
     *                  just loaded (may be null if the caller passed null;
     *                  in that case "null" is logged)
     */
    public static void onBitmapLoaded(String fullPath) {
        long now = System.currentTimeMillis();
        int count;
        long deltaFromViewpoint;
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            sBitmapLoadCount++;
            count = sBitmapLoadCount;
            deltaFromViewpoint = (sViewpointEntryTime != 0L)
                    ? (now - sViewpointEntryTime) : -1L;
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "BITMAP_LOADED #" + count
                + " t=" + now
                + " deltaFromViewpoint=" + deltaFromViewpoint + "ms"
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " path=" + (fullPath != null ? fullPath : "null"));
    }

    /**
     * Called from {@code y1.f(String, int, h3.b, boolean, int)} (injected
     * by the patch) when a thumbnail lookup hits the in-memory cache
     * (the bitmap is returned without going to disk/DB).
     *
     * @param fullPath the file path of the image whose thumbnail was
     *                  found in cache
     */
    public static void onBitmapCacheHit(String fullPath) {
        long now = System.currentTimeMillis();
        int hits;
        long deltaFromViewpoint;
        synchronized (ThumbnailLogger.class) {
            sCacheHitCount++;
            hits = sCacheHitCount;
            deltaFromViewpoint = (sViewpointEntryTime != 0L)
                    ? (now - sViewpointEntryTime) : -1L;
        }
        Log.i(TAG, "BITMAP_CACHE_HIT #" + hits
                + " t=" + now
                + " deltaFromViewpoint=" + deltaFromViewpoint + "ms"
                + " path=" + (fullPath != null ? fullPath : "null"));
    }

    /**
     * Called from {@code y1.b()} (injected by the patch) whenever the
     * thumbnail cache is cleared. Useful for verifying that the only
     * clears happening are the user-triggered ones (Settings -> Main ->
     * Cache -> "Refresh thumbnail cache"). If you see a CACHE_CLEARED
     * event that was NOT triggered by you clicking that button, then
     * some automatic path is still clearing the cache (and you should
     * file a bug against the Persist folder thumbnails patch).
     *
     * @param caller a short string identifying the caller context.
     *               Currently always {@code "y1.b()"}; the parameter
     *               exists so future patches can disambiguate multiple
     *               clear paths if needed.
     */
    public static void onCacheCleared(String caller) {
        long now = System.currentTimeMillis();
        int loads;
        int hits;
        synchronized (ThumbnailLogger.class) {
            loads = sBitmapLoadCount;
            hits = sCacheHitCount;
        }
        Log.i(TAG, "CACHE_CLEARED t=" + now
                + " caller=" + caller
                + " bitmapsLoadedSoFar=" + loads
                + " cacheHitsSoFar=" + hits);
    }

    // ════════════════════════════════════════════════════════════════════
    // SQLite thumbnail cache (added in v2 to diagnose force-stop restart issue)
    // ════════════════════════════════════════════════════════════════════

    /** Running counter of SQLite thumbnail reads (e3/b.a0 calls). */
    private static int sSQLiteReadCount = 0;

    /** Running counter of SQLite thumbnail writes (e3/b.Y1 calls = prescan saves). */
    private static int sSQLiteWriteCount = 0;

    /**
     * Called from {@code e3/b.a0(String, y1, int)} (injected by the patch)
     * whenever F-Stop attempts to read a thumbnail from the SQLite
     * {@code Thumbnail} table. This is the SQLite cache read path — it
     * fires on every in-memory cache miss (y1.f miss → z1.a → e3/b.a0).
     *
     * <p>If you see many SQLITE_READ events on app startup after a
     * force-stop, F-Stop IS trying to read from SQLite. If the reads
     * are followed quickly by BITMAP_LOADED events, the SQLite cache
     * is hitting (fast). If they're followed by slow BITMAP_LOADED
     * events (hundreds of ms), the SQLite cache is missing and F-Stop
     * is falling back to disk decode (slow).</p>
     *
     * @param fullPath the file path being looked up in SQLite
     */
    public static void onSQLiteRead(String fullPath) {
        long now = System.currentTimeMillis();
        int reads;
        int writes;
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            sSQLiteReadCount++;
            reads = sSQLiteReadCount;
            writes = sSQLiteWriteCount;
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "SQLITE_READ #" + reads
                + " t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " totalWrites=" + writes
                + " path=" + (fullPath != null ? fullPath : "null"));
    }

    /**
     * Called from {@code e3/b.Y1(int, String, Bitmap)} (injected by the
     * patch) whenever the prescan pipeline (DatabaseUpdaterService) saves
     * a newly-generated thumbnail to the SQLite {@code Thumbnail} table.
     * This is the SQLite cache WRITE path — it fires when prescan
     * processes an image and stores its MicroThumbnail blob.
     *
     * <p>If you see SQLITE_WRITE events, prescan IS running and making
     * progress. The rate of writes tells you how fast prescan is
     * processing images. If writes stop before all 3000+ folders are
     * processed, prescan was interrupted (e.g. by force-stop).</p>
     *
     * @param imageId  the SQLite Image._ID of the processed image
     * @param fullPath the file path of the processed image
     */
    public static void onSQLiteWrite(int imageId, String fullPath) {
        long now = System.currentTimeMillis();
        int reads;
        int writes;
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            sSQLiteWriteCount++;
            writes = sSQLiteWriteCount;
            reads = sSQLiteReadCount;
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "SQLITE_WRITE #" + writes
                + " t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " imageId=" + imageId
                + " totalReads=" + reads
                + " path=" + (fullPath != null ? fullPath : "null"));
    }
}
