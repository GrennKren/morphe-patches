/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.thumbnails

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode

/**
 * Patch to add a "Generate cover thumbnails" feature in Settings → Cache.
 *
 * FUNCTIONALITY:
 * Injects a new preference into the Cache settings screen that, when clicked,
 * generates thumbnails for the cover image of each included folder (as defined
 * in Settings → Main → Included folders). Progress is shown via notification.
 *
 * ARCHITECTURE:
 * - Patch side: Hooks SettingsFragmentCache.onCreatePreferences() and injects
 *   a call to CoverThumbnailGenerator.injectPreference(fragment) before return-void.
 * - Extension side: CoverThumbnailGenerator uses reflection to add a Preference
 *   to the Cache settings screen and sets up a click listener. On click, it
 *   starts a background thread that queries the IncludedFolder table, finds the
 *   cover image for each folder, generates a thumbnail, saves it to the Thumbnail
 *   SQLite table, and updates a notification with progress.
 *
 * DATABASE SCHEMA (from APK decompilation):
 * - IncludedFolder(_ID INTEGER PRIMARY KEY, FullPath TEXT UNIQUE)
 * - FolderData(_ID INTEGER PRIMARY KEY, FullPath TEXT UNIQUE, ThumbnailImageId INTEGER)
 * - Thumbnail(_ID INTEGER PRIMARY KEY, ImageId INTEGER, FullPath TEXT, MicroThumbnail BLOB)
 * - Image(_ID, FullPath, Folder, Orientation, ...)
 *
 * BYTECODE HOOK:
 * - SettingsFragmentCache.onCreatePreferences(Bundle, String):
 *   Before return-void, injects:
 *     invoke-static {p0}, CoverThumbnailGenerator->injectPreference(Ljava/lang/Object;)V
 *
 * Uses Object parameter instead of PreferenceFragmentCompat to avoid
 * compile-time dependency on obfuscated AndroidX preference classes.
 */
@Suppress("unused")
val generateCoverThumbnailsPatch = bytecodePatch(
    name = "Generate cover thumbnails",
    description = "Adds a 'Generate cover thumbnails' option in Settings → Cache " +
        "that generates thumbnails for the cover image of each included folder. " +
        "Shows progress via a notification with live updates.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/CoverThumbnailGenerator;"

        // ============================================================
        // Hook SettingsFragmentCache.onCreatePreferences()
        // ============================================================
        // The method body is:
        //   super.onCreatePreferences(bundle, str);
        //   findPreference("deletePreviewImages").setOnPreferenceClickListener(new a());
        //   findPreference("refreshCache").setOnPreferenceClickListener(new b());
        //   return-void;  <-- INJECT HERE (before return-void)
        //
        // p0 = this = SettingsFragmentCache instance
        // We pass it as Object to avoid AndroidX preference compile dependency.

        CacheSettingsOnCreateFingerprint.method.apply {
            val impl = implementation!!

            val returnIndex = impl.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return-void in SettingsFragmentCache.onCreatePreferences(). " +
                        "The APK version may not be supported."
                )
            }

            addInstructions(
                returnIndex,
                """
                    # Generate cover thumbnails: inject preference into cache settings
                    invoke-static {p0}, $EXTENSION_CLASS->injectPreference(Ljava/lang/Object;)V
                """,
            )
        }
    }
}
