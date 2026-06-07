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
