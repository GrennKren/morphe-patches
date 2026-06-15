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
 * Patch to fix thumbnail disappearance when F-Stop has 3000+ folders.
 *
 * PROBLEM:
 * When "Create Thumbnails in advance" is disabled (b0.X2 = false), thumbnails
 * are loaded on-demand into an LRU memory cache (default size 50 entries).
 * With 3000+ folders:
 *   1. The LRU cache (50 entries) is too small to hold all thumbnails
 *   2. On app refresh/restart, y1.b() clears ALL caches
 *   3. On-demand thumbnails are NOT saved to SQLite (only prescan does that)
 *   4. Result: all thumbnails disappear after restart
 *
 * With fewer folders, the prescan pipeline or smaller cache was sufficient,
 * but with 3000+ folders the system breaks down.
 *
 * SOLUTION (3 parts — minimal and safe):
 *
 * Part 1: Force b0.X2 = true in p.R2()
 *   - Overrides the "Create Thumbnails in advance" preference to always ON
 *   - This enables the prescan pipeline which saves thumbnails to SQLite
 *   - On restart, thumbnails are loaded from SQLite (instant, persistent)
 *
 * Part 2: Neutralize y1.b() — prevent cache clearing
 *   - Replace body with: force X2=true + return-void
 *   - Preserves in-memory LRU cache during the session
 *   - Re-forces X2=true as a safety measure
 *
 * Part 3: Increase LRU cache size in y1.<init>()
 *   - Change y1.e from 50 to 500 entries
 *   - Allows more thumbnails to stay in memory during a single session
 *   - 500 entries at ~30KB each ≈ 15MB — reasonable memory usage
 *   - Also force X2=true as a safety measure
 *
 * WHY THIS IS SIMPLER THAN PREVIOUS ATTEMPTS:
 * The previous patch (7 parts, commits 4bc8dfb5 to 22b4a2c7) tried to inject
 * custom SQLite read/write code directly into y1.e() and y1.h() bytecode.
 * This caused VerifyError and crashes due to register conflicts, label
 * resolution issues, and null pointer exceptions.
 *
 * This new approach works WITH the app's existing infrastructure instead of
 * fighting against it:
 *   - The prescan pipeline already knows how to save to SQLite correctly
 *   - We just force it ON and prevent cache clearing
 *   - No custom bytecode injection for SQLite operations
 *   - No risk of VerifyError from complex register manipulation
 */
@Suppress("unused")
val persistFolderThumbnailsPatch = bytecodePatch(
    name = "Persist folder thumbnails",
    description = "Fixes thumbnail disappearance when F-Stop has 3000+ folders. " +
        "1) Forces 'prescanThumbnails' preference to always ON (enables SQLite persistence). " +
        "2) Neutralizes y1.b() cache clearing. " +
        "3) Increases LRU cache size from 50 to 500 entries. " +
        "Thumbnails are persisted to SQLite by the existing prescan pipeline " +
        "and survive app restarts.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // ═══════════════════════════════════════════════════════════════
        // Part 1: Patch p.R2() — force X2=true (prescanThumbnails always ON)
        // ═══════════════════════════════════════════════════════════════
        // p.R2() loads all SharedPreferences and stores them in b0 fields.
        // The X2 field (prescanThumbnails) controls whether thumbnails are
        // pre-scanned and saved to SQLite. We force it to always true.
        PrescanThumbnailsPrefFingerprint.method.apply {
            val impl = implementation!!
            val sputX2Index = impl.instructions.indexOfFirst {
                it.opcode == Opcode.SPUT_BOOLEAN &&
                    it is ReferenceInstruction &&
                    it.reference.toString() == "Lcom/fstop/photo/b0;->X2:Z"
            }
            if (sputX2Index == -1) throw PatchException("No sput-boolean X2 in p.R2()")

            var moveResultIndex = -1
            for (i in (sputX2Index - 1) downTo 0) {
                if (impl.instructions[i].opcode == Opcode.MOVE_RESULT) {
                    moveResultIndex = i; break
                }
            }
            if (moveResultIndex == -1) throw PatchException("No move-result before sput-boolean X2")

            replaceInstruction(moveResultIndex, "const/4 v0, 0x1")
        }

        // ═══════════════════════════════════════════════════════════════
        // Part 2: Replace y1.b() — force X2=true + return-void
        // ═══════════════════════════════════════════════════════════════
        // y1.b() normally clears ALL LRU caches by calling c(1), c(2), c(4), c(8).
        // We replace it with a no-op that also forces X2=true.
        ThumbnailClearCacheFingerprint.method.apply {
            val impl = implementation!!
            val idx = impl.instructions.indexOfFirst { it.opcode != Opcode.RETURN_VOID }
            if (idx == -1) throw PatchException("y1.b() already no-op")
            replaceInstruction(idx, "const/4 v0, 0x1")
            replaceInstruction(idx + 1, "sput-boolean v0, Lcom/fstop/photo/b0;->X2:Z")
            replaceInstruction(idx + 2, "return-void")
        }

        // ═══════════════════════════════════════════════════════════════
        // Part 3: y1.<init>() — cache 500 + force X2=true
        // ═══════════════════════════════════════════════════════════════
        // y1.<init>() creates 4 LRU caches using the size from y1.e.
        // Default y1.e = 50 (0x32). We set it to 500 (0x1F4) for 3000+ folders.
        // Also force X2=true as a safety measure.
        ThumbnailManagerInitFingerprint.method.apply {
            val impl = implementation!!
            if (impl.instructions.first().opcode != Opcode.INVOKE_DIRECT)
                throw PatchException("Unexpected first instruction in y1.<init>()")
            addInstructions(1, """
                const/16 v0, 0x1f4
                sput v0, Lcom/fstop/photo/y1;->e:I
                const/4 v0, 0x1
                sput-boolean v0, Lcom/fstop/photo/b0;->X2:Z
            """)
        }
    }
}
