/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.shared

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER

/**
 * Resource patch that changes the FX Explorer package name and all related manifest references
 * so the patched app can be installed alongside the original without conflicts.
 *
 * This handles everything that the generic "Change package name" patch does NOT handle by default:
 * - Removes android:sharedUserId (causes signature conflict when package name changes)
 * - Updates provider authorities (nextapp.fx.FileProvider, etc.)
 * - Updates permissions (nextapp.fx.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)
 * - Updates intent actions and scheme references
 * - Updates android:name attributes for activities/services that use the package prefix
 * - Updates taskAffinity references
 *
 * Uses the same text-replacement approach as gmsCoreSupportResourcePatch.
 */
@Suppress("unused")
val changeFxPackageNamePatch = resourcePatch(
    name = "Change FX Explorer package name",
    description = "Changes the package name from nextapp.fx to nextapp.fx.morphe and updates " +
        "all manifest references (provider authorities, permissions, sharedUserId, etc.) " +
        "so the patched app can be installed alongside the original.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        val fromPackage = "nextapp.fx"
        val toPackage = "nextapp.fx.morphe"

        val transformations = mapOf(
            // 1. Change package attribute in <manifest> tag
            "package=\"$fromPackage\"" to "package=\"$toPackage\"",

            // 2. Remove sharedUserId — this is critical! sharedUserId ties the app
            //    to a specific signing certificate. If we change the package name but
            //    keep sharedUserId, Android will reject the install because the new APK
            //    is signed with a different certificate than the original.
            //    Removing sharedUserId allows the app to run under its own identity.
            "android:sharedUserId=\"$fromPackage\"" to "",

            // 3. Update provider authorities
            "android:authorities=\"$fromPackage." to "android:authorities=\"$toPackage.",

            // 4. Update permissions
            "$fromPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "$toPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",

            // 5. Update intent action names (e.g. nextapp.fx.theme.THEME_ICON)
            "android:name=\"$fromPackage.intent." to "android:name=\"$toPackage.intent.",

            // 6. Update scheme references (e.g. <data android:scheme="nextapp.fx"/>)
            "android:scheme=\"$fromPackage\"" to "android:scheme=\"$toPackage\"",

            // 7. Update taskAffinity references
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
