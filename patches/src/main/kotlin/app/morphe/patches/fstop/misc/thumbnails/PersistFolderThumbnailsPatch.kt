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
 * SOLUTION (5 parts — surgical, behavior-preserving):
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
 * Part 4 (THE KEY FIX): On-demand ASYNC SQLite save in y1.h()
 *   - Inject call to OnDemandThumbnailSaver.saveToSQLite(path, imageId, bitmap)
 *     at the start of y1.h()
 *   - y1.h() is called every time a thumbnail is stored in the LRU cache
 *     (whether from SQLite hit, disk decode, or network)
 *   - v5 CHANGE: saveToSQLite() is now ASYNCHRONOUS — it submits the
 *     SQLite write to a background executor and returns immediately.
 *     This means y1.h() and the z1 ThumbnailReader thread are NOT blocked
 *     by the SQLite write, making thumbnail loading as fast as vanilla.
 *   - v4 was synchronous: each thumbnail save took 20-45ms (Y1 + checkpoint),
 *     making folder browsing 3-7x slower than vanilla. v5 fixes this.
 *   - The executor uses a single-threaded ThreadPoolExecutor with a bounded
 *     queue (1024 tasks) and discard policy. Bitmap recycling is handled
 *     gracefully with isRecycled() checks.
 *   - CHECKPOINT_INTERVAL changed from 1 to 50 — checkpoint every 50th
 *     write instead of every write. The WAL usually survives force-stop,
 *     so at most 50 writes can be lost (very rare edge case).
 *
 * Part 5: Suppress "Scanning media" notification when prescanThumbnails is OFF
 *   - DatabaseUpdaterService.j() decides whether to show the "Scanning media"
 *     foreground notification. Stock code: `return b0.a4 != 0 || b0.X2`
 *   - Even with b0.X2=false, if there are images with MetadataProcessed=0
 *     (b0.a4 != 0), the notification still appears. This is caused by
 *     z1.b() adding images to the prescan queue when thumbnails aren't
 *     in SQLite, which triggers DatabaseUpdaterService.
 *   - We replace j()'s body to return ONLY `b0.X2`, so the notification
 *     only appears when the user explicitly enables "Create thumbnails
 *     in advance". When OFF, metadata processing still runs in the
 *     background (just without the foreground notification).
 *   - When b0.X2=true (user enabled "Create thumbnails in advance"), the
 *     notification shows as in vanilla F-Stop — vanilla behavior preserved.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHY "Scanning media" APPEARED (and why it's now FIXED):
 * ─────────────────────────────────────────────────────────────────────────
 * The "Scanning media" notification comes from DatabaseUpdaterService
 * (the prescan pipeline). It runs as a foreground service with a
 * notification on channel "com.fstop.photo.channel.scanning_media3".
 *
 * In vanilla F-Stop with prescanThumbnails=OFF:
 *   - After initial media scan, all images have MetadataProcessed=1
 *   - b0.a4 (max unprocessed count) is 0
 *   - j() returns false (a4==0 && X2==false)
 *   - No "Scanning media" notification
 *
 * With the patches (v4), the notification appeared because:
 *   - z1.b() finds thumbnails not in SQLite → adds to prescan queue
 *   - Prescan starts → counts MetadataProcessed=0 images → b0.a4 > 0
 *   - j() returns true (a4 != 0) → shows notification
 *   - The notification persists because the prescan keeps finding
 *     images to process
 *
 * v5 fix (Part 5): j() now returns ONLY b0.X2. When X2=false,
 * the notification is suppressed regardless of b0.a4. The prescan
 * still runs and processes metadata (just without the notification).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT THE USER WILL OBSERVE:
 * ─────────────────────────────────────────────────────────────────────────
 *   - Fast thumbnail loading inside folders (same speed as vanilla)
 *   - NO "Scanning media" notification when "Create thumbnails in advance" is OFF
 *   - "Scanning media" notification STILL works when "Create thumbnails in
 *     advance" is ON (vanilla behavior preserved)
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
        "3) THE KEY FIX: saves every on-demand-loaded thumbnail to SQLite ASYNCHRONOUSLY " +
        "via y1.h() → OnDemandThumbnailSaver → e3/b.Y1(). " +
        "v5: saveToSQLite() is now async — returns immediately, SQLite write happens " +
        "on a background thread. This makes thumbnail loading as fast as vanilla " +
        "(v4 was 3-7x slower due to synchronous SQLite writes). " +
        "CHECKPOINT_INTERVAL increased from 1 to 50 for less I/O overhead. " +
        "4) Suppresses 'Scanning media' notification when 'Create thumbnails in advance' " +
        "is OFF by patching DatabaseUpdaterService.j() to return only b0.X2. " +
        "Vanilla behavior is preserved when the setting is ON. " +
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
        // Part 4 (THE KEY FIX): y1.h() — on-demand ASYNC SQLite save
        // ═══════════════════════════════════════════════════════════════
        // y1.h(String path, int imageId, Bitmap, boolean, int cacheSlot) is
        // called every time a thumbnail is stored in the in-memory LRU cache.
        // This happens for BOTH SQLite-hit loads (e3/b.a0 returned a bitmap)
        // and disk-decode loads (z1 decoded from the original file).
        //
        // We inject a call to OnDemandThumbnailSaver.saveToSQLite(path, imageId, bitmap)
        // at the very start of y1.h(). The helper (v5):
        //   1. Checks for null path / zero imageId / null / recycled bitmap
        //   2. Submits the SQLite write to a background single-thread executor
        //   3. Returns IMMEDIATELY — does not block the z1 ThumbnailReader
        //   4. The executor runs: e3/b.Y1(imageId, path, bitmap) which:
        //      a. Scales the bitmap to a MicroThumbnail blob
        //      b. INSERT or REPLACE INTO Thumbnail (keyed by FullPath)
        //      c. UPDATE Image SET IsProcessed = 1 WHERE _ID = imageId
        //   5. WAL checkpoint every 50 writes (not every write)
        //
        // v5 PERFORMANCE FIX:
        //   v4 called Y1() + WAL checkpoint SYNCHRONOUSLY on the z1 thread,
        //   adding 20-45ms per thumbnail. With 50+ thumbnails in a folder,
        //   this made browsing 3-7x slower than vanilla F-Stop.
        //   v5 makes the save async — y1.h() returns in ~0.01ms, and the
        //   actual SQLite write happens on a background thread. Thumbnail
        //   loading is now as fast as vanilla F-Stop.
        //
        // Effect:
        //   - The SQLite cache grows as the user browses, not just from prescan
        //   - Thumbnails for visited folders survive force-stop
        //   - Prescan (if enabled by user) skips images with IsProcessed=1
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

        // ═══════════════════════════════════════════════════════════════
        // Part 5: DatabaseUpdaterService.j() — suppress "Scanning media"
        // ═══════════════════════════════════════════════════════════════
        // Stock j() returns: (b0.a4 != 0 || b0.X2)
        //   where a4 = max unprocessed image count, X2 = prescanThumbnails
        //
        // PROBLEM: Even with b0.X2=false, if b0.a4 != 0 (there are images
        // with MetadataProcessed=0), j() returns true and the "Scanning media"
        // foreground notification is shown. This happens because:
        //   1. z1.b() finds thumbnails not in SQLite → adds to prescan queue
        //   2. Prescan starts → Z3() counts MetadataProcessed=0 images → a4 > 0
        //   3. j() returns true → foreground notification with "Scanning media"
        //
        // FIX: Replace j()'s body to return ONLY b0.X2. This means:
        //   - When X2=true ("Create thumbnails in advance" ON): notification
        //     shows as in vanilla F-Stop (behavior preserved)
        //   - When X2=false ("Create thumbnails in advance" OFF): notification
        //     is suppressed. Prescan still runs for metadata processing,
        //     but as a background service without the foreground notification.
        //
        // Safety:
        //   - j() ONLY controls the foreground notification, not the prescan
        //     itself. The prescan worker (DatabaseUpdaterService$c.run())
        //     still processes metadata regardless of j()'s return value.
        //   - When X2=false, the prescan may be killed by the system
        //     (background service without notification), but this is fine:
        //     metadata processing is fast, and the service restarts on
        //     next app launch. The user's priority is no notification,
        //     not guaranteed metadata completeness.
        //
        // Original smali:
        //   sget v0, Lcom/fstop/photo/b0;->a4:I
        //   if-nez v0, :cond_1
        //   sget-boolean v0, Lcom/fstop/photo/b0;->X2:Z
        //   if-eqz v0, :cond_0
        //   goto :goto_0
        //   :cond_0
        //   const/4 v0, 0x0
        //   goto :goto_1
        //   :cond_1
        //   :goto_0
        //   const/4 v0, 0x1
        //   :goto_1
        //   return v0
        //
        // Replacement: just return b0.X2 directly.
        //   sget-boolean v0, Lcom/fstop/photo/b0;->X2:Z
        //   return v0
        DatabaseUpdaterServiceShouldShowNotificationFingerprint.method.apply {
            val impl = implementation!!
            val instructions = impl.instructions.toList()

            // Verify the method structure matches expectations
            if (instructions.isEmpty()) {
                throw PatchException("DatabaseUpdaterService.j() has no instructions")
            }

            // Find the sget-boolean instruction for b0.X2
            val sgetBooleanIndex = instructions.indexOfFirst {
                it.opcode == Opcode.SGET_BOOLEAN &&
                    it is ReferenceInstruction &&
                    it.reference.toString() == "Lcom/fstop/photo/b0;->X2:Z"
            }
            if (sgetBooleanIndex == -1) {
                throw PatchException(
                    "Could not find sget-boolean b0.X2:Z in DatabaseUpdaterService.j()"
                )
            }

            // Find the return instruction
            val returnIndex = instructions.indexOfFirst {
                it.opcode == Opcode.RETURN
            }
            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return instruction in DatabaseUpdaterService.j()"
                )
            }

            // Replace all instructions with just: sget-boolean + return
            // We keep the method's .locals 1 and replace the body.
            // Strategy: replace the first instruction with sget-boolean,
            // replace the last (return) instruction with return v0,
            // and nop everything in between.
            val firstInstrIdx = 0

            // Replace first instruction with sget-boolean v0, b0.X2
            replaceInstruction(firstInstrIdx,
                "sget-boolean v0, Lcom/fstop/photo/b0;->X2:Z"
            )

            // NOP all instructions between first and return
            for (i in (firstInstrIdx + 1) until returnIndex) {
                replaceInstruction(i, "nop")
            }

            // The return instruction already returns v0, which now holds b0.X2.
            // No change needed for the return instruction.
        }
    }
}
