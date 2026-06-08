/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.openexternally

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to add missing MIME types to FX Explorer's MIME map.
 *
 * Problem:
 * FX Explorer's Lab/k; class has a static MIME type map that maps file extensions
 * to MIME types. Many common extensions are missing (.md, .yaml, .toml, etc.).
 * When an extension is not in the map, clicking a file with that extension shows
 * the "Open As" dialog asking the user to choose a file type, instead of opening
 * it directly with the appropriate external application.
 *
 * Solution:
 * Inject additional Map.put() calls into the <clinit> method of Lab/k;,
 * right before the Collections.unmodifiableMap() call that seals the map.
 * This adds missing extension-to-MIME mappings so files like .md, .yaml, etc.
 * can be opened directly.
 *
 * Note: This patch only handles MIME type injection. The "Open With" dialog
 * bypass is handled by a separate patch that modifies the file opening flow.
 */
@Suppress("unused")
val openExternallyPatch = bytecodePatch(
    name = "Open files externally",
    description = "Adds missing MIME types for .md, .yaml, .toml and other file extensions, " +
        "so files with these extensions can be opened directly with external applications " +
        "instead of showing the 'Open As' dialog.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        // Inject missing MIME types into the MIME map static initializer
        MimeMapInitFingerprint.method.apply {
            // Find the Collections.unmodifiableMap() call
            val unmodifiableMapIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Collections;->unmodifiableMap")
            }

            if (unmodifiableMapIndex == -1) {
                throw PatchException(
                    "Could not find Collections.unmodifiableMap() call in MIME map initializer. " +
                        "The APK version may not be supported."
                )
            }

            // Find ANY AbstractMap.put() call to determine the map register.
            // APK uses invoke-virtual with AbstractMap;->put, NOT invoke-interface with Map;->put
            val anyPutIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("AbstractMap;->put")
            }

            if (anyPutIndex == -1) {
                throw PatchException("Could not find any AbstractMap.put() call in MIME map initializer.")
            }

            // Read the map register directly from the AbstractMap.put() instruction.
            // For invoke-virtual {v1, v2, v3}, registerC = v1 (the map object).
            // Using FiveRegisterInstruction.registerC is reliable — toString() returns
            // "BuilderInstruction35c@hash" which cannot be parsed with regex.
            val mapRegister = getInstruction<FiveRegisterInstruction>(anyPutIndex).registerC

            // Get free registers for key and value strings
            // The <clinit> has 49 registers, and the map is likely in a low register.
            // We use findFreeRegister which is simpler for our case.
            val keyReg = findFreeRegister(unmodifiableMapIndex, mapRegister)
            val valReg = findFreeRegister(unmodifiableMapIndex, mapRegister, keyReg)

            // Additional MIME types to inject (only extensions NOT already in the original map)
            // Original map already has: json, xml, ini, conf, sh, bat, rs, dart, log, csv
            val additionalMimeTypes = listOf(
                "md" to "text/markdown",
                "markdown" to "text/markdown",
                "yaml" to "text/yaml",
                "yml" to "text/yaml",
                "toml" to "application/toml",
                "cfg" to "text/plain",
                "ps1" to "text/plain",
                "go" to "text/plain",
                "tsv" to "text/tab-separated-values",
            )

            // Build smali injection: Map.put() calls for each MIME type
            val mimePutInstructions = additionalMimeTypes.flatMap { (ext, mime) ->
                listOf(
                    "const-string v$keyReg, \"$ext\"",
                    "const-string v$valReg, \"$mime\"",
                    "invoke-virtual {v$mapRegister, v$keyReg, v$valReg}, Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                )
            }.joinToString("\n")

            addInstructions(
                unmodifiableMapIndex,
                mimePutInstructions,
            )
        }
    }
}
