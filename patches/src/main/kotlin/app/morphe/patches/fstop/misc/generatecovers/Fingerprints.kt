package app.morphe.patches.fstop.misc.generatecovers

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for `Lcom/fstop/photo/preferences/SettingsFragmentCache;->onCreatePreferences(Landroid/os/Bundle;Ljava/lang/String;)V`.
 *
 * From APK decompilation (com.fstop.photo 5.5.484):
 * - Method: PUBLIC, takes (Bundle, String), returns void, .locals 0
 * - Calls super.onCreatePreferences, then findPreference("deletePreviewImages")
 *   and findPreference("refreshCache") to set click listeners
 *
 * The patch injects a findPreference("generateAllFolderCovers") +
 * setOnPreferenceClickListener call before return-void — following the
 * EXACT same pattern as vanilla F-Stop's existing click listener setup.
 */
internal object SettingsFragmentCacheOnCreatePreferencesFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/preferences/SettingsFragmentCache;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroidx/preference/h;",
            name = "findPreference",
        ),
    ),
)
