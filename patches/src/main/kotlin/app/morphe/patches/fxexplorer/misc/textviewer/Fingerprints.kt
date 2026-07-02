package app.morphe.patches.fxexplorer.misc.textviewer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the master file-open decision method in Lhf/b0;.
 *
 * From APK analysis: a(Landroid/app/Activity; Lkh/e; Llf/b; Landroid/graphics/Rect; Landroid/graphics/Rect; I)V
 * This is a PUBLIC STATIC method that decides how to open a file: internal viewer,
 * external app, or "Open With" dialog.
 *
 * The bug: When textViewerUseInternal is false (user unchecked "Text Files" in
 * Opening Files settings), the code falls through to a goto that STILL opens
 * the internal TextViewerActivity instead of showing the "Open With" dialog.
 * Text files should be opened externally when the internal viewer is disabled.
 *
 * Key identifiers from APK decompilation:
 * - Public static method with 6 parameters returning void
 * - Contains string "textViewerUseInternal" (SharedPreferences key)
 * - Contains string "textViewerUseEditor" (SharedPreferences key)
 * - Contains string "imageViewerUseInternal" (SharedPreferences key)
 * - Contains string "audioPlayerUseInternal" (SharedPreferences key)
 * - Contains string "videoPlayerUseInternal" (SharedPreferences key)
 * - Contains string "text/" (MIME type prefix check)
 * - Calls SharedPreferences.getBoolean()
 * - Calls hf/y0.j() as fallback (3 times)
 *
 * Method register layout: .registers 23, .locals 17
 * Parameter registers: p0=v16(Activity), p1=v17(kh/e), p2=v18(lf/b),
 *                      p3=v19(Rect), p4=v20(Rect), p5=v21(int)
 * But v17-v19 are preserved for y0.j() calls throughout the method.
 */
internal object FileOpenerFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lkh/e;",
        "Llf/b;",
        "Landroid/graphics/Rect;",
        "Landroid/graphics/Rect;",
        "I",
    ),
    strings = listOf("textViewerUseInternal", "textViewerUseEditor"),
    filters = listOf(
        methodCall(
            definingClass = "Lhf/y0;",
            name = "j",
        ),
    ),
)
