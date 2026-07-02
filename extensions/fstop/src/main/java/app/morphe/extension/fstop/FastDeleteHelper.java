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
 * <h2>Problem</h2>
 * When the user selects a large batch of images (30, 100, 500+) and taps
 * "Delete", F-Stop's stock deletion pipeline processes each file one-by-one
 * with per-file MediaStore IPC calls. Each file requires:
 * <ol>
 *   <li>{@code p.d(path, ctx)} — 1 to 4 SQL queries via Binder IPC to
 *       MediaProvider to look up the file's MediaStore Uri by its
 *       {@code _data} path. This is the dominant cost.</li>
 *   <li>{@code ContentResolver.delete(uri, null, null)} — one IPC call
 *       per file to remove the entry from MediaStore.</li>
 *   <li>{@code p.X3(i, size)} — progress dialog UI update per file.</li>
 * </ol>
 *
 * For 500 files this is 1000-2500 Binder IPC calls plus 500 SQL
 * transactions on MediaProvider, taking 7-30 seconds on mid-range devices.
 *
 * <h2>Solution</h2>
 * Replace the per-file MediaStore URI lookup and per-file delete with a
 * SINGLE batched SQL {@code WHERE _data IN (?,?,...)} call per MediaStore
 * volume URI. This collapses N IPC calls into (num_volumes × 2) IPC calls
 * — typically 4 calls total for internal + SD card volumes.
 *
 * <h2>Fallback</h2>
 * If the fast path throws any exception, it returns {@code false} to
 * signal the caller to fall through to the original stock code.
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
     * @return {@code true} if handled (caller returns null);
     *         {@code false} to fall through to stock code
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
            final ArrayList<String> localPathsForMediaStore = new ArrayList<>();

            final int size = items.size();
            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;

                if (tVar.Q != 3) {
                    String parent = new File(tVar.j).getParent();
                    if (parent != null) {
                        b0.H.c(parent);
                    }

                    if (db.w(tVar)) {
                        deletedItems.add(tVar);
                        if (tVar.Q == 0) {
                            localPathsForMediaStore.add(tVar.j);
                        }
                    }
                } else {
                    try {
                        Object U1 = p.U1(tVar.j, tVar.S);
                        if (U1 != null) {
                            ((e2.c) U1).m();
                            ((e2.c) U1).j().close();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cloud delete failed for " + tVar.j, e);
                    }
                }

                if (size <= 100 || (i % 10) == 0 || i == size - 1) {
                    p.X3(i + 1, size);
                }
            }

            if (!localPathsForMediaStore.isEmpty()) {
                batchedMediaStoreDelete(cr, localPathsForMediaStore);
            }

            db.U2(deletedItems);
            db.Y2(items);

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
     * @param db    the {@code e3.b} database instance
     * @param items the ArrayList of {@code c3.t} media items to move
     * @return {@code true} if handled; {@code false} to fall through
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
            final ArrayList<String> localPathsForMediaStore = new ArrayList<>();
            final ArrayList<Integer> idsForG2Batch = new ArrayList<>();

            final int size = items.size();
            for (int i = 0; i < size; i++) {
                Object obj = items.get(i);
                if (!(obj instanceof t)) continue;
                t tVar = (t) obj;

                Object Nobj = tVar.N(false);
                if (Nobj == null) {
                    continue;
                }
                o8.d N = (o8.d) Nobj;
                o8.e eVar = new o8.e(p.D1(tVar.i));

                try {
                    if (!N.c()) {
                        notExistsItems.add(tVar);
                    } else {
                        if (tVar.Q == 0) {
                            localPathsForMediaStore.add(tVar.j);
                        }

                        if (o8.a.m(N, eVar)) {
                            try {
                                b0.H.c(o8.d.f(tVar.j, ctx).i());
                            } catch (Exception e) {
                                Log.w(TAG, "Folder cover cache invalidation failed", e);
                            }

                            idsForG2Batch.add(tVar.i);
                            movedItems.add(tVar);
                            b0.R4 = true;
                        } else {
                            Log.w(TAG, "Move to recycle bin failed for: " + tVar.j);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Exception during move for " + tVar.j, e);
                }

                if (size <= 100 || (i % 10) == 0 || i == size - 1) {
                    p.X3(i + 1, size);
                }
            }

            if (!localPathsForMediaStore.isEmpty()) {
                batchedMediaStoreDelete(cr, localPathsForMediaStore);
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
                cr.delete(uri, selection, selectionArgs);
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
                Log.w(TAG, "getExternalVolumeNames failed, falling back to default", e);
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
