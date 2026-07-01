/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.foldercoverpersist

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val persistFolderCoverMetadataPatch = bytecodePatch(
    name = "Persist folder cover metadata",
    description = "Makes folder cover thumbnails load INSTANTLY from cache " +
        "on restart, exactly like image thumbnails. ROOT CAUSE: image " +
        "thumbnails survive force-stop via SQLite Thumbnail table. Folder " +
        "covers have NO on-disk cache — c.a HashMap is cleared on force-stop, " +
        "and e3.b.O0() re-runs the slow LIMIT 4 query per folder. F-Stop's " +
        "FolderData.ThumbnailImageId column was meant to cache this, but " +
        "is ONLY populated by manual 'Set as folder cover' (z3) — the folder " +
        "scanner does NOT create FolderData rows. FIX: replace O0() in " +
        "c.b() with FolderCoverResolver. FAST PATH: indexed JOIN query " +
        "(FolderData.ThumbnailImageId > 0) → skip O0(), ~1ms. SLOW PATH: " +
        "O0() then INSERT OR REPLACE into FolderData (creates row if missing " +
        "— this is the v3 bug: UPDATE was a no-op on missing rows). Works " +
        "with prescanThumbnails ON or OFF.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        val RESOLVER_CLASS = "Lapp/morphe/extension/fstop/FolderCoverResolver;"

        CoverResolverBFingerprint.method.apply {
            val impl = implementation!!
            val instructions = impl.instructions.toList()

            val o0CallIndex = instructions.indexOfFirst { instr ->
                instr.opcode == Opcode.INVOKE_VIRTUAL &&
                    instr is ReferenceInstruction &&
                    instr.reference.toString() == "Le3/b;->O0(Lc3/e;)V"
            }
            if (o0CallIndex == -1) {
                throw PatchException("Could not find O0() call in c.b()")
            }

            val sgetIndex = o0CallIndex - 1
            val sgetInstr = instructions.getOrNull(sgetIndex)
            if (sgetInstr == null ||
                sgetInstr.opcode != Opcode.SGET_OBJECT ||
                sgetInstr !is ReferenceInstruction ||
                sgetInstr.reference.toString() != "Lcom/fstop/photo/b0;->p:Le3/b;"
            ) {
                throw PatchException("Expected sget-object b0.p before O0() call")
            }

            val p1Register = impl.registerCount - 1

            replaceInstruction(sgetIndex, "nop")
            replaceInstruction(o0CallIndex,
                "invoke-static {v$p1Register}, " +
                    "$RESOLVER_CLASS->resolveCover(Lc3/e;)V"
            )
        }
    }
}
