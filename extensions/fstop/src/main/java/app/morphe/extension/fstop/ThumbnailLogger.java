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
 * <h2>Events logged (all tagged "MorpheFstop")</h2>
 * <ul>
 *   <li>{@code APP_ONCREATE} — from MyApplication.onCreate()</li>
 *   <li>{@code VIEWPOINT_ENTER} — from MainActivity.onResume()</li>
 *   <li>{@code BITMAP_LOADED} — from y1.h() (bitmap stored in cache)</li>
 *   <li>{@code BITMAP_CACHE_HIT} — from y1.f() (cache hit)</li>
 *   <li>{@code CACHE_CLEARED} — from y1.b() (cache cleared)</li>
 *   <li>{@code SQLITE_READ} — from e3/b.a0 (SQLite thumbnail read)</li>
 *   <li>{@code SQLITE_WRITE} — from e3/b.Y1 (prescan SQLite write)</li>
 *   <li>{@code FOLDER_OPENED} — from ListOfSomethingActivity.O6 (user opens folder)</li>
 *   <li>{@code COVER_RESOLVED HIT/MISS} — from c.b() (cover cache hit/miss)</li>
 *   <li>{@code PRELOAD_COVERS} — from FolderCoverPreloader (batch preload result)</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class ThumbnailLogger {

    private static final String TAG = "MorpheFstop";

    private static long sAppStartTime = 0L;
    private static long sViewpointEntryTime = 0L;
    private static int sBitmapLoadCount = 0;
    private static int sCacheHitCount = 0;
    private static int sSQLiteReadCount = 0;
    private static int sSQLiteWriteCount = 0;
    private static int sFolderOpenCount = 0;
    private static int sCoverCacheHitCount = 0;
    private static int sCoverCacheMissCount = 0;

    private ThumbnailLogger() {}

    public static void onAppCreate() {
        long now = System.currentTimeMillis();
        synchronized (ThumbnailLogger.class) {
            if (sAppStartTime == 0L) {
                sAppStartTime = now;
            }
        }
        Log.i(TAG, "APP_ONCREATE t=" + now);
    }

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

    // ════════════════════════════════════════════════════════════════════
    // Folder cover cache logging
    // ════════════════════════════════════════════════════════════════════

    public static void onFolderOpened(String folderPath) {
        long now = System.currentTimeMillis();
        int count;
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            sFolderOpenCount++;
            count = sFolderOpenCount;
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "FOLDER_OPENED #" + count
                + " t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " path=" + (folderPath != null ? folderPath : "null"));
    }

    public static void onCoverResolved(String folderPath, boolean cacheHit) {
        long now = System.currentTimeMillis();
        int hits;
        int misses;
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            if (cacheHit) {
                sCoverCacheHitCount++;
            } else {
                sCoverCacheMissCount++;
            }
            hits = sCoverCacheHitCount;
            misses = sCoverCacheMissCount;
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "COVER_RESOLVED"
                + " " + (cacheHit ? "HIT" : "MISS")
                + " t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " totalHits=" + hits
                + " totalMisses=" + misses
                + " path=" + (folderPath != null ? folderPath : "null"));
    }

    public static void onPreloadCovers(int preloaded, int skipped, long elapsedMs) {
        long now = System.currentTimeMillis();
        long deltaFromAppStart;
        synchronized (ThumbnailLogger.class) {
            deltaFromAppStart = (sAppStartTime != 0L)
                    ? (now - sAppStartTime) : -1L;
        }
        Log.i(TAG, "PRELOAD_COVERS"
                + " t=" + now
                + " deltaFromAppStart=" + deltaFromAppStart + "ms"
                + " preloaded=" + preloaded
                + " skippedNoImage=" + skipped
                + " elapsed=" + elapsedMs + "ms");
    }
}
