/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.graphics.Bitmap;
import android.util.Log;

import com.fstop.photo.b0;

/**
 * Helper class injected by the "Persist folder thumbnails" patch (Part 4)
 * to save on-demand-loaded thumbnails to the SQLite Thumbnail table.
 *
 * <h2>Why this exists</h2>
 * F-Stop's stock behavior: thumbnails are saved to SQLite ONLY by the
 * prescan pipeline (DatabaseUpdaterService). For users with 3000+ folders,
 * prescan takes hours or even days. If the user force-stops the app before
 * prescan finishes, the SQLite cache is only partial — thumbnails for
 * un-prescanned folders are lost and must be re-decoded from disk on next
 * launch (slow).
 *
 * <h2>What this does</h2>
 * The patch injects a call to {@link #saveToSQLite(String, int, Bitmap)}
 * at the start of {@code y1.h(String, int, Bitmap, boolean, int)} — the
 * method that stores a thumbnail in the in-memory LRU cache. Every time
 * a thumbnail is stored in the LRU (whether from SQLite hit, disk decode,
 * or network), it is ALSO saved to SQLite.
 *
 * <h2>WAL checkpoint (force-stop survival)</h2>
 * F-Stop enables Write-Ahead Logging (WAL) on its SQLite database
 * ({@code setWriteAheadLoggingEnabled(true)} in e3/a.&lt;init&gt;). With WAL,
 * writes go to a separate {@code -wal} file first, and are checkpointed
 * (merged) into the main {@code .db} file later — typically when the DB
 * is closed cleanly or the WAL reaches a size threshold.
 *
 * <p>When the user force-stops the app, the process is killed WITHOUT
 * closing the DB cleanly. The WAL file may still contain uncommitted
 * thumbnail writes that haven't been checkpointed to the main {@code .db}
 * file. On next launch, SQLite reads the WAL + main DB together, so the
 * data IS technically there — BUT if Android's process killer also deletes
 * or truncates the WAL file, the data is LOST.</p>
 *
 * <p>To ensure on-demand thumbnail saves survive force-stop, we force a
 * WAL checkpoint ({@code PRAGMA wal_checkpoint(TRUNCATE)}) after EVERY
 * save. This merges the WAL into the main DB file immediately and
 * truncates the WAL to zero bytes. The main {@code .db} file is durable
 * across process kills.</p>
 *
 * <h2>Effect</h2>
 * The SQLite cache grows as the user browses. Thumbnails for folders the
 * user has visited survive force-stop. Thumbnails for folders the user
 * has NOT visited are still subject to prescan timing — but the user
 * can "warm" the cache by scrolling through the folder grid.
 *
 * <h2>Performance notes</h2>
 * <ul>
 *   <li>Called on the async loader thread (not UI thread) — does not
 *       block scrolling.</li>
 *   <li>Adds ~10-30ms per thumbnail (bitmap scaling + SQLite I/O + WAL
 *       checkpoint). Acceptable for on-demand loads.</li>
 *   <li>Redundant for SQLite-hit case (re-saves a thumbnail that was
 *       just read from SQLite). The data is identical so the UPDATE
 *       is a no-op in terms of visible data. We accept this redundancy
 *       for simplicity.</li>
 *   <li>Sets IsProcessed=1 on the Image row, which tells prescan to
 *       SKIP this image. This actually SPEEDS UP prescan (fewer images
 *       to process).</li>
 *   <li>The WAL checkpoint adds ~5-15ms but ensures durability across
 *       force-stop. Without it, thumbnails may be lost on force-stop
 *       (the exact bug we're fixing).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * SQLite handles concurrent access via its own locking. Multiple threads
 * can call Y1() concurrently — SQLite will serialize the writes. The
 * WAL checkpoint is also safe to call concurrently (SQLite handles it).
 * No additional synchronization needed.
 */
@SuppressWarnings("unused")
public final class OnDemandThumbnailSaver {

    private static final String TAG = "MorpheFstop";

    /**
     * Counter to throttle WAL checkpointing. Checkpointing EVERY write
     * is expensive (~5-15ms each). Instead, we checkpoint every
     * {@link #CHECKPOINT_INTERVAL} writes. This balances durability
     * (at most N writes lost on force-stop) with performance.
     *
     * <p>Set to 1 to checkpoint every write (maximum durability).
     * Set to 10 to checkpoint every 10th write (lost at most 10 writes
     * on force-stop, but 10x less checkpoint overhead).</p>
     */
    private static final int CHECKPOINT_INTERVAL = 1;

    private static int sWriteCount = 0;

    private OnDemandThumbnailSaver() {
        // No instances.
    }

    /**
     * Save a thumbnail to the SQLite Thumbnail table.
     *
     * <p>Called from {@code y1.h(String, int, Bitmap, boolean, int)} via
     * an injected {@code invoke-static} at the start of the method.
     * Parameters map directly: p1 → path, p2 → imageId, p3 → bitmap.</p>
     *
     * <p>The method is defensive: null path, zero imageId, or null bitmap
     * are silently skipped. Exceptions from the SQLite layer are caught
     * and logged as warnings (to avoid crashing the thumbnail pipeline).</p>
     *
     * @param path    the file path of the image (Thumbnail table key)
     * @param imageId the SQLite Image._ID (0 = invalid, skip)
     * @param bitmap  the bitmap to scale and store (null = skip)
     */
    public static void saveToSQLite(String path, int imageId, Bitmap bitmap) {
        if (path == null || imageId == 0 || bitmap == null) {
            return;
        }
        try {
            e3.b db = b0.p;
            if (db == null) {
                // DB not yet initialized — app is still starting up.
                // Silently skip; the thumbnail is still in the LRU cache.
                return;
            }
            // Y1() scales the bitmap to a MicroThumbnail blob and does
            // INSERT/REPLACE INTO Thumbnail + UPDATE Image SET IsProcessed=1.
            db.Y1(imageId, path, bitmap);

            // Force WAL checkpoint so the write survives force-stop.
            // Without this, the write stays in the -wal file and may be
            // lost if Android's process killer truncates the WAL.
            sWriteCount++;
            if (sWriteCount % CHECKPOINT_INTERVAL == 0) {
                forceWalCheckpoint(db);
            }
        } catch (Exception e) {
            // Don't crash the thumbnail pipeline on SQLite errors.
            // Log and move on — the thumbnail is still in the LRU cache
            // for this session, just not persisted to SQLite.
            Log.w(TAG, "OnDemandSQLite save failed for imageId=" + imageId
                    + " path=" + path + ": " + e.getMessage());
        }
    }

    /**
     * Force a WAL checkpoint to merge the -wal file into the main .db file.
     *
     * <p>Uses {@code PRAGMA wal_checkpoint(TRUNCATE)} which:
     * <ol>
     *   <li>Merges all WAL frames into the main database file</li>
     *   <li>Truncates the WAL file to zero bytes</li>
     * </ol>
     * This ensures the on-demand thumbnail write is durable across
     * process kills (force-stop).</p>
     *
     * <p>The TRUNCATE mode is the most thorough — it blocks until the
     * checkpoint is complete and the WAL is empty. This adds ~5-15ms
     * but guarantees durability.</p>
     *
     * @param db the e3.b DB helper (its {@code a} field is the SQLiteDatabase)
     */
    private static void forceWalCheckpoint(e3.b db) {
        try {
            android.database.sqlite.SQLiteDatabase sqlite = db.a;
            if (sqlite == null) {
                return;
            }
            // PRAGMA wal_checkpoint(TRUNCATE) — forces WAL merge + truncate.
            // This is the most durable checkpoint mode.
            sqlite.execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (Exception e) {
            // Non-fatal — checkpoint failure doesn't lose data, it just
            // means the WAL isn't merged yet. The next checkpoint will try again.
            Log.w(TAG, "WAL checkpoint failed: " + e.getMessage());
        }
    }
}
