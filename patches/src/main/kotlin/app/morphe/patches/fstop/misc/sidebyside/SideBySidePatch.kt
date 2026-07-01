/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.sidebyside

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.changePackageNamePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to enable side-by-side installation of F-Stop alongside the original app.
 *
 * PROBLEM:
 * F-Stop's patched APK has the same package name (com.fstop.photo) as the original,
 * causing a package conflict when trying to install both. Simply changing the package
 * name in AndroidManifest.xml is NOT enough because F-Stop has hardcoded authority
 * strings in its bytecode:
 *
 * 1. FileProvider uses "content://com.fstop.photo.contentProvider.FileProvider" in
 *    Uri.parse() and "com.fstop.photo.contentProvider.FileProvider" in addURI().
 * 2. SearchSuggestionsProvider uses "com.fstop.photo.searchSuggestionsProvider" as
 *    a hardcoded authority in its <clinit> method.
 * 3. p.L() hardcodes "com.fstop.photo" in a getPackageInfo() call.
 * 4. NativeMethods.<clinit> has hardcoded JNI paths:
 *    /data/app-lib/com.fstop.photo/libfunctions-jni.so
 *    /data/data/com.fstop.photo/lib/libfunctions-jni.so
 *    These paths must be updated for the Editor to work.
 *
 * SOLUTION:
 * This patch:
 * 1. Changes the package name in AndroidManifest.xml (via changePackageNamePatch)
 * 2. Updates all manifest authority references (inline resourcePatch)
 * 3. Updates hardcoded authority strings in FileProvider bytecode (preserving content:// prefix)
 * 4. Updates hardcoded authority strings in SearchSuggestionsProvider bytecode (preserving content:// prefix)
 * 5. Updates hardcoded package name string in p.L() method
 * 6. Updates hardcoded JNI paths in NativeMethods.<clinit> for the Editor feature
 *
 * CRITICAL FIX (v2): Uses substring replacement instead of hardcoded replacement
 * to preserve the "content://" prefix in URI strings. The old approach replaced
 * the ENTIRE const-string with just the authority, which broke FileProvider URIs:
 *   "content://com.fstop.photo.contentProvider.FileProvider" became
 *   "com.fstop.photo.morphe.contentProvider.FileProvider" (missing content://).
 * This caused FileNotFoundException in the Editor.
 */
@Suppress("unused")
val sideBySidePatch = bytecodePatch(
    name = "Side-by-side installation",
    description = "Lets you install this patched version alongside the " +
        "original F-Stop from the Play Store, so both can coexist on the " +
        "same device.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    dependsOn(changePackageNamePatch)

    // Inline resource patch that changes the package name and updates all manifest
    // references so the patched app can be installed alongside the original.
    dependsOn(
        resourcePatch {
            execute {
                val fromPackage = "com.fstop.photo"
                val toPackage = setOrGetFallbackPackageName("$fromPackage.morphe")

                val transformations = mapOf(
                    "package=\"$fromPackage\"" to "package=\"$toPackage\"",
                    "android:authorities=\"$fromPackage." to "android:authorities=\"$toPackage.",
                    "$fromPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                        "$toPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                )

                val manifest = get("AndroidManifest.xml")
                manifest.writeText(
                    transformations.entries.fold(manifest.readText()) { acc, (from, to) ->
                        acc.replace(from, to)
                    },
                )
            }
        }
    )

    execute {
        val fromPackage = "com.fstop.photo"
        val toPackage = setOrGetFallbackPackageName("$fromPackage.morphe")

        // ============================================================
        // Part 1: Update FileProvider hardcoded authorities
        // ============================================================
        // FileProvider.<clinit> contains:
        //   const-string v0, "content://com.fstop.photo.contentProvider.FileProvider"
        //   invoke-static {v0}, Uri;->parse(...)
        //   ...
        //   const-string v3, "com.fstop.photo.contentProvider.FileProvider"
        //   invoke-virtual {v0, v3, ...}, UriMatcher;->addURI(...)
        //   const-string v3, "com.fstop.photo.contentProvider.FileProvider"
        //   invoke-virtual {v0, v3, ...}, UriMatcher;->addURI(...)
        //
        // CRITICAL: Use substring replacement to preserve "content://" prefix.
        // The first const-string has "content://" prefix for Uri.parse(),
        // while the other two are bare authority strings for addURI().
        // We replace "com.fstop.photo" with the new package name in all cases.

        FileProviderInitFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo.contentProvider.FileProvider")
                ) {
                    val oldString = instruction.reference.toString()
                    val newString = oldString.replace("com.fstop.photo", toPackage)
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$newString\"",
                    )
                }
            }
        }

        // ============================================================
        // Part 2: Update SearchSuggestionsProvider hardcoded authorities
        // ============================================================
        // Same pattern as FileProvider — use substring replacement to
        // preserve any URI scheme prefix.

        SearchSuggestionsProviderInitFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo.searchSuggestionsProvider")
                ) {
                    val oldString = instruction.reference.toString()
                    val newString = oldString.replace("com.fstop.photo", toPackage)
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$newString\"",
                    )
                }
            }
        }

        // ============================================================
        // Part 3: Update hardcoded package name in p.L() method
        // ============================================================
        // p.L() contains:
        //   const-string vX, "com.fstop.photo"
        //   invoke-virtual {vY, vX, ...}, PackageManager;->getPackageInfo(...)
        //
        // After package name change, getPackageInfo("com.fstop.photo") would
        // fail to find the app (since it's now com.fstop.photo.morphe).
        // We replace the const-string with the new package name.

        HardcodedPackageNameFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo")
                ) {
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$toPackage\"",
                    )
                }
            }
        }

        // ============================================================
        // Part 4: Update hardcoded JNI paths in NativeMethods.<clinit>
        // ============================================================
        // NativeMethods.<clinit> contains fallback JNI loading paths:
        //   const-string v0, "/data/app-lib/com.fstop.photo/libfunctions-jni.so"
        //   System.load(v0)
        //   const-string v0, "/data/data/com.fstop.photo/lib/libfunctions-jni.so"
        //   System.load(v0)
        //
        // The Editor feature uses libfunctions-jni.so for image editing.
        // After package name change, these paths point to the wrong directory,
        // causing "path not found" error when opening the Editor.
        //
        // We replace the package name in these paths to match the new package.

        JniPathFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo")
                ) {
                    val oldString = instruction.reference.toString()
                    val newString = oldString.replace("com.fstop.photo", toPackage)
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$newString\"",
                    )
                }
            }
        }
    }
}
