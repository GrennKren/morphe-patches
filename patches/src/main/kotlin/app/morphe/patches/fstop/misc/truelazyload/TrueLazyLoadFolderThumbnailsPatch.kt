/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.truelazyload

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction


/**
 * Patch: True Lazy-Load Folder Thumbnails
 *
 * PROBLEM
 * -------
 * F-Stop's "Create thumbnails in advance" preference (key `prescanThumbnails`,
 * default OFF) is supposed to enable lazy-load mode: thumbnails are created
 * on-demand when displayed, not in advance. The user reports that this lazy
 * load is not "true" lazy load — when opening the app, folder cover thumbnails
 * get generated sequentially from top to bottom. When the user scrolls quickly
 * to the bottom, those bottom thumbnails load very slowly because the
 * generation queue is still busy processing items the user has already
 * scrolled past.
 *
 * ROOT CAUSE (decompiled from com.fstop.photo 5.5.484)
 * ----------------------------------------------------
 * The folder cover thumbnail pipeline has TWO async queues, both running on
 * dedicated HandlerThreads:
 *
 * 1. `x1` ("ThumbnailDataSetter" HandlerThread) — resolves the folder's cover
 *    image path by querying SQLite (`SELECT * FROM Image WHERE Folder=? ORDER
 *    BY <order> LIMIT 4` — first image becomes the cover).
 *
 * 2. `z1` ("ThumbnailReader" HandlerThread) — reads the thumbnail bitmap for
 *    the resolved cover path from SQLite (`SELECT MicroThumbnail FROM
 *    Thumbnail WHERE FullPath=?`), decodes it, and stores it in `y1`'s
 *    in-memory cache.
 *
 * `z1` is correctly LIFO (inserts at HEAD via `add(0, bVar)`), so the most
 * recently requested thumbnail is loaded first. Good.
 *
 * `x1`, however, is FIFO. It uses `ArrayList.add(bVar)` (append to END) and
 * `ArrayList.get(0)/remove(0)` (take from HEAD). As the user scrolls, every
 * visible folder item's metadata request is appended to the queue in
 * submission order. Top items get their cover path resolved first; bottom
 * items wait at the tail.
 *
 * Because `z1` cannot load a thumbnail for an item whose cover path is not
 * yet known, the bottleneck is `x1`. Even though `z1` would prioritize the
 * current viewport, it has nothing to load for bottom items until `x1`
 * catches up. Result: the user sees top thumbnails first and waits a long
 * time for bottom thumbnails when scrolling.
 *
 * SOLUTION
 * --------
 * Change `x1.a(Lc3/e;)V` to insert at HEAD (LIFO) instead of END (FIFO),
 * matching `z1`'s behavior. After this change, both queues are LIFO, so the
 * most recently scrolled-to viewport items get their cover paths resolved
 * first AND their bitmaps loaded first. This is true viewport-prioritized
 * lazy load.
 *
 * SMALI CHANGE (com/fstop/photo/x1.smali, method `a(Lc3/e;)V`)
 * ------------------------------------------------------------
 * Stock instruction at offset N (smali line 256 in 5.5.484):
 *
 *     invoke-virtual {p1, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
 *
 * Replacement (2 instructions):
 *
 *     const/4 v<free>, 0x0
 *     invoke-virtual {p1, v<free>, v1}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
 *
 * The `<free>` register is determined dynamically by `findFreeRegister()`
 * from `app.morphe.util`. For F-Stop 5.5.484 it resolves to v4 (provably
 * dead at this program point — only used at smali lines 235 and 239 in the
 * method, never after).
 *
 * The original `add(Ljava/lang/Object;)Z` returns a boolean (Z) which is
 * discarded — the next instruction (line 261) is `iget-object p1, ...`, NOT
 * `move-result`. So swapping to the void-returning `add(ILjava/lang/Object;)V`
 * is safe; no `move-result` to clean up. The patch asserts this.
 *
 * WHY NOT ALSO TOUCH `z1`?
 * ------------------------
 * `z1.a()` already does `add(0, bVar)` (LIFO) at smali line 353. No change
 * needed. The patch is intentionally minimal: only `x1` is touched.
 *
 * WHY NOT CANCEL OFF-SCREEN ITEMS?
 * --------------------------------
 * A more aggressive fix would cancel queued requests for items the user has
 * scrolled past. This is risky: the dedup HashMap (`x1$d.f9486b`) would have
 * to be cleaned up atomically, and the `b0.H.b()` cache (`com.fstop.photo.c`)
 * would need invalidation hooks. The LIFO change alone gives ~90% of the
 * perceived benefit (current viewport is always processed first) with ~10% of
 * the risk. Off-screen items still get processed eventually — they just
 * don't block the current viewport.
 *
 * SAFETY
 * ------
 * - The `x1$d.f9485a` ArrayList is accessed under `monitor-enter`/`monitor-exit`
 *   on the ArrayList object itself (smali line 215 `monitor-enter v2`). My
 *   change only affects the order of insertion, which is already atomic under
 *   the monitor. No new race conditions are introduced.
 * - The dedup HashMap (`x1$d.f9486b`) is unchanged. Duplicate prevention still
 *   works — if the same folder item is submitted twice, only the first
 *   submission is queued. (Note: with LIFO, this means an item that the user
 *   scrolls past and back to won't jump to the head of the queue — it stays at
 *   its original position. This is acceptable; the alternative would require
 *   removing-and-reinserting, which adds complexity for marginal benefit.)
 * - The `x1.b()` drain loop is unchanged. It still takes from HEAD, processes
 *   the item, and broadcasts "com.fstop.photo.thumbnailDataSetterLoadedItem"
 *   when done. The receiver in `ListOfSomethingActivity` calls `F4()` →
 *   `invalidate()`, which triggers a redraw. The redraw calls `l0()` for
 *   visible items; for items already processed (`w1.f9465h == TRUE`), `l0()`
 *   is a no-op. For items not yet processed, `l0()` calls `x1.a()` again —
 *   but the dedup map prevents re-queueing. So the redraw loop terminates.
 */
@Suppress("unused")
val trueLazyLoadFolderThumbnailsPatch = bytecodePatch(
    name = "True lazy load folder thumbnails",
    description = "Makes F-Stop's folder cover thumbnail loading truly " +
        "viewport-prioritized. Stock behavior: the 'ThumbnailDataSetter' " +
        "queue (Lcom/fstop/photo/x1;) processes folder cover path lookups " +
        "in FIFO order, so when you open the app and scroll down fast, top " +
        "folders get their cover paths resolved first and bottom folders " +
        "have to wait. Combined with the downstream 'ThumbnailReader' " +
        "queue (Lcom/fstop/photo/z1;) which is already LIFO, this means " +
        "bottom thumbnails cannot be loaded until x1 catches up — making " +
        "scroll-to-bottom feel very slow. This patch changes x1.a(Lc3/e;)V " +
        "to insert at HEAD (LIFO) instead of END (FIFO), matching z1's " +
        "behavior. After this patch, both queues are LIFO: the most " +
        "recently scrolled-to viewport items get their cover paths " +
        "resolved first AND their bitmaps loaded first. Works alongside " +
        "the 'Create thumbnails in advance' setting (prescanThumbnails) — " +
        "if prescan is OFF (lazy load), this patch makes the lazy load " +
        "actually viewport-aware. If prescan is ON, this patch has no " +
        "effect (because prescan pre-generates thumbnails in advance and " +
        "x1 is not on the hot path).",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // ═══════════════════════════════════════════════════════════════
        // Locate the queue-insert call inside x1.a(Lc3/e;)V
        // ═══════════════════════════════════════════════════════════════
        X1EnqueueFolderFingerprint.method.apply {
            val impl = implementation!!
            val instructions = impl.instructions.toList()

            // Find the unique `ArrayList->add(Ljava/lang/Object;)Z` call.
            // There should be exactly one in this method — the queue append.
            val addIdx = instructions.indexOfFirst { instr ->
                instr.opcode == Opcode.INVOKE_VIRTUAL &&
                    instr is ReferenceInstruction &&
                    instr.reference.toString() ==
                    "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"
            }
            if (addIdx == -1) {
                throw PatchException(
                    "Could not find ArrayList.add(Ljava/lang/Object;)Z call in " +
                        "x1.a(Lc3/e;)V. The queue-insert instruction may have been " +
                        "refactored; manual analysis required."
                )
            }

            // Sanity: ensure there is only one such call.
            val secondIdx = instructions.drop(addIdx + 1).indexOfFirst { instr ->
                instr.opcode == Opcode.INVOKE_VIRTUAL &&
                    instr is ReferenceInstruction &&
                    instr.reference.toString() ==
                    "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"
            }
            if (secondIdx != -1) {
                throw PatchException(
                    "Found multiple ArrayList.add(Object) calls in x1.a(Lc3/e;)V " +
                        "(at smali indices $addIdx and ${addIdx + 1 + secondIdx}) — " +
                        "expected exactly one. The method may have changed; " +
                        "manual analysis required."
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // Verify the next instruction is NOT a move-result.
            //
            // The original `add(Object)Z` returns a boolean. The Java source
            // discards the return value, and the stock smali does not emit a
            // `move-result` after the invoke. We verify this so we know it's
            // safe to swap to the void-returning `add(int, Object)V` without
            // leaving a dangling move-result.
            // ═══════════════════════════════════════════════════════════════
            val nextInstr = instructions.getOrNull(addIdx + 1)
            if (nextInstr != null && nextInstr.opcode == Opcode.MOVE_RESULT) {
                throw PatchException(
                    "Unexpected move-result after ArrayList.add(Object) in x1.a(Lc3/e;)V " +
                        "(smali index ${addIdx + 1}). The patch's void-return swap would " +
                        "leave a dangling move-result. Manual analysis required."
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // Extract the register operands of the original invoke-virtual.
            //
            // For `invoke-virtual {p1, v1}, ...->add(Ljava/lang/Object;)Z`:
            //   registerC = p1 (the ArrayList)
            //   registerD = v1 (the b element to add)
            //
            // We need a 3rd register to hold the int constant 0. We use
            // `findFreeRegister` from app.morphe.util to pick a safe one.
            // ═══════════════════════════════════════════════════════════════
            val invokeInstr = instructions[addIdx] as FiveRegisterInstruction
            val arraylistReg = invokeInstr.registerC
            val elementReg = invokeInstr.registerD

            val indexReg = findFreeRegister(
                addIdx,
                arraylistReg,
                elementReg,
            )

            // Sanity: ensure indexReg is not one of the operands.
            if (indexReg == arraylistReg || indexReg == elementReg) {
                throw PatchException(
                    "Internal error: findFreeRegister returned v$indexReg which " +
                        "collides with an operand register " +
                        "(ArrayList=v$arraylistReg, element=v$elementReg). " +
                        "Manual fix required."
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // Apply the change:
            //   1. Insert `const/4 v<indexReg>, 0x0` BEFORE the original invoke
            //      (this shifts the original invoke to index addIdx+1).
            //   2. Replace the invoke at addIdx+1 with the new signature
            //      `add(ILjava/lang/Object;)V` using 3 registers.
            // ═══════════════════════════════════════════════════════════════
            addInstructions(addIdx, """
                const/4 v$indexReg, 0x0
            """.trimIndent())

            replaceInstruction(addIdx + 1, """
                invoke-virtual {v$arraylistReg, v$indexReg, v$elementReg}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
            """.trimIndent())
        }
    }
}
