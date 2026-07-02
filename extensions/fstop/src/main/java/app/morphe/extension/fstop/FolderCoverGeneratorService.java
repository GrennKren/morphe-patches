/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.fstop.photo.b0;

import c3.i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground Service that generates cover thumbnails for ALL folders.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Multi-threaded</b>: uses a fixed thread pool (4 threads) to process
 *       folders in parallel — 4x faster than single-threaded.</li>
 *   <li><b>Resume support</b>: queries only folders that DON'T have
 *       ThumbnailImageId set yet (LEFT JOIN FolderData). If you re-run the
 *       service, already-generated folders are skipped automatically.</li>
 *   <li><b>Path in notification</b>: shows the current folder being processed
 *       in the notification (BigTextStyle, expands to show full path).</li>
 *   <li><b>Pause/Resume/Stop</b>: notification action buttons. Pause uses
 *       volatile flag + wait/notify; each worker thread checks before
 *       processing each folder.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class FolderCoverGeneratorService extends Service {

    private static final String TAG = "MorpheFstop";

    public static final String ACTION_START = "app.morphe.fstop.GENERATE_COVERS_START";
    public static final String ACTION_PAUSE = "app.morphe.fstop.GENERATE_COVERS_PAUSE";
    public static final String ACTION_RESUME = "app.morphe.fstop.GENERATE_COVERS_RESUME";
    public static final String ACTION_STOP = "app.morphe.fstop.GENERATE_COVERS_STOP";

    private static final String CHANNEL_ID = "morphe_cover_generator";
    private static final int NOTIFICATION_ID = 0x4D5052;

    /** Number of worker threads for parallel folder processing. */
    private static final int NUM_THREADS = 4;

    private static volatile boolean sPaused = false;
    private static volatile boolean sShouldStop = false;

    private final Object mPauseLock = new Object();
    private final Object mDbWriteLock = new Object();
    private Thread mWorkerThread;

    private volatile int mCurrent = 0;
    private volatile int mTotal = 0;
    private final AtomicReference<String> mCurrentPath = new AtomicReference<>("");

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_START:
                sPaused = false;
                sShouldStop = false;
                startForeground(NOTIFICATION_ID, buildNotification(mCurrent, mTotal, mCurrentPath.get()));
                startGeneration();
                break;
            case ACTION_PAUSE:
                sPaused = true;
                updateNotification(mCurrent, mTotal, mCurrentPath.get());
                break;
            case ACTION_RESUME:
                sPaused = false;
                synchronized (mPauseLock) {
                    mPauseLock.notifyAll();
                }
                updateNotification(mCurrent, mTotal, mCurrentPath.get());
                break;
            case ACTION_STOP:
                sShouldStop = true;
                sPaused = false;
                synchronized (mPauseLock) {
                    mPauseLock.notifyAll();
                }
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sShouldStop = true;
        sPaused = false;
        synchronized (mPauseLock) {
            mPauseLock.notifyAll();
        }
        if (mWorkerThread != null && mWorkerThread.isAlive()) {
            mWorkerThread.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Folder cover generator",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Generates folder cover thumbnails in the background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int current, int total, String path) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Generating folder covers");
        builder.setSmallIcon(android.R.drawable.ic_menu_gallery);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);

        if (total > 0) {
            String contentText = current + " / " + total + " folders";
            builder.setContentText(contentText);
            builder.setProgress(total, current, false);

            // BigTextStyle shows the current folder path when notification is expanded
            String bigText = contentText;
            if (path != null && !path.isEmpty()) {
                bigText += "\n" + path;
            }
            builder.setStyle(new Notification.BigTextStyle().bigText(bigText));
        } else {
            builder.setContentText("Preparing...");
            builder.setProgress(0, 0, true);
        }

        if (sPaused) {
            builder.addAction(0, "Resume", createActionIntent(ACTION_RESUME));
        } else {
            builder.addAction(0, "Pause", createActionIntent(ACTION_PAUSE));
        }
        builder.addAction(0, "Stop", createActionIntent(ACTION_STOP));

        return builder.build();
    }

    private PendingIntent createActionIntent(String action) {
        Intent intent = new Intent(this, FolderCoverGeneratorService.class);
        intent.setAction(action);
        int requestCode = action.hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void updateNotification(int current, int total, String path) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(current, total, path));
        }
    }

    private void startGeneration() {
        if (mWorkerThread != null && mWorkerThread.isAlive()) return;

        mWorkerThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                generateAllCovers();
            } catch (Exception e) {
                Log.e(TAG, "COVER_GEN error: " + e.getMessage(), e);
            } finally {
                updateNotification(0, 0, "");
                stopForeground(true);
                stopSelf();
                Log.i(TAG, "COVER_GEN finished");
            }
        }, "MorpheCoverGen");
        mWorkerThread.start();
    }

    private void generateAllCovers() {
        e3.b db = b0.p;
        if (db == null || !db.b2()) {
            Log.w(TAG, "COVER_GEN: DB not ready");
            return;
        }
        SQLiteDatabase sqlite = db.a;
        if (sqlite == null) return;

        // ═══════════════════════════════════════════════════════════════
        // RESUME SUPPORT: query ONLY folders that don't have ThumbnailImageId
        // set yet. LEFT JOIN FolderData — if the row is missing OR
        // ThumbnailImageId is NULL/0, the folder needs processing.
        // Already-generated folders are skipped automatically.
        // ═══════════════════════════════════════════════════════════════
        List<String> folderPaths = new ArrayList<>();
        Cursor c = null;
        try {
            c = sqlite.rawQuery(
                "SELECT DISTINCT i.Folder FROM Image i " +
                "LEFT JOIN FolderData fd ON i.Folder = fd.FullPath " +
                "WHERE i.Folder IS NOT NULL AND i.Folder != '' " +
                "AND (fd.FullPath IS NULL OR fd.ThumbnailImageId IS NULL OR fd.ThumbnailImageId = 0)",
                null
            );
            if (c != null && c.moveToFirst()) {
                while (!c.isAfterLast()) {
                    folderPaths.add(c.getString(0));
                    c.moveToNext();
                }
            }
        } finally {
            if (c != null) c.close();
        }

        mTotal = folderPaths.size();
        if (mTotal == 0) {
            Log.i(TAG, "COVER_GEN: all folders already have covers — nothing to do");
            return;
        }

        Log.i(TAG, "COVER_GEN: starting for " + mTotal + " folders (" + NUM_THREADS + " threads, skipping already-generated)");

        // ═══════════════════════════════════════════════════════════════
        // MULTI-THREADED PROCESSING
        // ═══════════════════════════════════════════════════════════════
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger generated = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        final int total = mTotal;

        for (int idx = 0; idx < total; idx++) {
            if (sShouldStop) break;

            final String folderPath = folderPaths.get(idx);

            executor.submit(() -> {
                if (sShouldStop) return;

                // Check pause — each worker thread waits if paused
                if (sPaused) {
                    synchronized (mPauseLock) {
                        while (sPaused && !sShouldStop) {
                            try {
                                mPauseLock.wait(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
                if (sShouldStop) return;

                mCurrentPath.set(folderPath);

                try {
                    // Create c3.i folder item and resolve cover via O0()
                    i folderItem = new i();
                    folderItem.m = folderPath;
                    db.O0(folderItem);

                    int coverImageId = folderItem.n.c();
                    if (coverImageId != 0) {
                        // Synchronize SQLite writes to avoid "database is locked" errors
                        synchronized (mDbWriteLock) {
                            sqlite.execSQL(
                                "INSERT OR REPLACE INTO FolderData (FullPath, ThumbnailImageId) VALUES (?, ?)",
                                new Object[] { folderPath, Integer.valueOf(coverImageId) }
                            );
                        }
                        generated.incrementAndGet();
                    } else {
                        skipped.incrementAndGet();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "COVER_GEN error: " + folderPath + ": " + e.getMessage());
                    skipped.incrementAndGet();
                }

                int p = processed.incrementAndGet();
                mCurrent = p;
                // Update notification every 10 items or on the last one
                if (p % 10 == 0 || p == total) {
                    updateNotification(p, total, mCurrentPath.get());
                }
            });
        }

        // Wait for all tasks to complete
        executor.shutdown();
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Log.i(TAG, "COVER_GEN done. processed=" + mCurrent +
            " generated=" + generated.get() + " skipped=" + skipped.get() +
            " total=" + total);
    }
}
