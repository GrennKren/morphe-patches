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
 * 1. FileProvider uses "com.fstop.photo.contentProvider.FileProvider" as a hardcoded
 *    authority in its <clinit> method (Uri.parse + UriMatcher.addURI).
 * 2. SearchSuggestionsProvider uses "com.fstop.photo.searchSuggestionsProvider" as
 *    a hardcoded authority in its <clinit> method.
 * 3. Various methods use getPackageName() to construct data paths
 *    (/Android/data/{packageName}/...), which is actually fine because the data
 *    directory should match the new package name.
 * 4. p.L() hardcodes "com.fstop.photo" in a getPackageInfo() call to get
 *    firstInstallTime — this needs updating so the app can find itself.
 *
 * When the manifest's provider authorities are changed but the bytecode still
 * uses the old authority strings, the ContentProviders fail to resolve URIs,
 * causing the app to crash on startup.
 *
 * SOLUTION:
 * This patch:
 * 1. Changes the package name in AndroidManifest.xml (via changePackageNamePatch)
 * 2. Updates all manifest authority references (inline resourcePatch)
 * 3. Updates hardcoded authority strings in FileProvider bytecode
 * 4. Updates hardcoded authority strings in SearchSuggestionsProvider bytecode
 * 5. Updates hardcoded package name string in p.L() method
 *
 * The new package name defaults to "com.fstop.photo.morphe" but can be customized
 * via the Change package name patch options.
 */
@Suppress("unused")
val sideBySidePatch = bytecodePatch(
    name = "Side-by-side installation",
    description = "Changes the package name to allow installing the patched app " +
        "alongside the original. Also updates hardcoded ContentProvider authority " +
        "strings in bytecode to match the new package name. Without these bytecode " +
        "fixes, the app would crash because providers cannot resolve URIs.",
    // Default enabled so the patched app can be installed alongside the original
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
        //   const-string vX, "com.fstop.photo.contentProvider.FileProvider"
        //   invoke-static {vX}, Landroid/net/Uri;->parse(...)
        //   ...
        //   const-string vY, "com.fstop.photo.contentProvider.FileProvider"
        //   invoke-virtual {vZ, vY, ...}, Landroid/content/UriMatcher;->addURI(...)
        //   const-string vY, "com.fstop.photo.contentProvider.FileProvider"
        //   invoke-virtual {vZ, vY, ...}, Landroid/content/UriMatcher;->addURI(...)
        //
        // We need to replace ALL const-string instructions that contain the old
        // authority with the new one.

        FileProviderInitFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo.contentProvider.FileProvider")
                ) {
                    val newAuthority = "com.fstop.photo.morphe.contentProvider.FileProvider"
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$newAuthority\"",
                    )
                }
            }
        }

        // ============================================================
        // Part 2: Update SearchSuggestionsProvider hardcoded authorities
        // ============================================================
        // Same pattern as FileProvider — replace all const-string instructions
        // containing the old authority.

        SearchSuggestionsProviderInitFingerprint.method.apply {
            val impl = implementation!!

            for ((index, instruction) in impl.instructions.withIndex()) {
                if (instruction.opcode == Opcode.CONST_STRING &&
                    instruction is ReferenceInstruction &&
                    instruction.reference.toString().contains("com.fstop.photo.searchSuggestionsProvider")
                ) {
                    val newAuthority = "com.fstop.photo.morphe.searchSuggestionsProvider"
                    val register = (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
                    replaceInstruction(
                        index,
                        "const-string v$register, \"$newAuthority\"",
                    )
                }
            }
        }

        // ============================================================
        // Part 3: Update hardcoded package name in p.L() method
        // ============================================================
        // p.L() contains:
        //   const-string vX, "com.fstop.photo"
        //   invoke-virtual {vY, vX, ...}, Landroid/content/pm/PackageManager;->getPackageInfo(...)
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
    }
}
