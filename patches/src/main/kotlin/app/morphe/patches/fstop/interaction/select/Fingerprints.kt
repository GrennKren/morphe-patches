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
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->onPrepareOptionsMenu(Landroid/view/Menu;)Z
 * - PUBLIC, takes Menu parameter, returns boolean
 * - Just calls x2(menu) and returns true
 * - Registers: 2 (p0=Activity, p1=Menu)
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
 * Fingerprint for M3() method in ViewImageActivityNew.
 *
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->M3()V
 * - PUBLIC, no parameters, returns void
 * - Registers: 4 (p0=v3=this=ViewImageActivityNew)
 * - Creates Intent, gets current item from u0, puts selectImageId extra
 * - Calls Activity.setResult(-1, intent)
 * - Called from onPageSelected in ViewImageActivityNew$o inner class
 *   (at the END of page change processing, after all UI updates)
 *
 * Why hook M3() instead of onPageSelected():
 * - M3() is in ViewImageActivityNew itself, so p0 = this = Activity
 *   (type-safe, no VerifyError from wrong register types)
 * - onPageSelected is in inner class $o with only 4 registers,
 *   and v0 gets reused for different types (FilmStrip vs Activity),
 *   causing VerifyError when we try to use it as Activity
 * - M3() is called at the right time (after page change processing completes)
 * - p0 is ALWAYS ViewImageActivityNew, guaranteed by the method signature
 */
internal object M3Fingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/Intent;",
            name = "putExtra",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "setResult",
        ),
    ),
)

/**
 * Fingerprint for g(I Z)V — the native selection callback in ViewImageActivityNew.
 *
 * From androguard bytecode analysis:
 * - Method: Lcom/fstop/photo/activity/ViewImageActivityNew;->g(I Z)V
 * - PUBLIC, takes int (index) and boolean (selected), returns void
 * - Registers: 4 (p0=v1=this, p1=v2=index, p2=v3=selected)
 * - Gets c3/t item from u0.a ArrayList at index, calls item.X(selected)
 * - Has try-catch: happy path = goto return-void, catch = printStackTrace
 *
 * CALLED FROM: FilmStrip$a.onLongPress() after user long-presses a thumbnail:
 *   1. Toggle p1.m (thumbnail selected flag)
 *   2. FilmStrip.invalidate() (redraw)
 *   3. activity.g(index, p1.m) → THIS METHOD
 *
 * This is THE hook point for detecting native selection changes (Direction 2 sync).
 * When this method is called, c3/t.X() has already been invoked, so z() reflects
 * the new state. We update the Quick Select button icon accordingly.
 */
internal object SelectionCallbackFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/activity/ViewImageActivityNew;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lc3/t;",
            name = "X",
        ),
    ),
)
