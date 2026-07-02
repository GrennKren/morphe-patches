/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.fstop.photo.b0;
import com.fstop.photo.p;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import e3.b;
import c3.t;

/**
 * Helper class for the "Fast batch delete" patch in F-Stop.
 *
 * <h2>v5 — Correctness fix: proper S/Q field handling + SAF fallback</h2>
 *
 * <h3>Root cause of v4 incompatibility</h3>
 * v4 checked only the Q field to determine storage type. But F-Stop uses
 * TWO fields together:
 * <ul>
 *   <li><b>S</b> (DataSourceType): 0 = local filesystem, >0 = cloud source ID</li>
 *   <li><b>Q</b> (StorageType): 0 = local-with-MediaStore, 1 = SMB, 2 = other-remote, 3 = cloud-via-e2.c</li>
 * </ul>
 *
 * The combination matters:
 * <table>
 *   <tr><th>S</th><th>Q</th><th>Meaning</th><th>Stock w() uses</th></tr>
 *   <tr><td>≤0</td><td>0</td><td>Local file</td><td>o8.d → File.delete() or SAF</td></tr>
 *   <tr><td>≤0</td><td>1/2</td><td>Local mount of SMB/remote</td><td>o8.d → File.delete() or SAF</td></tr>
 *   <tr><td>&gt;0</td><td>1</td><td>SMB cloud source</td><td>d3.h.b(path, S) — SMB protocol</td></tr>
 *   <tr><td>&gt;0</td><td>2</td><td>Other remote cloud</td><td>d3.i.b(R, S) — remote API</td></tr>
 *   <tr><td>any</td><td>3</td><td>Cloud via e2.c (GDrive, Dropbox)</td><td>p.U1() + e2.c.m() (NOT w())</td></tr>
 * </table>
 *
 * <p>v4 tried File.delete() for Q==1/Q==2 without checking S. For SMB cloud
 * sources (S>0, Q==1), tVar.j is "smb://server/share/file.jpg" —
 * new File("smb://...").exists() returns false, so v4 set deleted=true
 * without actually deleting the SMB file. <b>DB entry was removed but the
 * file on the SMB server remained.</b></p>
 *
 * <h3>v5 fix strategy</h3>
 * <ol>
 *   <li><b>Partition by Q first</b>: Q==3 → cloud e2.c (sequential), Q!=3 → w() path</li>
 *   <li><b>Within Q!=3, partition by S</b>:
 *     <ul>
 *       <li>S≤0: local file → try File.delete() in parallel (2 threads).
 *           If File.delete() fails (SAF-only paths on Android 11+ without
 *           MANAGE_EXTERNAL_STORAGE), fall back to db.w() sequentially —
 *           w() will use DocumentsContract.deleteDocument() (SAF) which
 *           correctly deletes the physical file.</li>
 *       <li>S&gt;0: cloud source (SMB/remote) → db.w() sequentially.
 *           Cannot use File.delete() because path is protocol-based
 *           (smb://, pcloud://, etc.). w() dispatches to d3.h.b() or
 *           d3.i.b() which use the correct cloud protocol.</li>
 *     </ul>
 *   </li>
 *   <li><b>Thread safety</b>: synchronize b0.Z access in parallel phase.
 *       Stock code accesses b0.Z unsynchronized, but stock is sequential.
 *       Our parallel phase needs sync; sequential fallback phase is safe
 *       because it runs after parallel phase completes.</li>
 *   <li><b>SAF fallback correctness</b>: for files where File.delete() fails,
 *       we call db.w() which internally calls N(false).b(). N(false) returns
 *       o8.g (SAF wrapper) on Android 11+ without MANAGE_EXTERNAL_STORAGE.
 *       o8.g.b() calls DocumentsContract.deleteDocument() which correctly
 *       deletes the physical file via SAF. This matches stock behavior
 *       exactly — no regression.</li>
 * </ol>
 *
 * <h3>Compatibility matrix (v5)</h3>
 * <table>
 *   <tr><th>Scenario</th><th>v4</th><th>v5</th></tr>
 *   <tr><td>Local + MANAGE_EXTERNAL_STORAGE</td><td>✅ Fast</td><td>✅ Fast (same)</td></tr>
 *   <tr><td>Local without MANAGE_EXTERNAL_STORAGE (Android 11+)</td><td>⚠️ File tertinggal</td><td>✅ SAF fallback via w()</td></tr>
 *   <tr><td>Old Android (API 24-28)</td><td>⚠️ File tertinggal</td><td>✅ File.delete() works (no scoped storage)</td></tr>
 *   <tr><td>SMB cloud source (S>0, Q==1)</td><td>❌ File NOT deleted</td><td>✅ w() → d3.h.b() (SMB protocol)</td></tr>
 *   <tr><td>Other remote (S>0, Q==2)</td><td>❌ File NOT deleted</td><td>✅ w() → d3.i.b() (remote API)</td></tr>
 *   <tr><td>Cloud via e2.c (Q==3)</td><td>✅ Works</td><td>✅ Works (same)</td></tr>
 *   <tr><td>Recycle Bin move (local)</td><td>✅ Fast</td><td>✅ Fast (same)</td></tr>
 *   <tr><td>Recycle Bin move (cloud source)</td><td>⚠️ renameTo fails</td><td>✅ o8.a.m() fallback</td></tr>
 * </table>
 */
@SuppressWarnings("unused")
public final class FastDeleteHelper {

    private static final String TAG = "FastDelete";

    /** Thread pool size for parallel file deletion (optimal per research). */
    private static final int DELETE_THREAD_COUNT = 2;

    /** Progress broadcast interval (each call = 1 broadcast IPC). */
    private static final int PROGRESS_INTERVAL = 25;

    private FastDeleteHelper() {}

    // ═══════════════════════════════════════════════════════════════
    // fastDelete — replacement for e3.b.x(ArrayList)
    // ═══════════════════════════════════════════════════════════════

    public static boolean fastDelete(b db, ArrayList items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        if (db == null || !db.b2()) {
            return false;
        }

        try {
            final Context ctx = b0.r;
            if (ctx == null) {
                return false;
            }
            final ContentResolver cr = ctx.getContentResolver();

            final int size = items.size();

            // ── Partition items by storage type ──
            // Group A: Q==3 → cloud via e2.c (sequential, network I/O)
            // Group B: S>0, Q!=3 → SMB/remote cloud source (sequential, w())
            // Group C: S<=0, Q!=3 → local file (parallel File.delete() + w() fallback)
            final ArrayList<t> cloudE2Files = new ArrayList<>();    // Q==3
            final ArrayList<t> cloudSourceFiles = new ArrayList<>(); // S>0, Q!=3
            final ArrayList<t> localFiles = new ArrayList<>();       // S<=0, Q!=3

            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;
                if (tVar.Q == 3) {
                    cloudE2Files.add(tVar);
                } else if (tVar.S > 0) {
                    cloudSourceFiles.add(tVar);
                } else {
                    localFiles.add(tVar);
                }
            }

            final ArrayList deletedItems = new ArrayList();
            final ArrayList<String> pathsForMediaStoreCleanup = new ArrayList<>();
            final HashSet<String> parentDirsToInvalidate = new HashSet<>();
            final AtomicInteger fileDeleteSuccess = new AtomicInteger(0);
            final AtomicInteger fileDeleteFailed = new AtomicInteger(0);

            // ── Group C: Parallel File.delete() for local files ──
            if (!localFiles.isEmpty()) {
                final int localSize = localFiles.size();
                final ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(DELETE_THREAD_COUNT, localSize));
                final ArrayList<t> failedLocalFiles = new ArrayList<>(); // need w() fallback
                final ArrayList<Throwable> errors = new ArrayList<>();

                for (int i = 0; i < localSize; i++) {
                    final t tVar = localFiles.get(i);
                    final int index = i;

                    executor.execute(() -> {
                        try {
                            String path = tVar.j;
                            File file = new File(path);

                            String parent = file.getParent();
                            if (parent != null) {
                                synchronized (parentDirsToInvalidate) {
                                    parentDirsToInvalidate.add(parent);
                                }
                            }

                            boolean deleted;
                            if (file.exists()) {
                                deleted = file.delete();
                            } else {
                                // File doesn't exist — consider it deleted.
                                // Stock w() does the same: if !b() && !c(), set b10=true.
                                deleted = true;
                            }

                            if (deleted) {
                                fileDeleteSuccess.incrementAndGet();
                                if (b0.Z != null) {
                                    synchronized (b0.Z) {
                                        b0.Z.add(path);
                                    }
                                }
                                synchronized (deletedItems) {
                                    deletedItems.add(tVar);
                                }
                                synchronized (pathsForMediaStoreCleanup) {
                                    pathsForMediaStoreCleanup.add(path);
                                }
                            } else {
                                // File.delete() failed — likely SAF-only path
                                // (Android 11+ without MANAGE_EXTERNAL_STORAGE).
                                // Collect for sequential w() fallback.
                                fileDeleteFailed.incrementAndGet();
                                synchronized (failedLocalFiles) {
                                    failedLocalFiles.add(tVar);
                                }
                            }

                            if (index == 0 || index == localSize - 1 ||
                                (index % PROGRESS_INTERVAL) == 0) {
                                p.X3(index + 1, localSize);
                            }
                        } catch (Throwable e) {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        }
                    });
                }

                executor.shutdown();
                try {
                    executor.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Parallel delete interrupted", e);
                }

                if (!errors.isEmpty()) {
                    Log.w(TAG, "Parallel delete had " + errors.size() + " errors");
                    for (Throwable e : errors) {
                        Log.w(TAG, "Delete error", e);
                    }
                }

                // ── Sequential w() fallback for failed local files ──
                // w() will use N(false).b() which dispatches to o8.g (SAF)
                // on Android 11+ without MANAGE_EXTERNAL_STORAGE.
                // SAF correctly deletes the physical file.
                if (!failedLocalFiles.isEmpty()) {
                    Log.i(TAG, "Falling back to w() for " + failedLocalFiles.size() +
                        " local files (File.delete() failed)");
                    for (t tVar : failedLocalFiles) {
                        try {
                            if (db.w(tVar)) {
                                synchronized (deletedItems) {
                                    deletedItems.add(tVar);
                                }
                                synchronized (pathsForMediaStoreCleanup) {
                                    pathsForMediaStoreCleanup.add(tVar.j);
                                }
                            } else {
                                Log.w(TAG, "w() also failed for: " + tVar.j);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "w() fallback error for " + tVar.j, e);
                        }
                    }
                }
            }

            // ── Group B: Sequential w() for SMB/remote cloud sources ──
            // These need d3.h.b() (SMB) or d3.i.b() (remote) — cannot use
            // File.delete() because paths are protocol-based (smb://, etc.)
            if (!cloudSourceFiles.isEmpty()) {
                Log.i(TAG, "Processing " + cloudSourceFiles.size() +
                    " cloud source files sequentially (SMB/remote)");
                for (int i = 0; i < cloudSourceFiles.size(); i++) {
                    t tVar = cloudSourceFiles.get(i);
                    try {
                        // Folder cache invalidation (stock does this before w())
                        String parent = new File(tVar.j).getParent();
                        if (parent != null) {
                            parentDirsToInvalidate.add(parent);
                        }
                        if (db.w(tVar)) {
                            deletedItems.add(tVar);
                            // No MediaStore cleanup for cloud sources (Q!=0)
                        } else {
                            Log.w(TAG, "w() failed for cloud source: " + tVar.j);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud source delete error for " + tVar.j, e);
                    }
                    p.X3(i + 1, cloudSourceFiles.size());
                }
            }

            // ── Group A: Sequential cloud e2.c handling (Q==3) ──
            if (!cloudE2Files.isEmpty()) {
                Log.i(TAG, "Processing " + cloudE2Files.size() +
                    " cloud e2.c files sequentially");
                for (int i = 0; i < cloudE2Files.size(); i++) {
                    t tVar = cloudE2Files.get(i);
                    try {
                        Object U1 = p.U1(tVar.j, tVar.S);
                        if (U1 != null) {
                            ((e2.c) U1).m();
                            ((e2.c) U1).j().close();
                        }
                        deletedItems.add(tVar);
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud e2.c delete failed for " + tVar.j, e);
                    }
                    p.X3(i + 1, cloudE2Files.size());
                }
            }

            Log.i(TAG, "fastDelete: " + size + " items (local=" + localFiles.size() +
                ", cloudSource=" + cloudSourceFiles.size() +
                ", cloudE2=" + cloudE2Files.size() +
                "), File.delete OK=" + fileDeleteSuccess.get() +
                " failed=" + fileDeleteFailed.get());

            // ── Batched folder cover cache invalidation ──
            for (String parent : parentDirsToInvalidate) {
                try {
                    b0.H.c(parent);
                } catch (Exception e) {
                    Log.w(TAG, "Folder cover cache invalidation failed for " + parent, e);
                }
            }

            // ── BATCHED MediaStore cleanup ──
            // Only for local file paths (Q==0). Cloud source paths are not in MediaStore.
            if (!pathsForMediaStoreCleanup.isEmpty()) {
                batchedMediaStoreDelete(cr, pathsForMediaStoreCleanup);
            }

            // ── F-Stop DB cleanup (already batched) ──
            db.U2(deletedItems);
            db.Y2(items);

            // ── MediaScanner (conditional) ──
            // Skip if all File.delete() succeeded AND no cloud fallback needed.
            // Only call MediaScanner if there were failures (SAF paths that
            // need MediaStore refresh).
            if (fileDeleteFailed.get() > 0 && !deletedItems.isEmpty()) {
                String[] paths = new String[deletedItems.size()];
                for (int i = 0; i < deletedItems.size(); i++) {
                    paths[i] = ((t) deletedItems.get(i)).j;
                }
                try {
                    MediaScannerConnection.scanFile(ctx, paths, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "MediaScanner scanFile failed", e);
                }
            }

            return true;
        } catch (Throwable t) {
            Log.e(TAG, "fastDelete failed — falling back to stock code", t);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // fastMoveToRecycleBin — replacement for e3.b.J2(ArrayList)
    // ═══════════════════════════════════════════════════════════════

    public static boolean fastMoveToRecycleBin(b db, ArrayList items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        if (db == null || !db.b2()) {
            return false;
        }

        try {
            final Context ctx = b0.r;
            if (ctx == null) {
                return false;
            }
            final ContentResolver cr = ctx.getContentResolver();

            final int size = items.size();

            // ── Partition: local (S<=0, Q!=3) vs cloud (S>0 or Q==3) ──
            // Cloud sources cannot use File.renameTo() — must use o8.a.m()
            // which dispatches to the correct cloud protocol.
            final ArrayList<t> localFiles = new ArrayList<>();
            final ArrayList<t> cloudFiles = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;
                if (tVar.S > 0 || tVar.Q == 3) {
                    cloudFiles.add(tVar);
                } else {
                    localFiles.add(tVar);
                }
            }

            final ArrayList movedItems = new ArrayList();
            final ArrayList notExistsItems = new ArrayList();
            final ArrayList<String> pathsForMediaStoreCleanup = new ArrayList<>();
            final ArrayList<Integer> idsForG2Batch = new ArrayList<>();
            final HashSet<String> parentDirsToInvalidate = new HashSet<>();
            final AtomicInteger renameSuccess = new AtomicInteger(0);
            final AtomicInteger renameFailed = new AtomicInteger(0);

            // ── Parallel File.renameTo() for local files ──
            if (!localFiles.isEmpty()) {
                final int localSize = localFiles.size();
                final ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(DELETE_THREAD_COUNT, localSize));
                final ArrayList<t> failedLocalFiles = new ArrayList<>();
                final ArrayList<Throwable> errors = new ArrayList<>();

                for (int i = 0; i < localSize; i++) {
                    final t tVar = localFiles.get(i);
                    final int index = i;

                    executor.execute(() -> {
                        try {
                            String destPath = p.D1(tVar.i);
                            if (destPath == null) {
                                synchronized (errors) {
                                    errors.add(new RuntimeException(
                                        "D1 returned null for id=" + tVar.i));
                                }
                                return;
                            }

                            File srcFile = new File(tVar.j);
                            File destFile = new File(destPath);

                            if (!srcFile.exists()) {
                                synchronized (notExistsItems) {
                                    notExistsItems.add(tVar);
                                }
                                return;
                            }

                            File destDir = destFile.getParentFile();
                            if (destDir != null && !destDir.exists()) {
                                destDir.mkdirs();
                            }

                            String parent = srcFile.getParent();
                            if (parent != null) {
                                synchronized (parentDirsToInvalidate) {
                                    parentDirsToInvalidate.add(parent);
                                }
                            }

                            // Try File.renameTo() first (instant for same-filesystem)
                            boolean moved = srcFile.renameTo(destFile);

                            if (!moved) {
                                // renameTo failed — likely cross-filesystem.
                                // Fall back to o8.a.m() which handles copy+delete.
                                synchronized (failedLocalFiles) {
                                    failedLocalFiles.add(tVar);
                                }
                                renameFailed.incrementAndGet();
                            } else {
                                renameSuccess.incrementAndGet();
                                if (tVar.Q == 0) {
                                    synchronized (pathsForMediaStoreCleanup) {
                                        pathsForMediaStoreCleanup.add(tVar.j);
                                    }
                                }
                                synchronized (idsForG2Batch) {
                                    idsForG2Batch.add(tVar.i);
                                }
                                synchronized (movedItems) {
                                    movedItems.add(tVar);
                                }
                                b0.R4 = true;
                            }

                            if (index == 0 || index == localSize - 1 ||
                                (index % PROGRESS_INTERVAL) == 0) {
                                p.X3(index + 1, localSize);
                            }
                        } catch (Throwable e) {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        }
                    });
                }

                executor.shutdown();
                try {
                    executor.awaitTermination(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Parallel move interrupted", e);
                }

                if (!errors.isEmpty()) {
                    Log.w(TAG, "Parallel move had " + errors.size() + " errors");
                    for (Throwable e : errors) {
                        Log.w(TAG, "Move error", e);
                    }
                }

                // ── Sequential o8.a.m() fallback for failed local files ──
                if (!failedLocalFiles.isEmpty()) {
                    Log.i(TAG, "Falling back to o8.a.m() for " + failedLocalFiles.size() +
                        " local files (renameTo failed)");
                    for (t tVar : failedLocalFiles) {
                        try {
                            String destPath = p.D1(tVar.i);
                            if (destPath == null) continue;
                            Object Nobj = tVar.N(false);
                            if (Nobj != null) {
                                o8.d N = (o8.d) Nobj;
                                o8.e eVar = new o8.e(destPath);
                                if (o8.a.m(N, eVar)) {
                                    if (tVar.Q == 0) {
                                        pathsForMediaStoreCleanup.add(tVar.j);
                                    }
                                    idsForG2Batch.add(tVar.i);
                                    movedItems.add(tVar);
                                    b0.R4 = true;
                                    renameSuccess.incrementAndGet();
                                } else {
                                    Log.w(TAG, "o8.a.m() also failed for: " + tVar.j);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Move fallback error for " + tVar.j, e);
                        }
                    }
                }
            }

            // ── Sequential o8.a.m() for cloud source files ──
            if (!cloudFiles.isEmpty()) {
                Log.i(TAG, "Processing " + cloudFiles.size() +
                    " cloud files sequentially (cannot renameTo)");
                for (int i = 0; i < cloudFiles.size(); i++) {
                    t tVar = cloudFiles.get(i);
                    try {
                        String destPath = p.D1(tVar.i);
                        if (destPath == null) continue;
                        Object Nobj = tVar.N(false);
                        if (Nobj == null) continue;
                        o8.d N = (o8.d) Nobj;
                        o8.e eVar = new o8.e(destPath);
                        if (o8.a.m(N, eVar)) {
                            if (tVar.Q == 0) {
                                pathsForMediaStoreCleanup.add(tVar.j);
                            }
                            idsForG2Batch.add(tVar.i);
                            movedItems.add(tVar);
                            b0.R4 = true;
                        } else {
                            Log.w(TAG, "Cloud move failed for: " + tVar.j);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud move error for " + tVar.j, e);
                    }
                    p.X3(i + 1, cloudFiles.size());
                }
            }

            Log.i(TAG, "fastMoveToRecycleBin: " + size + " items (local=" +
                localFiles.size() + ", cloud=" + cloudFiles.size() +
                "), rename OK=" + renameSuccess.get() +
                " failed=" + renameFailed.get());

            // Batched folder cover cache invalidation
            for (String parent : parentDirsToInvalidate) {
                try {
                    b0.H.c(parent);
                } catch (Exception e) {
                    Log.w(TAG, "Folder cover cache invalidation failed for " + parent, e);
                }
            }

            // Batched MediaStore cleanup
            if (!pathsForMediaStoreCleanup.isEmpty()) {
                batchedMediaStoreDelete(cr, pathsForMediaStoreCleanup);
            }

            // Batched G2 SQL UPDATE
            if (!idsForG2Batch.isEmpty()) {
                batchedG2Update(db, idsForG2Batch);
            }

            // DB cleanup (batched)
            db.U2(notExistsItems);
            db.Y2(items);
            db.L2(movedItems);
            db.L2(notExistsItems);

            return true;
        } catch (Throwable t) {
            Log.e(TAG, "fastMoveToRecycleBin failed — falling back to stock code", t);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════

    private static void batchedMediaStoreDelete(ContentResolver cr, ArrayList<String> paths) {
        if (paths.isEmpty()) return;

        final ArrayList<Uri> volumeUris = getMediaStoreVolumeUris();
        if (volumeUris.isEmpty()) return;

        final int n = paths.size();
        final StringBuilder sb = new StringBuilder("_data IN (?");
        for (int i = 1; i < n; i++) {
            sb.append(",?");
        }
        sb.append(")");
        final String selection = sb.toString();
        final String[] selectionArgs = paths.toArray(new String[0]);

        for (Uri uri : volumeUris) {
            try {
                int deleted = cr.delete(uri, selection, selectionArgs);
                Log.d(TAG, "MediaStore batched delete " + uri + ": " + deleted + " rows");
            } catch (Exception e) {
                Log.w(TAG, "MediaStore batched delete failed for " + uri, e);
            }
        }
    }

    private static ArrayList<Uri> getMediaStoreVolumeUris() {
        final ArrayList<Uri> uris = new ArrayList<>();
        final Context ctx = b0.r;
        if (ctx == null) return uris;

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Set<String> volumes = MediaStore.getExternalVolumeNames(ctx);
                for (String v : volumes) {
                    uris.add(MediaStore.Images.Media.getContentUri(v));
                    uris.add(MediaStore.Video.Media.getContentUri(v));
                }
            } catch (Exception e) {
                Log.w(TAG, "getExternalVolumeNames failed", e);
                uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            }
        } else {
            uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        }
        return uris;
    }

    private static void batchedG2Update(b db, ArrayList<Integer> ids) {
        if (ids.isEmpty()) return;
        final long ts = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder(128 + ids.size() * 12);
        sb.append("update Image set IsProtected=0, DeleteDate = ");
        sb.append(ts);
        sb.append(", FullPath=FullPath || '_' || _ID where _ID in (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i).intValue());
        }
        sb.append(")");
        try {
            db.a.execSQL(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "batchedG2Update failed", e);
        }
    }
}
