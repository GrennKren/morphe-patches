package app.morphe.patches.fstop.misc.thumbnails

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for SettingsFragmentCache.onCreatePreferences(Bundle, String).
 *
 * From APK decompilation:
 * - Class: com.fstop.photo.preferences.SettingsFragmentCache
 * - Method: onCreatePreferences(Bundle, String)
 * - PUBLIC override, returns void
 * - Contains strings "deletePreviewImages" and "refreshCache" from findPreference() calls
 *
 * The patch injects a static call to CoverThumbnailGenerator.injectPreference(this)
 * before the return-void instruction, adding a "Generate cover thumbnails" preference.
 */
internal object CacheSettingsOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/preferences/SettingsFragmentCache;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    strings = listOf("deletePreviewImages"),
)
