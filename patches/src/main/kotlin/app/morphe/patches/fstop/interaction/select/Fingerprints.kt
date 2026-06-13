package app.morphe.patches.fstop.interaction.select

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the onCreateOptionsMenu method in ViewImageActivityNew.
 *
 * From androguard analysis of real DEX bytecode:
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
 * Fingerprint for the onPrepareOptionsMenu method in ViewImageActivityNew.
 *
 * From androguard analysis:
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

/**
 * Fingerprint for I2() method — hides toolbar / enters fullscreen mode.
 *
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->I2()V
 * - PUBLIC, no parameters, returns void
 * - Sets H0 = false (toolbar not visible)
 * - Uses AlphaAnimation(1.0f, 0.0f) with setFillAfter(true) to fade out
 * - Sets system UI visibility flags for fullscreen
 * - Final instruction: iput-boolean v1, v6, H0 Z (v1=0) then return-void
 */
internal object HideToolbarFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/animation/AlphaAnimation;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/view/View;",
            name = "setSystemUiVisibility",
        ),
    ),
)

/**
 * Fingerprint for a4() method — shows toolbar / exits fullscreen mode.
 *
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->a4()V
 * - PUBLIC, no parameters, returns void
 * - Sets H0 = true (toolbar visible)
 * - Uses AlphaAnimation(0.0f, 1.0f) with setFillAfter(true) to fade in
 * - Clears system UI visibility flags
 * - Final instruction: iput-boolean v2, v7, H0 Z (v2=1) then return-void
 */
internal object ShowToolbarFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/animation/AlphaAnimation;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/view/animation/Animation;",
            name = "setFillBefore",
        ),
    ),
)

/**
 * Fingerprint for onPageSelected in the inner class ViewImageActivityNew$o.
 *
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew$o;->onPageSelected(I)V
 * - PUBLIC, takes int parameter (page index), returns void
 * - Called by ViewPager when user swipes between images
 * - Accesses ViewImageActivityNew.u0 (l3.k), calls l3.k.b(I)V
 * - Accesses ViewImageActivityNew.Q0 (FilmStrip), calls FilmStrip.l(I)V or u(I)V
 * - Calls ViewImageActivityNew.M3()V and E3()V
 *
 * This is the key method we hook to update the quick select button
 * immediately when the user swipes, without waiting for
 * onPrepareOptionsMenu which may not be called.
 */
internal object PageSelectedFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew\$o;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
            name = "M3",
        ),
    ),
)
