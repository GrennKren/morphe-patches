package app.morphe.patches.fstop.misc.thumbnails

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the y1.b() method that clears ALL thumbnail caches.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/y1;->b()V
 * - PUBLIC, no parameters, returns void
 * - Registers: 2
 * - Calls y1.c(I) four times with indices 0, 1, 2, 4 to clear each cache slot
 *
 * This patch LEAVES y1.b() INTACT so the user-triggered Settings button
 * "Refresh thumbnail cache" (SettingsFragmentCache$b$b$a.run()) can still
 * call it to clear caches on demand.
 *
 * The automatic callers (OOM catch blocks, system memory callbacks) are
 * patched separately at their call sites to skip the y1.b() invocation.
 */
internal object ThumbnailClearCacheFingerprint : Fingerprint(
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
 * Fingerprint for the ThumbnailManager (y1) constructor.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/y1;-><init>()V
 * - PUBLIC constructor, no parameters, returns void
 * - Registers: 11 (p0 = this)
 * - Creates 4 LRU caches (y1$a extends j0 extends LinkedHashMap)
 * - Each cache is initialized with size from static field y1.e (default 50 = 0x32)
 *
 * The patch injects code at the start of the constructor (after super() call)
 * to set y1.e = 500 to hold more thumbnails in memory for 3000+ folders.
 */
internal object ThumbnailManagerInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1\$a;",
            name = "<init>",
        ),
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/y1;->h(Ljava/lang/String;ILandroid/graphics/Bitmap;ZI)V`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, takes (String path, int imageId, Bitmap, boolean, int cacheSlot), returns void
 * - Stores a bitmap in the in-memory LRU cache at slot p5, keyed by Integer.valueOf(p2)
 * - Called from z1 (async thumbnail loader) after a bitmap is loaded
 *   (either from SQLite via e3/b.a0, or decoded from disk)
 * - Broadcasts "com.fstop.photo.bitmapLoaded" Intent when p4 = true
 *
 * The patch injects a call to
 * `Lapp/morphe/extension/fstop/OnDemandThumbnailSaver;->saveToSQLite(Ljava/lang/String;ILandroid/graphics/Bitmap;)V`
 * at the very start of the method (passing p1, p2, p3), so every
 * thumbnail stored in the LRU is ALSO saved to the SQLite Thumbnail
 * table. This is the KEY FIX for "thumbnails don't survive force-stop":
 * the SQLite cache grows as the user browses, not just from prescan.
 *
 * NOTE: The Thumbnail cache logger patch ALSO injects a call at the
 * start of y1.h() (to log BITMAP_LOADED). Both injections coexist —
 * the persist patch's injection runs first (if both patches are applied),
 * then the logger's injection, then the original method body.
 */
internal object ThumbnailManagerStoreBitmapFingerprint : Fingerprint(
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
 * Fingerprint for the p.R2(SharedPreferences) method that loads all preferences.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->R2(Landroid/content/SharedPreferences;)V
 * - PUBLIC STATIC, takes SharedPreferences parameter, returns void
 * - Reads dozens of preference keys and stores them in b0 static fields
 *
 * The patch replaces the move-result v0 with const/4 v0, 0x1, ensuring
 * that X2 (prescanThumbnails) is ALWAYS set to true regardless of the
 * user's preference. This makes the prescan pipeline save thumbnails to
 * SQLite so they survive app restarts.
 */
internal object PrescanThumbnailsPrefFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/SharedPreferences;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getBoolean",
        ),
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getString",
        ),
    ),
)

// ════════════════════════════════════════════════════════════════════════════
// FINGERPRINTS FOR AUTOMATIC y1.b() CALLERS (to be NOP'd by the patch)
//
// Each of these methods calls y1.b() in an automatic context (OOM catch block
// or system memory callback). The patch replaces each `invoke-virtual {...},
// Lcom/fstop/photo/y1;->b()V` instruction with `nop` to prevent the automatic
// cache clear, while leaving y1.b() itself intact so the user-triggered
// Settings -> Main -> Cache -> "Refresh thumbnail cache" button still works.
//
// The Settings button path (SettingsFragmentCache$b$b$a.run()) is intentionally
// NOT in this list — its y1.b() call must be preserved.
// ════════════════════════════════════════════════════════════════════════════

/**
 * Fingerprint for MyApplication.onLowMemory().
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/MyApplication;->onLowMemory()V
 * - PUBLIC, no parameters, returns void
 * - Calls super.onLowMemory() then y1.b() to release thumbnail memory
 *
 * The patch NOPs the y1.b() call so the in-memory cache survives system
 * low-memory callbacks. With LRU 500 + SQLite backing (Part 1 + Part 3),
 * the memory footprint is bounded (~15MB) and persistent, so releasing
 * the cache is not necessary for stability.
 */
internal object MyApplicationOnLowMemoryFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/MyApplication;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for MyApplication.onTrimMemory(int).
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/MyApplication;->onTrimMemory(I)V
 * - PUBLIC, takes int level, returns void
 * - Calls super.onTrimMemory(level) then y1.b()
 *
 * Same rationale as onLowMemory — NOP the y1.b() call.
 */
internal object MyApplicationOnTrimMemoryFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/MyApplication;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for p.V(...) — folder thumbnail generator (variant 1).
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->V(Landroid/app/Activity;Ljava/lang/String;
 *     Landroid/graphics/Point;Ljava/lang/String;Lcom/fstop/photo/p$o;ZLc3/t;)
 *     Landroid/util/Pair;
 * - PUBLIC STATIC, returns Pair, takes 7 parameters
 * - The y1.b() call is inside :catch_a (OutOfMemoryError catch block)
 * - After y1.b(), it retries with a smaller thumbnail size
 *
 * The patch NOPs the y1.b() call. The catch block still recycles the
 * failed bitmap and tries a smaller size — it just no longer clears
 * the entire cache. With LRU 500 + SQLite, the smaller retry is
 * sufficient for OOM recovery.
 */
internal object FolderThumbnailGenVFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "Landroid/util/Pair;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Landroid/graphics/Point;",
        "Ljava/lang/String;",
        "Lcom/fstop/photo/p\$o;",
        "Z",
        "Lc3/t;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for p.W(...) — folder thumbnail generator (variant 2).
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->W(Landroid/app/Activity;Ljava/lang/String;
 *     Landroid/graphics/Point;Ljava/lang/String;Lcom/fstop/photo/p\$o;ZLc3/t;)
 *     Landroid/util/Pair;
 * - PUBLIC STATIC, returns Pair, takes 7 parameters
 * - Same structure as p.V() — y1.b() is in OOM catch block
 *
 * Same rationale — NOP the y1.b() call.
 */
internal object FolderThumbnailGenWFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "Landroid/util/Pair;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Landroid/graphics/Point;",
        "Ljava/lang/String;",
        "Lcom/fstop/photo/p\$o;",
        "Z",
        "Lc3/t;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for e3.b.a0(String, y1, int) — bitmap loader with cursor.
 *
 * From APK decompilation:
 * - Method: Le3/b;->a0(Ljava/lang/String;Lcom/fstop/photo/y1;I)Landroid/graphics/Bitmap;
 * - PUBLIC, returns Bitmap, takes 3 parameters
 * - The y1.b() call is in OOM catch block (comment says
 *   "Out of memory in getBitmap(), clearing thumbnails cache.")
 * - After y1.b(), continues cursor iteration
 *
 * The patch NOPs the y1.b() call. The catch block still logs the OOM
 * and continues to the next cursor row, which may succeed with a
 * smaller bitmap.
 */
internal object BitmapLoaderA0Fingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "Landroid/graphics/Bitmap;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "Lcom/fstop/photo/y1;", "I"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for e3.b.z2(String, String[]) — collection manager.
 *
 * From APK decompilation:
 * - Method: Le3/b;->z2(Ljava/lang/String;[Ljava/lang/String;)Ljava/util/ArrayList;
 * - PUBLIC, returns ArrayList, takes 2 parameters
 * - The y1.b() call is in OOM/RuntimeException catch block
 * - After y1.b(), it retries A2() with a counter limit
 *
 * The patch NOPs the y1.b() call. The retry counter still bounds the
 * number of retries, so worst case the operation fails gracefully.
 */
internal object CollectionManagerZ2Fingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "Ljava/util/ArrayList;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "[Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for e0.c(ArrayList) — defensive OOM retry during delete.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/e0;->c(Ljava/util/ArrayList;)V
 * - PUBLIC STATIC, takes ArrayList, returns void
 * - The y1.b() call is in OOM catch block after p.n() fails
 * - After y1.b(), it retries p.n() with a counter limit (max 2 retries)
 *
 * The patch NOPs the y1.b() call. The retry counter still bounds the
 * retries; worst case the delete operation fails after 2 OOMs.
 */
internal object DefensiveRetryC0Fingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/e0;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/util/ArrayList;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for DatabaseUpdaterService$c (inner class) — DB update catch block.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/Services/DatabaseUpdaterService\$c;->run()V
 * - PUBLIC, no parameters, returns void (Runnable)
 * - The y1.b() call is in catch_1 block (defensive clear after DB error)
 * - After y1.b(), continues the loop
 *
 * The patch NOPs the y1.b() call. The catch block still logs and
 * continues, just without clearing the cache.
 */
internal object DatabaseUpdaterServiceCRunFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/Services/DatabaseUpdaterService\$c;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "b",
        ),
    ),
)

/**
 * Fingerprint for `Lcom/fstop/photo/Services/DatabaseUpdaterService;->j()Z`.
 *
 * From APK decompilation:
 * - Method: PUBLIC, no parameters, returns boolean
 * - Body: `return b0.f8604a4 != 0 || b0.X2;`
 *   where f8604a4 = max unprocessed image count, X2 = prescanThumbnails pref
 * - Used by the prescan worker (DatabaseUpdaterService$c.run()) to decide
 *   whether to show the "Scanning media" foreground notification
 *
 * The patch replaces the method body to return ONLY `b0.X2`, so the
 * notification only appears when the user has explicitly enabled
 * "Create thumbnails in advance". When that setting is OFF, no
 * "Scanning media" notification is shown, even if there are images
 * with MetadataProcessed=0 (metadata processing still runs in the
 * background, just without the foreground notification).
 *
 * This is safe because:
 * - The prescan worker still runs and processes metadata regardless of j()
 * - j() ONLY controls the foreground notification, not the prescan itself
 * - When b0.X2=true (user enabled "Create thumbnails in advance"), j()
 *   returns true and the notification shows as in vanilla F-Stop
 * - When b0.X2=false, the prescan runs as a background service (no
 *   notification), which is fine because metadata processing is fast
 */
internal object DatabaseUpdaterServiceShouldShowNotificationFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/Services/DatabaseUpdaterService;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    // The method body reads b0.a4 (sget int) and b0.X2 (sget-boolean),
    // then returns (a4 != 0 || X2). No unique method calls to filter on,
    // but the combination of class + return type + public + no params
    // is sufficient to uniquely identify j() within DatabaseUpdaterService.
    // Verified: no other public boolean no-arg methods in this class
    // that have the same sget/sget-boolean pattern.
)
