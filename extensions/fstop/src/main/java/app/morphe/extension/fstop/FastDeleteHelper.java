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
import java.util.Set;

import e3.b;
import c3.t;

/**
 * Helper class for the "Fast batch delete" patch in F-Stop.
 *
 * <h2>v3 — Root Cause Fix: Bypass w() entirely</h2>
 *
 * <h3>Why v1/v2 were still slow</h3>
 * The stock deletion pipeline calls {@code e3.b.w(t)} per file. Inside w(),
 * the file is deleted via the o8.d wrapper:
 * <ul>
 *   <li>If MANAGE_EXTERNAL_STORAGE granted → o8.e → {@code File.delete()} (FAST)</li>
 *   <li>If NOT granted → o8.g → {@code DocumentsContract.deleteDocument()} (SAF, SLOW)</li>
 * </ul>
 *
 * v1/v2 of this patch still called {@code db.w(tVar)} per file, so when the
 * user hadn't granted MANAGE_EXTERNAL_STORAGE, every file went through SAF
 * (one IPC call per file, ~500ms-2s each). Batching MediaStore lookups
 * didn't help because the bottleneck was the per-file SAF delete, not the
 * MediaStore lookup.
 *
 * <h3>v3 Solution</h3>
 * Bypass {@code w()} entirely:
 * <ol>
 *   <li>Try {@code File.delete()} directly for each file — instant syscall,
 *       no IPC. Works for internal storage and SD card with
 *       MANAGE_EXTERNAL_STORAGE.</li>
 *   <li>If {@code File.delete()} fails (returns false), collect the path
 *       for batched MediaStore delete — which also deletes the actual file
 *       on Android 10+.</li>
 *   <li>At the end: ONE batched {@code ContentResolver.delete(_data IN ?,...)}
 *       call per volume URI. This deletes both the MediaStore entry AND the
 *       physical file for any paths where File.delete() failed.</li>
 *   <li>DB cleanup (U2/Y2) — already batched via SQL {@code _ID IN (...)}.</li>
 *   <li>MediaScanner — already batched (single call with all paths).</li>
 *   <li>Progress updates — throttled for >100 items to reduce broadcast IPC.</li>
 * </ol>
 *
 * This eliminates ALL per-file IPC calls:
 * - No per-file p.d() MediaStore lookup (eliminated in v1)
 * - No per-file ContentResolver.delete() (eliminated in v1)
 * - No per-file w() → SAF DocumentsContract.deleteDocument() (eliminated in v3)
 * - No per-file p.X3() broadcast for large batches (throttled in v3)
 */
@SuppressWarnings("unused")
public final class FastDeleteHelper {

    private static final String TAG = "FastDelete";

    private FastDeleteHelper() {}

    /**
     * Fast replacement for {@code e3.b.x(ArrayList)}.
     *
     * @param db    the {@code e3.b} database instance
     * @param items the ArrayList of {@code c3.t} media items to delete
     * @return {@code true} if handled; {@code false} to fall through to stock
     */
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

            final ArrayList deletedItems = new ArrayList();
            final ArrayList<String> pathsForMediaStoreCleanup = new ArrayList<>();

            final int size = items.size();
            int fileDeleteSuccess = 0;
            int fileDeleteFailed = 0;

            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;

                if (tVar.Q != 3) {
                    // ── Local/SMB/remote file (not cloud) ──
                    String path = tVar.j;
                    String parent = new File(path).getParent();
                    if (parent != null) {
                        b0.H.c(parent);
                    }

                    boolean deleted;
                    if (tVar.Q == 0 || tVar.Q == 1 || tVar.Q == 2) {
                        // Try File.delete() directly — bypasses o8.d wrapper
                        // and avoids SAF DocumentsContract IPC.
                        File file = new File(path);
                        if (file.exists()) {
                            deleted = file.delete();
                        } else {
                            // File doesn't exist — consider it deleted
                            deleted = true;
                        }
                    } else {
                        // Unknown Q — fall back to w()
                        deleted = db.w(tVar);
                    }

                    if (deleted) {
                        fileDeleteSuccess++;
                        if (b0.Z != null) {
                            b0.Z.add(path);
                        }
                        deletedItems.add(tVar);
                        pathsForMediaStoreCleanup.add(path);
                    } else {
                        // File.delete() failed — collect for batched MediaStore delete.
                        // ContentResolver.delete() on MediaStore URIs also deletes
                        // the physical file on Android 10+.
                        fileDeleteFailed++;
                        pathsForMediaStoreCleanup.add(path);
                        deletedItems.add(tVar);  // still add for DB cleanup
                        if (b0.Z != null) {
                            b0.Z.add(path);
                        }
                    }
                } else {
                    // ── Cloud file (Q == 3) ──
                    try {
                        Object U1 = p.U1(tVar.j, tVar.S);
                        if (U1 != null) {
                            ((e2.c) U1).m();
                            ((e2.c) U1).j().close();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud delete failed for " + tVar.j, e);
                    }
                    deletedItems.add(tVar);
                }

                // Throttled progress: update every 5 items or at start/end.
                // Stock code updates every file, sending a broadcast IPC each time.
                // For 60 files that's 60 broadcasts; for 500 that's 500.
                // We throttle to reduce IPC overhead.
                if (i == 0 || i == size - 1 || (i % 5) == 0) {
                    p.X3(i + 1, size);
                }
            }

            Log.i(TAG, "fastDelete: " + size + " items, File.delete OK=" +
                fileDeleteSuccess + " failed=" + fileDeleteFailed);

            // ── BATCHED MediaStore cleanup ──
            // Single SQL call per volume URI using _data IN (?,?,...).
            // On Android 10+, this also deletes the physical file.
            if (!pathsForMediaStoreCleanup.isEmpty()) {
                batchedMediaStoreDelete(cr, pathsForMediaStoreCleanup);
            }

            // ── F-Stop DB cleanup (already batched) ──
            db.U2(deletedItems);
            db.Y2(items);

            // ── MediaScanner (already batched) ──
            if (!deletedItems.isEmpty()) {
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

    /**
     * Fast replacement for {@code e3.b.J2(ArrayList)} (move to recycle bin).
     *
     * v3: Uses File.renameTo() directly instead of o8.a.m() (which goes
     * through SAF for file moves). Falls back to o8.a.m() if rename fails.
     */
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

            final ArrayList movedItems = new ArrayList();
            final ArrayList notExistsItems = new ArrayList();
            final ArrayList<String> pathsForMediaStoreCleanup = new ArrayList<>();
            final ArrayList<Integer> idsForG2Batch = new ArrayList<>();

            final int size = items.size();
            int renameSuccess = 0;
            int renameFailed = 0;

            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;

                // Get recycle bin destination path
                String destPath = p.D1(tVar.i);
                if (destPath == null) {
                    // Can't determine destination — fall back to stock
                    Log.w(TAG, "fastMoveToRecycleBin: D1 returned null for id=" + tVar.i);
                    return false;
                }

                File srcFile = new File(tVar.j);
                File destFile = new File(destPath);

                if (!srcFile.exists()) {
                    notExistsItems.add(tVar);
                } else {
                    // Ensure dest directory exists
                    File destDir = destFile.getParentFile();
                    if (destDir != null && !destDir.exists()) {
                        destDir.mkdirs();
                    }

                    // Try File.renameTo() directly — bypasses o8.a.m() / SAF
                    boolean moved = srcFile.renameTo(destFile);

                    if (!moved) {
                        // renameTo failed (possibly cross-filesystem) — fall back to o8.a.m()
                        try {
                            Object Nobj = tVar.N(false);
                            if (Nobj != null) {
                                o8.d N = (o8.d) Nobj;
                                o8.e eVar = new o8.e(destPath);
                                moved = o8.a.m(N, eVar);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Move fallback failed for " + tVar.j, e);
                        }
                    }

                    if (moved) {
                        renameSuccess++;
                        if (tVar.Q == 0) {
                            pathsForMediaStoreCleanup.add(tVar.j);
                        }
                        try {
                            b0.H.c(srcFile.getParent());
                        } catch (Exception e) {
                            Log.w(TAG, "Folder cover cache invalidation failed", e);
                        }
                        idsForG2Batch.add(tVar.i);
                        movedItems.add(tVar);
                        b0.R4 = true;
                    } else {
                        renameFailed++;
                        Log.w(TAG, "Move failed for: " + tVar.j + " -> " + destPath);
                    }
                }

                // Throttled progress
                if (i == 0 || i == size - 1 || (i % 5) == 0) {
                    p.X3(i + 1, size);
                }
            }

            Log.i(TAG, "fastMoveToRecycleBin: " + size + " items, rename OK=" +
                renameSuccess + " failed=" + renameFailed);

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
