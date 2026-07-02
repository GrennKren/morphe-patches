/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.fastdelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP

/**
 * Patch: Fast batch delete — instant batch deletion for large selections.
 *
 * Injects a fast-path check at the start of `e3.b.x(ArrayList)` and
 * `e3.b.J2(ArrayList)`. The fast path (FastDeleteHelper.java) replaces
 * the per-file MediaStore lookup and per-file delete with a SINGLE
 * batched SQL `WHERE _data IN (?,?,...,?)` call per MediaStore volume URI.
 */
@Suppress("unused")
val fastDeletePatch = bytecodePatch(
    name = "Fast batch delete",
    description = "Speeds up deleting many selected files at once. " +
        "Stock F-Stop deletes files one by one, making a separate " +
        "MediaStore lookup and delete call for each file — slow when " +
        "selecting 30, 100, 500+ images. This patch batches those " +
        "MediaStore calls into a single SQL delete per storage volume, " +
        "so deletion feels nearly instant regardless of selection size. " +
        "Works for both direct delete (Recycle Bin off) and move-to-" +
        "Recycle-Bin (Recycle Bin on), as well as deleting from inside " +
        "the Recycle Bin.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/FastDeleteHelper;"

        // Patch 1: e3.b.x(ArrayList) — direct delete (Recycle Bin OFF)
        DeleteFilesFingerprint.method.apply {
            implementation
                ?: throw PatchException("DeleteFilesFingerprint: no implementation")
            addInstructions(
                0,
                """
                    invoke-static {p0, p1}, $EXTENSION_CLASS->fastDelete(Le3/b;Ljava/util/ArrayList;)Z
                    move-result v0
                    if-eqz v0, :morphe_fast_delete_original
                    const/4 v0, 0x0
                    return-object v0
                    :morphe_fast_delete_original
                """,
            )
        }

        // Patch 2: e3.b.J2(ArrayList) — move to recycle bin (Recycle Bin ON)
        MoveToRecycleBinFingerprint.method.apply {
            implementation
                ?: throw PatchException("MoveToRecycleBinFingerprint: no implementation")
            addInstructions(
                0,
                """
                    invoke-static {p0, p1}, $EXTENSION_CLASS->fastMoveToRecycleBin(Le3/b;Ljava/util/ArrayList;)Z
                    move-result v0
                    if-eqz v0, :morphe_fast_move_original
                    const/4 v0, 0x0
                    return-object v0
                    :morphe_fast_move_original
                """,
            )
        }
    }
}
