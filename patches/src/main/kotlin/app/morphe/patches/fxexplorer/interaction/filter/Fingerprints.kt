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
