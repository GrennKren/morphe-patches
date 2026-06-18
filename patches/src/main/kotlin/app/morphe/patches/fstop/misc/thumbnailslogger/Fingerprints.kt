package app.morphe.patches.fstop.misc.thumbnailslogger

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for `Lcom/fstop/photo/MyApplication;->onCreate()V`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, no parameters, returns void
 * - Calls `super.onCreate()` first, then initializes Firebase, Bugsnag,
 *   FolderScannerJobService, etc.
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onAppCreate()V`
 * at the very beginning of the method (after `super.onCreate()`) so
 * the APP_ONCREATE log line appears before any other app-side work.
 */
internal object MyApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/MyApplication;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/firebase/analytics/FirebaseAnalytics;",
            name = "getInstance",
        ),
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/activity/MainActivity;->onResume()V`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, no parameters, returns void
 * - Calls `super.onResume()` first, then processes the launch Intent
 *
 * MainActivity is F-Stop's home screen — the folder grid view that
 * the user sees when they open the app. This is what we call the
 * "viewpoint" in the log.
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onViewpointEnter()V`
 * right after `super.onResume()` so the VIEWPOINT_ENTER log line
 * marks the moment the user entered the folder grid.
 */
internal object MainActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/MainActivity;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroidx/fragment/app/FragmentActivity;",
            name = "onResume",
        ),
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/y1;->h(Ljava/lang/String;ILandroid/graphics/Bitmap;ZI)V`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, takes (String, int, Bitmap, boolean, int), returns void
 * - Stores a bitmap in the in-memory cache at slot `p5` keyed by `p2`
 * - Broadcasts "com.fstop.photo.bitmapLoaded" Intent when the bitmap
 *   is newly cached (p4 = true) — this is F-Stop's own "image loaded"
 *   signal
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onBitmapLoaded(Ljava/lang/String;)V`
 * at the start of the method (passing p1 = the file path), so every
 * bitmap-store event is logged with its path and a timestamp.
 */
internal object Y1StoreBitmapFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Landroid/graphics/Bitmap;",
        "Z",
        "I",
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/y1;->f(Ljava/lang/String;ILh3/b;ZI)Landroid/graphics/Bitmap;`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, takes (String, int, h3.b, boolean, int), returns Bitmap
 * - Looks up bitmap by (path, id, cacheSlot) in the in-memory cache
 * - Returns the cached bitmap immediately if found (cache hit)
 * - Otherwise triggers async load via z1.a() and returns null
 *
 * The patch injects a check after the cache-hit return path: when the
 * method returns a non-null bitmap (cache hit), it calls
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onBitmapCacheHit(Ljava/lang/String;)V`
 * with the path. This lets us measure cache effectiveness after the
 * Persist folder thumbnails patch is applied.
 *
 * NOTE: The injection is done by replacing the `monitor-exit + return-object`
 * sequence at the cache-hit branch with a sequence that calls the logger
 * first, then returns the bitmap.
 */
internal object Y1GetBitmapFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "Landroid/graphics/Bitmap;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Lh3/b;",
        "Z",
        "I",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/z1;",
            name = "a",
        ),
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/y1;->b()V` (the cache-clear method).
 *
 * Reuses the same fingerprint as the Persist folder thumbnails patch.
 * The logger patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onCacheCleared(Ljava/lang/String;)V`
 * at the start of y1.b() so every cache-clear event is logged. This
 * lets you verify that the only clears happening are the user-triggered
 * ones (Settings -> Main -> Cache -> "Refresh thumbnail cache").
 */
internal object Y1ClearCacheFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "c",
        ),
    ),
)

/**
 * Fingerprint for `Le3/b;->a0(Ljava/lang/String;Lcom/fstop/photo/y1;I)Landroid/graphics/Bitmap;`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, takes (String path, y1 thumbManager, int cacheSlot), returns Bitmap
 * - Runs `SELECT MicroThumbnail FROM Thumbnail WHERE FullPath = ?` on the SQLite DB
 * - Decodes the MicroThumbnail blob to a Bitmap and returns it
 * - Returns null if the SQLite row doesn't exist (cache miss) or cursor is empty
 * - Called from `z1.a()` (the async thumbnail loader) on every in-memory cache miss
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onSQLiteRead(Ljava/lang/String;)V`
 * at the start of the method (passing p1 = the file path), so every
 * SQLite thumbnail read attempt is logged. This lets you see whether
 * F-Stop is trying to read from SQLite on app restart (it should) and
 * correlate with BITMAP_LOADED events to determine hit/miss rate.
 */
internal object E3BSQLiteReadFingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "Landroid/graphics/Bitmap;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "Lcom/fstop/photo/y1;", "I"),
)

/**
 * Fingerprint for `Le3/b;->Y1(ILjava/lang/String;Landroid/graphics/Bitmap;)V`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, takes (int imageId, String fullPath, Bitmap bitmap), returns void
 * - Scales the bitmap to a MicroThumbnail blob
 * - INSERT or REPLACE into the SQLite `Thumbnail` table (keyed by FullPath)
 * - Runs `UPDATE Image SET IsProcessed = 1 WHERE _ID = <imageId>`
 * - Called from DatabaseUpdaterService$c (prescan worker) and
 *   CloudThumbnailScannerService$a (cloud prescan)
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/ThumbnailLogger;->onSQLiteWrite(ILjava/lang/String;)V`
 * at the start of the method (passing p1 = imageId, p2 = fullPath), so
 * every prescan thumbnail save is logged. This lets you verify that
 * prescan is running and making progress, and estimate how many
 * thumbnails have been persisted to SQLite.
 */
internal object E3BSQLiteWriteFingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I", "Ljava/lang/String;", "Landroid/graphics/Bitmap;"),
)
