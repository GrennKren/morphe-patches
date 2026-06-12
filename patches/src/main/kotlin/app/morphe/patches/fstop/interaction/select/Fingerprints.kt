package app.morphe.patches.fstop.interaction.select

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the onCreateOptionsMenu method in ViewImageActivityNew.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->onCreateOptionsMenu(Landroid/view/Menu;)Z
 * - PUBLIC, takes Menu parameter, returns boolean
 * - 13 registers, 338 instruction units, 2 catch blocks
 * - Inflates view_image_menu and calls t2(menu), x2(menu)
 *
 * Note: "view_image_menu" is a resource ID (integer constant), NOT a string
 * literal in the method, so we can't use it as a strings filter.
 *
 * The patch will hook this to add a "Select" menu item after inflation.
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
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->p3(ILandroid/view/MenuItem;)Z
 * - PUBLIC, takes int (item ID) and MenuItem, returns boolean
 * - Contains a large switch statement handling all menu item IDs
 *
 * The patch will hook this to handle the "Select" menu item click.
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
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->onPrepareOptionsMenu(Landroid/view/Menu;)Z
 * - PUBLIC, takes Menu parameter, returns boolean
 * - Calls x2(menu) to update menu item visibility
 *
 * The patch will hook this to update the select button's icon/state.
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
