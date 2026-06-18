/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.thumbnails

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction


/**
 * Patch to fix thumbnail disappearance when F-Stop has 3000+ folders,
 * while keeping the Settings -> Main -> Cache buttons functional AND
 * NOT triggering the "Scanning media" notification.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PROBLEM (original, with "Create thumbnails in advance" OFF):
 * ─────────────────────────────────────────────────────────────────────────
 * When "Create thumbnails in advance" (b0.X2 / SharedPreferences key
 * "prescanThumbnails") is disabled, thumbnails are loaded on-demand into
 * an LRU memory cache (default size 50 entries) and are NOT saved to
 * SQLite by the prescan pipeline (because prescan doesn't run). With
 * 3000+ folders:
 *   1. The LRU cache (50 entries) is too small to hold all thumbnails
 *   2. On-demand thumbnails are NOT saved to SQLite (only prescan does)
 *   3. Result: thumbnails disappear after the in-memory cache is cleared
 *      (e.g. by OOM recovery, system low-memory callback, or app restart)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * SOLUTION (4 parts — surgical, behavior-preserving):
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Part 1 (REMOVED in v4): DO NOT force b0.X2 = true
 *   - The previous version forced prescanThumbnails=true, which caused
 *     the DatabaseUpdaterService (prescan) to run continuously, showing
 *     a "Scanning media" notification and bloating app data to 4GB+.
 *   - In vanilla F-Stop with prescanThumbnails=OFF, no prescan runs and
 *     no "Scanning media" notification appears. We preserve this vanilla
 *     behavior — the user's Settings preference is respected.
 *   - The on-demand SQLite save (Part 4) makes prescan unnecessary for
 *     thumbnail persistence. Thumbnails are saved to SQLite as the user
 *     browses, not by prescan.
 *
 * Part 2: NOP all 8 automatic y1.b() caller sites
 *   - All non-Settings callers of y1.b() are in OOM catch blocks or system
 *     memory callbacks (MyApplication.onLowMemory / onTrimMemory,
 *     p.V / p.W OOM catch, e3.b.a0 / e3.b.z2 OOM catch, e0.c OOM retry,
 *     DatabaseUpdaterService$c defensive catch).
 *   - Each `invoke-virtual {...}, Lcom/fstop/photo/y1;->b()V` is replaced
 *     with `nop`, so the in-memory cache is NEVER cleared automatically.
 *   - y1.b() itself is LEFT INTACT — the user-triggered Settings button
 *     "Refresh thumbnail cache" (SettingsFragmentCache$b$b$a.run()) still
 *     calls y1.b() and clears the cache as intended.
 *
 * Part 3: Increase LRU cache size in y1.<init>()
 *   - Change y1.e from 50 to 500 entries
 *   - Allows more thumbnails to stay in memory during a single session
 *   - 500 entries at ~30KB each ≈ 15MB — reasonable memory usage
 *
 * Part 4 (THE KEY FIX): On-demand SQLite save in y1.h()
 *   - Inject call to OnDemandThumbnailSaver.saveToSQLite(path, imageId, bitmap)
 *     at the start of y1.h()
 *   - y1.h() is called every time a thumbnail is stored in the LRU cache
 *     (whether from SQLite hit, disk decode, or network)
 *   - The injected call saves the bitmap to the SQLite Thumbnail table
 *     via e3/b.Y1(imageId, path, bitmap)
 *   - This makes the SQLite cache GROW AS THE USER BROWSES, not just
 *     from prescan
 *   - On force-stop + restart, thumbnails for folders the user has
 *     visited are loaded instantly from SQLite (survive force-stop)
 *   - Y1() also sets IsProcessed=1 on the Image row, which tells prescan
 *     to SKIP that image → SPEEDS UP prescan (if prescan ever runs)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHY "Scanning media" APPEARED (and why it's now FIXED):
 * ─────────────────────────────────────────────────────────────────────────
 * The "Scanning media" notification comes from DatabaseUpdaterService
 * (the prescan pipeline). It runs as a foreground service with a
 * notification on channel "com.fstop.photo.channel.scanning_media3".
 *
 * In vanilla F-Stop with prescanThumbnails=OFF:
 *   - DatabaseUpdaterService.j() returns false (b0.X2 is false)
 *   - The prescan worker thread (DatabaseUpdaterService$c) does NOT
 *     start the foreground notification
 *   - No "Scanning media" notification appears
 *
 * The previous version of this patch (Part 1) forced b0.X2=true, which
 * made DatabaseUpdaterService.j() return true, which triggered the
 * prescan worker + "Scanning media" notification. This caused:
 *   - Continuous "Scanning media" notification
 *   - App data bloat to 4GB+ (prescan writes MicroThumbnail blobs for
 *     every image in every folder — 3000+ folders × many images each)
 *
 * By REMOVING Part 1, we restore the vanilla behavior: prescan does NOT
 * run unless the user explicitly enables "Create thumbnails in advance"
 * in Settings → Main. The on-demand SQLite save (Part 4) provides
 * thumbnail persistence WITHOUT prescan.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT THE USER WILL OBSERVE:
 * ─────────────────────────────────────────────────────────────────────────
 *   - NO "Scanning media" notification (prescan doesn't auto-run)
 *   - NO app data bloat (prescan doesn't write all thumbnails at once)
 *   - Thumbnails persist across app restarts (loaded from SQLite via Part 4)
 *   - In-memory cache (500 entries) stays loaded during normal use
 *   - Settings -> Main -> Cache -> "Refresh thumbnail cache" button works
 *   - Thumbnails for folders the user has browsed survive force-stop
 *   - The user's "Create thumbnails in advance" Settings preference is
 *     RESPECTED — if OFF, no prescan; if ON, prescan runs (user's choice)
 */
@Suppress("unused")
val persistFolderThumbnailsPatch = bytecodePatch(
    name = "Persist folder thumbnails",
    description = "Fixes thumbnail disappearance when F-Stop has 3000+ folders. " +
        "1) NOPs the 8 automatic y1.b() call sites (OOM catch blocks + system memory callbacks) " +
        "so the in-memory cache is never auto-cleared. " +
        "2) Increases LRU cache size from 50 to 500 entries. " +
        "3) THE KEY FIX: saves every on-demand-loaded thumbnail to SQLite via y1.h() → e3/b.Y1(). " +
        "The SQLite cache grows as the user browses (not from prescan), so thumbnails " +
        "for visited folders survive force-stop. " +
        "Does NOT force 'prescanThumbnails' ON — preserves vanilla behavior " +
        "(no 'Scanning media' notification, no app data bloat). " +
        "y1.b() itself is left intact so the Settings -> Main -> Cache -> " +
        "'Refresh thumbnail cache' button still works as intended.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // ═══════════════════════════════════════════════════════════════
        // Part 1: REMOVED — do NOT force b0.X2 = true
        // ═══════════════════════════════════════════════════════════════
        // The previous version forced prescanThumbnails=true here, which
        // caused the "Scanning media" notification and app data bloat.
        // We now respect the user's Settings preference. The on-demand
        // SQLite save (Part 4 below) provides thumbnail persistence
        // without needing prescan.

        // ═══════════════════════════════════════════════════════════════
        // Part 2: NOP all 8 automatic y1.b() caller sites
        // ═══════════════════════════════════════════════════════════════
        // Each of these methods calls y1.b() in an automatic context
        // (OOM catch block or system memory callback). We replace the
        // invoke-virtual call with `nop` so the in-memory cache survives.
        //
        // y1.b() itself is left intact — the user-triggered Settings button
        // (SettingsFragmentCache$b$b$a.run()) still calls y1.b() and works.
        val automaticCallerFingerprints = listOf(
            MyApplicationOnLowMemoryFingerprint,
            MyApplicationOnTrimMemoryFingerprint,
            FolderThumbnailGenVFingerprint,
            FolderThumbnailGenWFingerprint,
            BitmapLoaderA0Fingerprint,
            CollectionManagerZ2Fingerprint,
            DefensiveRetryC0Fingerprint,
            DatabaseUpdaterServiceCRunFingerprint,
        )

        automaticCallerFingerprints.forEach { fingerprint ->
            fingerprint.method.apply {
                val impl = implementation!!
                val callIndex = impl.instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        it is ReferenceInstruction &&
                        it.reference.toString() == "Lcom/fstop/photo/y1;->b()V"
                }
                if (callIndex == -1) {
                    throw PatchException(
                        "No invoke-virtual y1.b()V in ${fingerprint.definingClass} " +
                            "(expected 1, found 0)"
                    )
                }
                // Replace invoke-virtual with nop. We use replaceInstruction
                // (not removeInstruction) to avoid shifting instruction
                // indices, which would break try-catch handler offsets.
                replaceInstruction(callIndex, "nop")
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // Part 3: y1.<init>() — cache 500
        // ═══════════════════════════════════════════════════════════════
        // y1.<init>() creates 4 LRU caches using the size from y1.e.
        // Default y1.e = 50 (0x32). We set it to 500 (0x1F4) for 3000+ folders.
        ThumbnailManagerInitFingerprint.method.apply {
            val impl = implementation!!
            if (impl.instructions.first().opcode != Opcode.INVOKE_DIRECT)
                throw PatchException("Unexpected first instruction in y1.<init>()")
            addInstructions(1, """
                const/16 v0, 0x1f4
                sput v0, Lcom/fstop/photo/y1;->e:I
            """)
        }

        // ═══════════════════════════════════════════════════════════════
        // Part 4 (THE KEY FIX): y1.h() — on-demand SQLite save
        // ═══════════════════════════════════════════════════════════════
        // y1.h(String path, int imageId, Bitmap, boolean, int cacheSlot) is
        // called every time a thumbnail is stored in the in-memory LRU cache.
        // This happens for BOTH SQLite-hit loads (e3/b.a0 returned a bitmap)
        // and disk-decode loads (z1 decoded from the original file).
        //
        // We inject a call to OnDemandThumbnailSaver.saveToSQLite(path, imageId, bitmap)
        // at the very start of y1.h(). The helper:
        //   1. Checks for null path / zero imageId / null bitmap (skips if invalid)
        //   2. Gets b0.p (the e3/b DB helper singleton)
        //   3. Calls e3/b.Y1(imageId, path, bitmap) which:
        //      a. Scales the bitmap to a MicroThumbnail blob
        //      b. INSERT or REPLACE INTO Thumbnail (keyed by FullPath)
        //      c. UPDATE Image SET IsProcessed = 1 WHERE _ID = imageId
        //
        // Effect:
        //   - The SQLite cache grows as the user browses, not just from prescan
        //   - Thumbnails for visited folders survive force-stop
        //   - Prescan (if enabled by user) skips images with IsProcessed=1
        //
        // Performance:
        //   - Called on the async loader thread (not UI thread)
        //   - Adds ~5-20ms per thumbnail (bitmap scaling + SQLite I/O)
        //   - Redundant for SQLite-hit case (re-saves what was just read),
        //     but the data is identical so the UPDATE is a no-op in practice
        //   - Acceptable for on-demand loads
        //
        // Register usage:
        //   y1.h() has .locals 3 (v0, v1, v2 available)
        //   We inject at index 0, before any original code uses v0.
        //   The injected invoke-static uses p1, p2, p3 (method parameters).
        //   No register conflict — the original code's iget-object v0 runs
        //   AFTER our injection and overwrites v0, which is fine.
        //
        // NOTE: The Thumbnail cache logger patch ALSO injects at index 0 of
        // y1.h(). If both patches are applied, the logger's injection runs
        // first (it's applied second by morphe-cli), then our injection,
        // then the original method body. Both hooks work independently.
        val SAVER_CLASS = "Lapp/morphe/extension/fstop/OnDemandThumbnailSaver;"
        ThumbnailManagerStoreBitmapFingerprint.method.apply {
            val impl = implementation!!
            addInstructions(0, """
                invoke-static {p1, p2, p3}, $SAVER_CLASS->saveToSQLite(Ljava/lang/String;ILandroid/graphics/Bitmap;)V
            """.trimIndent())
        }
    }
}
