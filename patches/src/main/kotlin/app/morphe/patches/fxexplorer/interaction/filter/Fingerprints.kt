package app.morphe.patches.fxexplorer.interaction.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the directory refresh method (L0) in the DirectoryContentPanel (lf/s).
 *
 * This method unconditionally calls V0() which clears the active filter.
 * The patch modifies this to skip the clear when a filter is active.
 *
 * Key identifiers:
 * - Public final method returning void with no parameters
 * - Calls K0() then V0() at the start
 * - Calls getDirectory() method
 */
internal object DirectoryRefreshFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            name = "K0",
            returnType = "V",
        ),
        methodCall(
            name = "V0",
            returnType = "V",
        ),
        methodCall(
            name = "getDirectory",
        ),
    ),
)

/**
 * Fingerprint for the license verification method (lh/n.l(Context)Z).
 *
 * This method verifies the FX Plus License Key app by:
 * 1. Checking SharedPreferences cache for IAB license
 * 2. Comparing the signature of nextapp.fx.rk with the app's own signature
 *
 * The signature spoofing patch hooks the second getPackageInfo call
 * (which queries the app's own package) to return the original signature
 * instead of the patched one, so the comparison succeeds.
 *
 * Key identifiers:
 * - Public static method returning boolean with Context parameter
 * - References "nextapp.fx.rk" string constant
 * - Calls PackageManager.getPackageInfo twice (once for key app, once for self)
 */
internal object LicenseCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("nextapp.fx.rk"),
)

/**
 * Fingerprint for the onResume method in the DirectoryContentPanel (lf/s).
 *
 * This method triggers a directory refresh (R0) when the activity resumes
 * after being paused for more than 1.5 seconds. This causes a full reload
 * that destroys the RecyclerView adapter, losing the scroll position.
 *
 * The patch hooks this method to skip the refresh when returning from
 * an external app launch (indicated by FilterCache.consumeExternalLaunch()),
 * preserving the scroll position by simply restarting the FileObserver
 * without a full directory reload.
 *
 * Key identifiers:
 * - Public final method returning void with no parameters in Llf/s;
 * - Accesses Llf/s;->k2 (boolean: isLocalFile)
 * - Accesses Llf/s;->j2 (long: lastRefreshTimestamp)
 * - Calls Llf/s;->R0(Z)V (directory refresh with saveScroll parameter)
 */
internal object OnResumeFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    definingClass = "Llf/s;",
    filters = listOf(
        methodCall(
            definingClass = "Llf/s;",
            name = "R0",
        ),
    ),
)
