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
 * - Static method (<clinit>) with 49 registers
 * - Calls Collections.unmodifiableMap()
 * - Contains many Map.put() calls for extension-to-MIME mapping
 * - Field Lab/k;->b stores the resulting Map
 */
internal object MimeMapInitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
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
 * It creates a new hf/y0 dialog instance and posts it via Handler.
 *
 * The patch replaces the dialog-showing flow with direct external app launch.
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
 * This method creates an ACTION_VIEW intent, sets the data URI and MIME type,
 * sets the component from ResolveInfo, adds flags, and starts the activity.
 * It's the method that actually launches an external app.
 *
 * Key pattern:
 *   new-instance Intent
 *   const-string "android.intent.action.VIEW"
 *   invoke-direct Intent-><init>(String)V
 *   ... setData or setDataAndType ...
 *   ... setClassName ...
 *   ... addFlags ...
 *   invoke-static Lyd/a;->l(Context,Intent,int)Z  (startActivity wrapper)
 */
internal object ExternalLaunchFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/pm/ResolveInfo;", "Landroid/net/Uri;", "I"),
    strings = listOf("android.intent.action.VIEW"),
)
