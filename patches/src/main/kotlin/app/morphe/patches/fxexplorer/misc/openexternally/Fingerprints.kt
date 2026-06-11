package app.morphe.patches.fxexplorer.misc.openexternally

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the MIME type map static initializer in Lab/k;.
 *
 * The <clinit> method builds the extension-to-MIME-type map and wraps it
 * with Collections.unmodifiableMap(). The patch injects additional Map.put()
 * calls before the unmodifiableMap call to add missing MIME types.
 *
 * Key identifiers from APK decompilation:
 * - Static constructor (<clinit>) — access flags: STATIC | CONSTRUCTOR only (NO PUBLIC)
 * - 49 registers, no parameters, returns void
 * - Calls Collections.unmodifiableMap()
 * - Contains many AbstractMap.put() calls for extension-to-MIME mapping
 * - Contains unique string constants like "asm", "awk", "xslt", "cxx"
 * - Field Lab/k;->b stores the resulting Map
 *
 * IMPORTANT: <clinit> does NOT have the PUBLIC access flag (0x10008 = STATIC|CONSTRUCTOR).
 * Using definingClass = "Lab/k;" narrows the search to this specific class.
 */
internal object MimeMapInitFingerprint : Fingerprint(
    definingClass = "Lab/k;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    strings = listOf("asm", "awk", "xslt"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Collections;",
            name = "unmodifiableMap",
        ),
    ),
)

/**
 * Fingerprint for the file open dialog method in Lhf/y0;.
 *
 * This is the j() method that creates and shows the "Open With" dialog.
 * From APK analysis: j(Landroid/content/Context; Lkh/e; Llf/b;)V
 * It is a PUBLIC STATIC method that creates a new hf/y0 dialog instance
 * and posts a show-runnable via Handler.postDelayed(100ms).
 *
 * The patch hooks this method AFTER the y0 constructor call to check for
 * a stored default app. At that point, v0 holds the y0 dialog instance
 * with its resolver (qe/d) fully initialized, providing access to the
 * correct File/URI for the file. If a default is found, tryOpenWithDefault()
 * opens the file directly, the dialog is dismissed, and the method returns
 * early — preventing the show-runnable from being created/posted.
 *
 * Injection uses addInstructionsWithLabels for label support.
 * Original register layout: .locals 2 (v0, v1), p0=Context, p1=kh/e, p2=lf/b
 * At injection point (after constructor): v0=y0 dialog, v1=0x1 (available)
 *
 * The injection simply calls tryOpenWithDefault(y0) which uses the dialog's
 * resolver to get the proper File/URI — the same proven approach used when
 * the user clicks the wildcard button in the dialog.
 */
internal object FileOpenDialogFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Lkh/e;", "Llf/b;"),
    filters = listOf(
        methodCall(
            definingClass = "Lhf/y0;",
            name = "<init>",
        ),
    ),
)

/**
 * Fingerprint for the external app launch method in Lhf/y0;.
 *
 * From APK analysis: h(Landroid/content/pm/ResolveInfo; Landroid/net/Uri; I)V
 * This is an instance method (PUBLIC FINAL) that creates an ACTION_VIEW intent,
 * sets the data URI and MIME type, sets the component from ResolveInfo, adds flags,
 * and starts the activity. It's the method that actually launches an external app.
 *
 * The patch hooks this method to intercept app launches and save the selected
 * app as the default when the "selecting default" flag is set.
 *
 * Injection: Single invoke-static at method start. No labels, no branches.
 * Safe: The extension method catches all exceptions internally.
 */
internal object ExternalLaunchFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/pm/ResolveInfo;", "Landroid/net/Uri;", "I"),
    strings = listOf("android.intent.action.VIEW"),
)

/**
 * Fingerprint for the dialog populator Runnable in Lhf/w0;.
 *
 * This is the run() method that populates the "Open With" dialog after
 * app resolution completes. The pswitch case 0 handles the main dialog
 * population flow (adding headers, sections, and app items).
 *
 * Key identifiers from APK decompilation:
 * - Public final method returning void with no parameters
 * - Implements Runnable (called via Handler.post)
 * - Checks Lhf/w0;->f (case selector) via packed-switch
 * - Case 0 (pswitch_1c) is the main dialog populator
 * - Contains unique string "download" (used for icon key)
 * - Contains unique string "package_android_root"
 * - Accesses Lhf/y0;->e2 (MIME type override field)
 * - Calls Lhf/y0;->b(I)V to add section separators
 * - Calls Lhf/y0;->e(...)V to add grid items
 * - Calls Lqe/a;-><init>(Lqe/d;Ljava/lang/String;Z)V for resolver results
 *
 * The patch injects an "Open With Default" section before the existing
 * sections, containing a single button that sets MIME type override to
 * wildcard and re-resolves apps.
 */
internal object DialogPopulatorFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    strings = listOf("package_android_root"),
    filters = listOf(
        methodCall(
            definingClass = "Lhf/y0;",
            name = "b",
        ),
        methodCall(
            definingClass = "Lhf/y0;",
            name = "e",
        ),
    ),
)
