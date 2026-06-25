/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.graphics.Bitmap;
import android.util.Log;

import com.fstop.photo.b0;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <h2>What this does (v5 — ASYNC)</h2>
 * The patch injects a call to {@link #saveToSQLite(String, int, Bitmap)}
 * at the start of {@code y1.h(String, int, Bitmap, boolean, int)} — the
 * method that stores a thumbnail in the in-memory LRU cache. Every time
 * a thumbnail is stored in the LRU (whether from SQLite hit, disk decode,
 * or network), it is ALSO saved to SQLite — but now ASYNCHRONOUSLY.
 *
 * <p><b>v5 CHANGE:</b> Previous versions called {@code e3.b.Y1()} and
 * {@code PRAGMA wal_checkpoint(TRUNCATE)} SYNCHRONOUSLY on the z1
 * ThumbnailReader thread. This added 20–45 ms per thumbnail, making
 * folder browsing 3–7× slower than vanilla F-Stop. v5 moves all SQLite
 * I/O to a dedicated single-thread executor, so {@code y1.h()} returns
 * immediately and the z1 thread is free to load the next thumbnail.</p>
 *
 * <h2>Bitmap lifecycle safety</h2>
 * The bitmap passed to {@code y1.h()} is stored in the LRU cache by the
 * method body (after our injected call). The LRU cache holds a strong
 * reference, so the bitmap is not GC'd while cached. However, if the LRU
 * evicts the entry before the async save runs (unlikely with 500-entry
 * cache), the bitmap may be recycled. We guard against this with
 * {@code bitmap.isRecycled()} checks before and during the async save.
 * If recycled, the save is silently skipped — the thumbnail was evicted
 * because it's no longer visible, so persistence is not needed.
 *
 * <h2>WAL checkpoint (force-stop survival)</h2>
 * F-Stop enables Write-Ahead Logging (WAL) on its SQLite database.
 * We checkpoint every {@link #CHECKPOINT_INTERVAL} writes (default 50)
 * instead of every write (v4 default was 1). This reduces checkpoint
 * overhead from ~5-15ms per write to ~0.1-0.3ms amortized. At most 50
 * writes can be lost on force-stop (before the WAL is checkpointed), but
 * the WAL itself survives most force-stops — only a process kill that
 * also truncates the -wal file would lose data, which is rare.
 *
 * <h2>Effect</h2>
 * The SQLite cache grows as the user browses. Thumbnails for folders the
 * user has visited survive force-stop. Thumbnails for folders the user
 * has NOT visited are still subject to prescan timing — but the user
 * can "warm" the cache by scrolling through the folder grid.
 *
 * <h2>Performance notes</h2>
 * <ul>
 *   <li>ASYNC: saveToSQLite() now returns immediately (~0.01ms). The
 *       actual SQLite write happens on a background thread, not blocking
 *       the z1 ThumbnailReader thread.</li>
 *   <li>Previous version: 20-45ms per thumbnail (synchronous Y1 + checkpoint).
 *       v5: ~0.01ms per thumbnail (just executor.submit()).</li>
 *   <li>The LRU cache still stores the bitmap immediately (in y1.h() body),
 *       so the UI can display it right away. SQLite persistence is a
 *       best-effort background task.</li>
 *   <li>Sets IsProcessed=1 on the Image row, which tells prescan to
 *       SKIP this image. This SPEEDS UP prescan (fewer images to process).</li>
 *   <li>CHECKPOINT_INTERVAL=50: checkpoint every 50th write. Amortized
 *       overhead is negligible. Worst case: 50 writes lost on force-stop
 *       (very rare — WAL usually survives).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * The executor is single-threaded, so saves are serialized. SQLite handles
 * concurrent access via its own locking. The WAL checkpoint is safe to call
 * from the executor thread. No additional synchronization needed.
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
     * <p>v5: Changed from 1 to 50. With async saves, the checkpoint
     * no longer blocks the UI or thumbnail reader thread, but we still
     * batch checkpoints to reduce I/O overhead. At most 50 writes can
     * be lost on force-stop (before WAL is checkpointed), but the WAL
     * file itself usually survives force-stop.</p>
     */
    private static final int CHECKPOINT_INTERVAL = 50;

    private static int sWriteCount = 0;

    /**
     * Single-thread executor for async SQLite saves. Single-threaded to:
     * <ol>
     *   <li>Serialize SQLite writes (avoids concurrent write contention)</li>
     *   <li>Preserve ordering (thumbnails saved in the order they were loaded)</li>
     *   <li>Prevent resource contention with the z1 ThumbnailReader thread</li>
     * </ol>
     * The executor uses a bounded queue (1024 tasks) to prevent memory
     * issues if saves are submitted faster than they can be processed
     * (e.g., during fast scrolling). Excess tasks are silently dropped —
     * the thumbnail is still in the LRU cache for the current session.
     */
    private static final ExecutorService sExecutor = new ThreadPoolExecutor(
        1,                      // corePoolSize: single thread
        1,                      // maxPoolSize: single thread
        30L, TimeUnit.SECONDS,  // keepAliveTime: thread idle timeout
        new LinkedBlockingQueue<>(1024),  // bounded work queue
        new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MorpheThumbSaver-" + mCount.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY - 1);  // Lower than thumbnail reader
                t.setDaemon(true);  // Don't prevent app exit
                return t;
            }
        },
        new ThreadPoolExecutor.DiscardPolicy()  // Silently drop excess tasks
    );

    private OnDemandThumbnailSaver() {
        // No instances.
    }

    /**
     * Save a thumbnail to the SQLite Thumbnail table ASYNCHRONOUSLY.
     *
     * <p>Called from {@code y1.h(String, int, Bitmap, boolean, int)} via
     * an injected {@code invoke-static} at the start of the method.
     * Parameters map directly: p1 → path, p2 → imageId, p3 → bitmap.</p>
     *
     * <p>This method returns immediately. The actual SQLite write is
     * submitted to a background executor, so the z1 ThumbnailReader
     * thread is not blocked and can continue loading the next thumbnail.</p>
     *
     * <p>The method is defensive: null path, zero imageId, null bitmap,
     * or recycled bitmap are silently skipped. Exceptions from the SQLite
     * layer are caught and logged as warnings (to avoid crashing the
     * thumbnail pipeline).</p>
     *
     * @param path    the file path of the image (Thumbnail table key)
     * @param imageId the SQLite Image._ID (0 = invalid, skip)
     * @param bitmap  the bitmap to scale and store (null/recycled = skip)
     */
    public static void saveToSQLite(String path, int imageId, Bitmap bitmap) {
        if (path == null || imageId == 0 || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        // Capture a local reference to avoid the bitmap being set to null
        // by another thread between the null check and the executor submit.
        final Bitmap bitmapRef = bitmap;
        final String pathRef = path;
        final int idRef = imageId;

        sExecutor.execute(() -> {
            try {
                // Check again — bitmap may have been recycled by LRU eviction
                // between the initial check and this execution.
                if (bitmapRef.isRecycled()) {
                    return;
                }

                e3.b db = b0.p;
                if (db == null) {
                    // DB not yet initialized — app is still starting up.
                    // Silently skip; the thumbnail is still in the LRU cache.
                    return;
                }
                // Y1() scales the bitmap to a MicroThumbnail blob and does
                // INSERT/REPLACE INTO Thumbnail + UPDATE Image SET IsProcessed=1.
                db.Y1(idRef, pathRef, bitmapRef);

                // Force WAL checkpoint periodically so writes survive force-stop.
                // Without this, the writes stay in the -wal file and may be
                // lost if Android's process killer truncates the WAL.
                sWriteCount++;
                if (sWriteCount % CHECKPOINT_INTERVAL == 0) {
                    forceWalCheckpoint(db);
                }
            } catch (Exception e) {
                // Don't crash the thumbnail pipeline on SQLite errors.
                // Log and move on — the thumbnail is still in the LRU cache
                // for this session, just not persisted to SQLite.
                Log.w(TAG, "OnDemandSQLite save failed for imageId=" + idRef
                        + " path=" + pathRef + ": " + e.getMessage());
            }
        });
    }

    /**
     * Force a WAL checkpoint to merge the -wal file into the main .db file.
     *
     * <p>Uses {@code PRAGMA wal_checkpoint(TRUNCATE)} which:
     * <ol>
     *   <li>Merges all WAL frames into the main database file</li>
     *   <li>Truncates the WAL file to zero bytes</li>
     * </ol>
     * This ensures the on-demand thumbnail writes are durable across
     * process kills (force-stop).</p>
     *
     * <p>Called from the background executor thread (not the z1 thread),
     * so it does not block thumbnail loading.</p>
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
