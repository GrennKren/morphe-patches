/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.fstop.photo.b0;

import c3.e;

/**
 * Resolver injected by "Persist folder cover metadata" patch. Replaces
 * e3.b.O0(eVar) inside c.b(c3.e) to make folder cover loading INSTANT
 * on restart — exactly like image thumbnails load from SQLite.
 *
 * <h2>ROOT CAUSE (traced from APK 5.5.484)</h2>
 * Image thumbnails survive force-stop because their blobs are in the
 * SQLite Thumbnail table. On restart, z1.b() → e3.b.a0(path) reads the
 * blob → instant.
 *
 * Folder covers go through c.b() → e3.b.O0() which runs a SLOW
 * "SELECT * FROM Image WHERE Folder=? ... LIMIT 4" per folder. The
 * in-memory c.a HashMap caches the result, but c.a is CLEARED on
 * force-stop. There is NO on-disk cache for cover metadata.
 *
 * F-Stop's FolderData table HAS a ThumbnailImageId column meant for
 * this, but it is ONLY populated by manual "Set as folder cover" (z3).
 * The folder scanner does NOT create FolderData rows. So the column
 * is always 0/NULL for auto-resolved covers.
 *
 * <h2>Why v1/v2/v3 didn't work</h2>
 * v1/v2: ASYNC persist — lost on force-stop.
 * v3: SYNCHRONOUS persist, but used UPDATE. FolderData rows don't exist
 *     for most folders (only created by z3 manual cover-set). UPDATE
 *     affected 0 rows → ThumbnailImageId NEVER persisted → FAST PATH
 *     never triggered → "same loading method as vanilla."
 *
 * <h2>v4 fix</h2>
 * Use INSERT OR REPLACE to CREATE the FolderData row if it doesn't exist.
 * This guarantees ThumbnailImageId is persisted regardless of whether
 * the row existed before.
 *
 * <p>FAST PATH: single indexed JOIN query (FolderData.FullPath UNIQUE,
 * Image._ID PK). If ThumbnailImageId > 0: populate eVar.n, SKIP O0().
 * ~0.5-2ms per folder. For 15 visible folders: ~15ms total = INSTANT.</p>
 *
 * <p>SLOW PATH: call O0() (first view). Then INSERT OR REPLACE to persist
 * ThumbnailImageId. Next launch: FAST PATH.</p>
 */
@SuppressWarnings("unused")
public final class FolderCoverResolver {

    private static final String TAG = "MorpheFstop";

    private FolderCoverResolver() {}

    /**
     * Resolve cover for a folder. Called INSTEAD of e3.b.O0(eVar) from
     * inside c.b(c3.e).
     */
    public static void resolveCover(e eVar) {
        if (eVar == null) return;

        e3.b db = b0.p;
        if (db == null || !db.b2()) return;
        SQLiteDatabase sqlite = db.a;
        if (sqlite == null) return;

        String folderPath = eVar.m;
        if (folderPath == null || folderPath.isEmpty()) return;

        // ═══════════════════════════════════════════════════════════════
        // FAST PATH: check FolderData.ThumbnailImageId
        // ═══════════════════════════════════════════════════════════════
        Cursor c = null;
        try {
            c = sqlite.rawQuery(
                "SELECT i._ID, i.FullPath, i.IsVideo " +
                "FROM FolderData fd " +
                "JOIN Image i ON fd.ThumbnailImageId = i._ID " +
                "WHERE fd.FullPath = ?",
                new String[]{ folderPath }
            );

            if (c != null && c.moveToFirst()) {
                int imageId = c.getInt(0);
                String imagePath = c.getString(1);
                boolean isVideo = c.getInt(2) == 1;

                eVar.n.i(imageId, imagePath, isVideo);
                eVar.r = imageId;

                Log.i(TAG, "COVER_FAST path=" + folderPath
                    + " imageId=" + imageId);
                return; // ← SKIP O0()!
            }
        } catch (Exception ex) {
            Log.w(TAG, "COVER_FAST failed for " + folderPath
                + ": " + ex.getMessage());
        } finally {
            if (c != null) c.close();
        }

        // ═══════════════════════════════════════════════════════════════
        // SLOW PATH: call O0() then persist
        // ═══════════════════════════════════════════════════════════════
        long t0 = System.currentTimeMillis();
        db.O0(eVar);
        long elapsed = System.currentTimeMillis() - t0;

        int coverImageId = eVar.n.c();
        Log.i(TAG, "COVER_SLOW path=" + folderPath
            + " imageId=" + coverImageId + " elapsed=" + elapsed + "ms");

        // ═══════════════════════════════════════════════════════════════
        // PERSIST: INSERT OR REPLACE (creates FolderData row if missing!)
        // This is THE fix — v3 used UPDATE which was a no-op on missing rows.
        // ═══════════════════════════════════════════════════════════════
        if (coverImageId != 0) {
            eVar.r = coverImageId;
            try {
                sqlite.execSQL(
                    "INSERT OR REPLACE INTO FolderData (FullPath, ThumbnailImageId) VALUES (?, ?)",
                    new Object[] { folderPath, Integer.valueOf(coverImageId) }
                );
                Log.i(TAG, "COVER_PERSIST path=" + folderPath
                    + " imageId=" + coverImageId);
            } catch (Exception ex) {
                Log.w(TAG, "COVER_PERSIST failed for " + folderPath
                    + ": " + ex.getMessage());
            }
        }
    }
}
