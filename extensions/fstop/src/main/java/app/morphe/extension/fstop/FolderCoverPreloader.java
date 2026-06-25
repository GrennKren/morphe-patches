/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.fstop.photo.b0;
import com.fstop.photo.c;
import com.fstop.photo.c$a;
import com.fstop.photo.w1;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Preloads folder cover metadata from SQLite into the c.a HashMap cache
 * on app startup, so that c.b() hits the cache instead of calling O0()
 * per folder on every restart.
 *
 * <h2>CRITICAL: Access Modifier Rules</h2>
 * Extension classes are in package {@code app.morphe.extension.fstop},
 * but many APK fields are package-private (no modifier = default access
 * in {@code com.fstop.photo}). Java enforces access control at runtime:
 * <ul>
 *   <li>{@code c.a} (HashMap) — PACKAGE-PRIVATE → MUST use reflection</li>
 *   <li>{@code c$a.a} (w1 cover) — PACKAGE-PRIVATE → MUST use reflection</li>
 *   <li>{@code b0.H} (c singleton) — PUBLIC STATIC → direct access OK</li>
 *   <li>{@code b0.p} (e3.b DB) — PUBLIC STATIC → direct access OK</li>
 *   <li>{@code e3.b.a} (SQLiteDatabase) — PUBLIC → direct access OK</li>
 *   <li>{@code w1.<init>()} — PUBLIC → direct access OK</li>
 *   <li>{@code w1.i(int,String,boolean)} — PUBLIC → direct access OK</li>
 *   <li>{@code c$a.<init>(c,ArrayList)} — PUBLIC → direct access OK</li>
 * </ul>
 *
 * <h2>Why reflection?</h2>
 * Direct field access like {@code coverResolver.a} compiles fine (our stubs
 * declare the field as public), but at RUNTIME the DEX bytecode has the
 * original package-private access modifier. The Android runtime throws
 * {@code IllegalAccessError: Field 'com.fstop.photo.c.a' is inaccessible
 * to class 'app.morphe.extension.fstop.FolderCoverPreloader'}.
 * Reflection with {@code setAccessible(true)} bypasses this check.
 */
@SuppressWarnings("unused")
public final class FolderCoverPreloader {

    private static final String TAG = "MorpheFstop";

    /** Cached reflection Field for c.a (HashMap). Lazily initialized. */
    private static Field sCacheField = null;

    /** Cached reflection Field for c$a.a (w1 cover). Lazily initialized. */
    private static Field sCoverField = null;

    /** Ensure preload only runs once per process. */
    private static volatile boolean sPreloaded = false;

    private FolderCoverPreloader() {}

    /**
     * Preload folder cover metadata from SQLite into the c.a HashMap cache.
     * Async — spawns a background thread and returns immediately.
     * Only runs once per process.
     */
    public static void preloadAsync() {
        if (sPreloaded) {
            return;
        }
        sPreloaded = true;

        new Thread(() -> {
            try {
                preloadSync();
            } catch (Exception e) {
                Log.w(TAG, "FolderCoverPreloader failed: " + e.getMessage());
                sPreloaded = false; // Allow retry on next call
            }
        }, "MorpheCoverPreload").start();
    }

    /**
     * Get the c.a HashMap via reflection.
     * c.a is package-private, so we can't access it directly from our package.
     */
    private static HashMap<String, c$a> getCacheHashMap(c coverResolver) throws Exception {
        if (sCacheField == null) {
            Field f = c.class.getDeclaredField("a");
            f.setAccessible(true);
            sCacheField = f;
        }
        @SuppressWarnings("unchecked")
        HashMap<String, c$a> cache = (HashMap<String, c$a>) sCacheField.get(coverResolver);
        return cache;
    }

    /**
     * Set the c$a.a field (cover w1) via reflection.
     * c$a.a is package-private, so we can't access it directly from our package.
     */
    private static void setCoverField(c$a data, w1 cover) throws Exception {
        if (sCoverField == null) {
            Field f = c$a.class.getDeclaredField("a");
            f.setAccessible(true);
            sCoverField = f;
        }
        sCoverField.set(data, cover);
    }

    /**
     * Synchronous preload — runs on the background thread.
     */
    private static void preloadSync() throws Exception {
        long startTime = System.currentTimeMillis();

        c coverResolver = b0.H;
        if (coverResolver == null) {
            Log.w(TAG, "PRELOAD_COVERS skipped: b0.H is null");
            return;
        }

        e3.b db = b0.p;
        if (db == null) {
            Log.w(TAG, "PRELOAD_COVERS skipped: b0.p is null");
            return;
        }

        if (!db.b2()) {
            Log.w(TAG, "PRELOAD_COVERS skipped: DB not ready");
            return;
        }

        SQLiteDatabase sqlite = db.a;
        if (sqlite == null) {
            Log.w(TAG, "PRELOAD_COVERS skipped: e3.b.a is null");
            return;
        }

        // Get the c.a HashMap via reflection (package-private field)
        HashMap<String, c$a> cache = getCacheHashMap(coverResolver);

        // Check if c.a already has entries
        synchronized (cache) {
            if (!cache.isEmpty()) {
                Log.i(TAG, "PRELOAD_COVERS skipped: c.a already has "
                        + cache.size() + " entries");
                return;
            }
        }

        // Batch query: get cover image info for all folders with ThumbnailImageId set
        int preloadedCount = 0;
        int skippedNoImage = 0;
        Cursor cursor = null;
        try {
            cursor = sqlite.rawQuery(
                "SELECT fd.FullPath as FolderPath, "
                    + "i._ID as ImageId, "
                    + "i.FullPath as ImagePath, "
                    + "i.IsVideo as IsVideo "
                    + "FROM FolderData fd "
                    + "LEFT OUTER JOIN Image i ON fd.ThumbnailImageId = i._ID "
                    + "WHERE fd.ThumbnailImageId > 0",
                null
            );

            if (cursor == null) {
                Log.w(TAG, "PRELOAD_COVERS: query returned null cursor");
                return;
            }

            int folderPathIdx = cursor.getColumnIndex("FolderPath");
            int imageIdIdx = cursor.getColumnIndex("ImageId");
            int imagePathIdx = cursor.getColumnIndex("ImagePath");
            int isVideoIdx = cursor.getColumnIndex("IsVideo");

            if (folderPathIdx == -1) {
                Log.w(TAG, "PRELOAD_COVERS: FolderPath column not found");
                return;
            }

            synchronized (cache) {
                cursor.moveToFirst();
                while (!cursor.isAfterLast()) {
                    String folderPath = cursor.getString(folderPathIdx);

                    if (folderPath == null || cache.containsKey(folderPath)) {
                        cursor.moveToNext();
                        continue;
                    }

                    int imageId = cursor.getInt(imageIdIdx);
                    String imagePath = cursor.getString(imagePathIdx);
                    int isVideoInt = cursor.getInt(isVideoIdx);
                    boolean isVideo = (isVideoInt == 1);

                    if (imageId == 0 || imagePath == null) {
                        skippedNoImage++;
                        cursor.moveToNext();
                        continue;
                    }

                    // Create w1 cover with public constructor + public i() method
                    w1 cover = new w1();
                    cover.i(imageId, imagePath, isVideo);

                    // Create c$a with public constructor (empty sub-thumbs)
                    ArrayList<w1> emptySubThumbs = new ArrayList<>();
                    c$a data = new c$a(coverResolver, emptySubThumbs);

                    // Override c$a.a with populated w1 via reflection
                    // (c$a.a is package-private, constructor creates empty w1)
                    setCoverField(data, cover);

                    cache.put(folderPath, data);
                    preloadedCount++;

                    cursor.moveToNext();
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        Log.i(TAG, "PRELOAD_COVERS preloaded=" + preloadedCount
                + " skippedNoImage=" + skippedNoImage
                + " cacheSize=" + cache.size()
                + " elapsed=" + elapsed + "ms");

        ThumbnailLogger.onPreloadCovers(preloadedCount, skippedNoImage, elapsed);
    }
}
