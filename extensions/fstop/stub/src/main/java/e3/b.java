/*
 * Stub for F-Stop's database helper class (e3/b).
 * Used only at compile time so extension code can reference APK classes.
 *
 * Field names verified via androguard against the actual APK DEX:
 * - b0.p : static e3.b — the singleton DB helper instance, initialized
 *   during app startup. Used to access the SQLite Thumbnail table.
 * - e3.b.a : public SQLiteDatabase — the underlying SQLite database.
 *   Initialized by e3/b.R2(Context) via getWritableDatabase().
 *   WAL (Write-Ahead Logging) is enabled on it.
 *
 * Method signatures verified from the decompiled APK:
 * - Y1(int imageId, String fullPath, Bitmap bitmap) : void
 *   Scales the bitmap to a MicroThumbnail blob, then INSERT or REPLACE
 *   into the Thumbnail table (keyed by FullPath). Also runs
 *   UPDATE Image SET IsProcessed = 1 WHERE _ID = imageId.
 *   Called from DatabaseUpdaterService$c (prescan),
 *   CloudThumbnailScannerService$a (cloud prescan), and — after the
 *   Persist folder thumbnails patch is applied — from
 *   OnDemandThumbnailSaver.saveToSQLite() (on-demand save).
 */
package e3;

import android.database.sqlite.SQLiteDatabase;

import android.graphics.Bitmap;

@SuppressWarnings("unused")
public class b {

    /**
     * The underlying SQLite database.
     * Public field — accessed directly by OnDemandThumbnailSaver
     * to force WAL checkpointing after on-demand thumbnail saves.
     */
    public SQLiteDatabase a;

    /**
     * Save a thumbnail to the SQLite Thumbnail table.
     * Scales the bitmap to a MicroThumbnail blob, then INSERT or REPLACE.
     * Also runs UPDATE Image SET IsProcessed=1 WHERE _ID=imageId.
     *
     * @param imageId  the SQLite Image._ID
     * @param fullPath the file path (used as the Thumbnail table key)
     * @param bitmap   the full-size bitmap to scale and store
     */
    public void Y1(int imageId, String fullPath, Bitmap bitmap) {
        // Stub — real implementation is in the APK.
    }
}
