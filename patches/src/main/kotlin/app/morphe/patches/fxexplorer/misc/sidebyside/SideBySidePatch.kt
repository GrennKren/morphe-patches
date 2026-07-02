/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.sidebyside

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.changePackageNamePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER

/**
 * Patch to enable side-by-side installation of FX Explorer alongside the original app.
 *
 * PROBLEM:
 * The patched APK has the same package name (nextapp.fx) as the original,
 * causing a package conflict when trying to install both.
 *
 * SOLUTION:
 * Changes the package name to nextapp.fx.morphe and updates all manifest
 * references (authorities, shared user ID, permissions, intent schemes,
 * task affinity) so the patched app can be installed alongside the original.
 *
 * COMPATIBILITY:
 * - Independent of "License compatibility" (works whether or not signature
 *   spoofing is enabled).
 * - Independent of "Unlock premium" (works whether or not premium is
 *   force-unlocked).
 */
@Suppress("unused")
val sideBySidePatch = bytecodePatch(
    name = "Side-by-side installation",
    description = "Lets you install this patched version alongside the " +
        "original FX Explorer from the Play Store, so both can coexist on " +
        "the same device.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    dependsOn(changePackageNamePatch)

    dependsOn(
        resourcePatch {
            execute {
                val fromPackage = "nextapp.fx"
                val toPackage = setOrGetFallbackPackageName("$fromPackage.morphe")

                val transformations = mapOf(
                    "package=\"$fromPackage\"" to "package=\"$toPackage\"",
                    "android:sharedUserId=\"$fromPackage\"" to "",
                    "android:authorities=\"$fromPackage." to "android:authorities=\"$toPackage.",
                    "$fromPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                        "$toPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    "android:name=\"$fromPackage.intent." to "android:name=\"$toPackage.intent.",
                    "android:scheme=\"$fromPackage\"" to "android:scheme=\"$toPackage\"",
                    "android:taskAffinity=\"$fromPackage." to "android:taskAffinity=\"$toPackage.",
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
}
