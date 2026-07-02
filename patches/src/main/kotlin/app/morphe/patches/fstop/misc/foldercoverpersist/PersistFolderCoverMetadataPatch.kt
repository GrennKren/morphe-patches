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
    description = "Makes folder cover thumbnails load instantly on restart, " +
        "just like image thumbnails do. The first time you view a folder its " +
        "cover is remembered, so every restart after that shows all your " +
        "folder covers immediately instead of loading them one by one.",
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
