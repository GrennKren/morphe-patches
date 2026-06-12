package app.morphe.patches.fstop.interaction.select

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the onCreateOptionsMenu method in ViewImageActivityNew.
 *
 * From dexdump (REAL DEX bytecode, not jadx):
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->onCreateOptionsMenu(Landroid/view/Menu;)Z
 * - PUBLIC, takes Menu parameter, returns boolean
 * - Calls: getMenuInflater().inflate(), t2(menu), x2(menu)
 */
internal object CreateOptionsMenuFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/MenuInflater;",
            name = "inflate",
        ),
        methodCall(
            definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
            name = "x2",
        ),
    ),
)

/**
 * Fingerprint for the menu item handler method p3 in ViewImageActivityNew.
 *
 * From dexdump:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->p3(ILandroid/view/MenuItem;)Z
 * - PUBLIC, takes int (item ID) and MenuItem, returns boolean
 */
internal object MenuItemHandlerFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I", "Landroid/view/MenuItem;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
            name = "Z3",
        ),
    ),
)

/**
 * Fingerprint for the onPrepareOptionsMenu method in ViewImageActivityNew.
 *
 * From dexdump:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->onPrepareOptionsMenu(Landroid/view/Menu;)Z
 * - PUBLIC, takes Menu parameter, returns boolean
 * - Just calls x2(menu) and returns true
 */
internal object PrepareOptionsMenuFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
            name = "x2",
        ),
    ),
)
