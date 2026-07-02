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
 * <h2>v9 — Restore v6 RB logic (N(false).b() confirmed instant by user)</h2>
 *
 * <h3>Root cause of v8 RB slowness</h3>
 * v8 tried to "optimize" RB deletion by using {@code p.D1(imageId)} + direct
 * {@code File.delete()}, with {@code N(false).b()} as inline fallback when
 * File.delete() fails. This was WRONG:
 * <ul>
 *   <li>{@code File.delete()} on the {@code p.D1()} path frequently fails for
 *       RB items (path permission / cache state), causing the inline fallback
 *       {@code N(false).b()} to be invoked on every file — but with the extra
 *       overhead of a failed File.delete() syscall before it.</li>
 *   <li>User verified v6 RB deletion was INSTANT — v6 used
 *       {@code N(false).b()} directly with no File.delete() pre-attempt.</li>
 * </ul>
 *
 * <h3>v9 fix — revert RB branch to v6 logic verbatim</h3>
 * RB branch now uses {@code tVar.N(false).b()} + XMP sidecar via
 * {@code o8.d.f()} (exactly v6 logic). User confirmed this is instant.
 *
 * <h3>Branching strategy</h3>
 * <ul>
 *   <li><b>Non-RB items</b> ({@code File(tVar.j).exists() == true}):
 *       {@code File.delete()} directly (v5/v7 fast path — no wrapper).</li>
 *   <li><b>RB items</b> ({@code File(tVar.j).exists() == false}):
 *       {@code N(false).b()} + XMP sidecar via {@code o8.d.f()}
 *       (v6 logic — verified instant by user).</li>
 *   <li><b>SAF fallback</b>: if {@code N(false).b()} also fails, collect for
 *       sequential {@code db.w()} fallback (handles SAF paths).</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * b0.Z (HashSet) is accessed by w(t) unsynchronized in stock code.
 * Since stock is sequential, this is safe. Our parallel phase synchronizes
 * b0.Z access to prevent race conditions.
 */
@SuppressWarnings("unused")
public final class FastDeleteHelper {

    private static final String TAG = "FastDelete";
    private static final int DELETE_THREAD_COUNT = 2;
    private static final int PROGRESS_INTERVAL = 25;

    private FastDeleteHelper() {}

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

            // Partition by storage type
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

            // ── Group C: Parallel delete for local files ──
            // v7: branch on File(tVar.j).exists() to isolate RB path resolution
            // from the common non-RB happy path (which stays as fast as v5).
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
                            final File fileAtDbPath = new File(tVar.j);
                            final boolean fileExistsAtDbPath = fileAtDbPath.exists();

                            String realPath;          // path used for parent cache + b0.Z
                            boolean deleted;

                            if (fileExistsAtDbPath) {
                                // ── Non-RB happy path (v5/v7 fast path) ──
                                // File is at tVar.j — delete directly via File.delete().
                                // No wrapper allocation, no XMP sidecar.
                                realPath = tVar.j;
                                deleted = fileAtDbPath.delete();
                                if (!deleted) {
                                    // File.delete() failed — likely SAF-only path
                                    // (Android 11+ without MANAGE_EXTERNAL_STORAGE).
                                    // Collect for sequential w() fallback.
                                    fileDeleteFailed.incrementAndGet();
                                    synchronized (failedLocalFiles) {
                                        failedLocalFiles.add(tVar);
                                    }
                                    return;
                                }
                            } else {
                                // ── RB item path (v6 logic — VERIFIED FAST) ──
                                // File NOT at tVar.j (modified DB path) → recycle bin item.
                                // Use N(false).b() — this is the v6 logic that user confirmed
                                // was INSTANT for RB deletion. N(false) returns o8.e wrapper
                                // for D1(imageId) (the real RB path), and o8.e.b() = File.delete()
                                // which is a direct syscall (instant).
                                //
                                // Do NOT use p.D1() + File.delete() directly (v8 attempt)
                                // because File.delete() on D1 path can fail for RB items,
                                // triggering slow fallback path. N(false).b() is the correct
                                // stock-equivalent path that user verified as instant.
                                Object Nobj = tVar.N(false);
                                if (Nobj == null) {
                                    fileDeleteFailed.incrementAndGet();
                                    synchronized (failedLocalFiles) {
                                        failedLocalFiles.add(tVar);
                                    }
                                    return;
                                }
                                o8.d N = (o8.d) Nobj;
                                realPath = N.d();  // actual RB path (p.D1(imageId))
                                deleted = N.b();
                                if (!deleted && !N.c()) {
                                    // Delete failed but file doesn't exist at RB path either —
                                    // consider it deleted (matches stock w(t) behavior).
                                    deleted = true;
                                }
                                if (!deleted) {
                                    fileDeleteFailed.incrementAndGet();
                                    synchronized (failedLocalFiles) {
                                        failedLocalFiles.add(tVar);
                                    }
                                    return;
                                }

                                // XMP sidecar deletion ONLY for RB items (matches stock w(t)).
                                // Use o8.d.f() wrapper (v6 logic) — matches stock w(t) behavior.
                                // Non-RB path skips this entirely (v5 behavior).
                                try {
                                    String xmpPath = p.R1(realPath);
                                    o8.d xmpWrapper = o8.d.f(xmpPath, ctx);
                                    if (xmpWrapper.c()) {
                                        xmpWrapper.b();
                                    }
                                } catch (Exception e) {
                                    // XMP deletion failure is not critical
                                }
                            }

                            // ── Common success path ──
                            fileDeleteSuccess.incrementAndGet();

                            // Folder cover cache invalidation
                            String parent = new File(realPath).getParent();
                            if (parent != null) {
                                synchronized (parentDirsToInvalidate) {
                                    parentDirsToInvalidate.add(parent);
                                }
                            }

                            // Track deleted path (synchronized — b0.Z is HashSet)
                            if (b0.Z != null) {
                                synchronized (b0.Z) {
                                    b0.Z.add(tVar.j);
                                }
                            }

                            synchronized (deletedItems) {
                                deletedItems.add(tVar);
                            }
                            // MediaStore cleanup only for Q==0 (local with MediaStore entry)
                            if (tVar.Q == 0) {
                                synchronized (pathsForMediaStoreCleanup) {
                                    pathsForMediaStoreCleanup.add(tVar.j);
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

                // Sequential w() fallback for failed files
                if (!failedLocalFiles.isEmpty()) {
                    Log.i(TAG, "Falling back to w() for " + failedLocalFiles.size() +
                        " local files");
                    for (t tVar : failedLocalFiles) {
                        try {
                            if (db.w(tVar)) {
                                synchronized (deletedItems) {
                                    deletedItems.add(tVar);
                                }
                                if (tVar.Q == 0) {
                                    synchronized (pathsForMediaStoreCleanup) {
                                        pathsForMediaStoreCleanup.add(tVar.j);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "w() fallback error for " + tVar.j, e);
                        }
                    }
                }
            }

            // ── Group B: Sequential w() for SMB/remote cloud sources ──
            if (!cloudSourceFiles.isEmpty()) {
                for (int i = 0; i < cloudSourceFiles.size(); i++) {
                    t tVar = cloudSourceFiles.get(i);
                    try {
                        String parent = new File(tVar.j).getParent();
                        if (parent != null) {
                            parentDirsToInvalidate.add(parent);
                        }
                        if (db.w(tVar)) {
                            deletedItems.add(tVar);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud source delete error for " + tVar.j, e);
                    }
                    p.X3(i + 1, cloudSourceFiles.size());
                }
            }

            // ── Group A: Sequential cloud e2.c handling (Q==3) ──
            if (!cloudE2Files.isEmpty()) {
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
                "), delete OK=" + fileDeleteSuccess.get() +
                " failed=" + fileDeleteFailed.get());

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

            // DB cleanup (batched)
            db.U2(deletedItems);
            db.Y2(items);

            // MediaScanner (conditional)
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

                            boolean moved = srcFile.renameTo(destFile);

                            if (!moved) {
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

                if (!failedLocalFiles.isEmpty()) {
                    Log.i(TAG, "Falling back to o8.a.m() for " + failedLocalFiles.size() +
                        " local files");
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
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Move fallback error for " + tVar.j, e);
                        }
                    }
                }
            }

            if (!cloudFiles.isEmpty()) {
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

            for (String parent : parentDirsToInvalidate) {
                try {
                    b0.H.c(parent);
                } catch (Exception e) {
                    Log.w(TAG, "Folder cover cache invalidation failed for " + parent, e);
                }
            }

            if (!pathsForMediaStoreCleanup.isEmpty()) {
                batchedMediaStoreDelete(cr, pathsForMediaStoreCleanup);
            }

            if (!idsForG2Batch.isEmpty()) {
                batchedG2Update(db, idsForG2Batch);
            }

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
