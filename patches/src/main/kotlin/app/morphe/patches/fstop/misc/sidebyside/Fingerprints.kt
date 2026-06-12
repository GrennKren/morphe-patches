package app.morphe.patches.fstop.misc.sidebyside

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for FileProvider's static initializer (<clinit>).
 *
 * From APK decompilation:
 * - Class: Lcom/fstop/photo/contentProvider/FileProvider;
 * - The <clinit> method contains:
 *   1. Uri.parse("content://com.fstop.photo.contentProvider.FileProvider")
 *      stored in static field f8849g
 *   2. UriMatcher construction with addURI("com.fstop.photo.contentProvider.FileProvider", ...)
 *
 * The patch needs to update the hardcoded authority string so it matches
 * the new package name in AndroidManifest.xml.
 */
internal object FileProviderInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/contentProvider/FileProvider;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    strings = listOf("com.fstop.photo.contentProvider.FileProvider"),
)

/**
 * Fingerprint for SearchSuggestionsProvider's static initializer (<clinit>).
 *
 * From APK decompilation:
 * - Class: Lcom/fstop/photo/contentProvider/SearchSuggestionsProvider;
 * - The <clinit> method contains:
 *   1. Uri.parse("content://com.fstop.photo.searchSuggestionsProvider/search")
 *      stored in static field f8851g
 *   2. UriMatcher construction with addURI("com.fstop.photo.searchSuggestionsProvider", ...)
 *
 * The patch needs to update the hardcoded authority string so it matches
 * the new package name in AndroidManifest.xml.
 */
internal object SearchSuggestionsProviderInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/contentProvider/SearchSuggestionsProvider;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    strings = listOf("com.fstop.photo.searchSuggestionsProvider"),
)

/**
 * Fingerprint for the hardcoded package name reference in p.L() method.
 *
 * From APK decompilation:
 * - Method in class Lcom/fstop/photo/p;
 * - Calls getPackageInfo("com.fstop.photo", 4096) with hardcoded package name
 * - Used to get firstInstallTime
 *
 * The patch will replace this with the new package name so it can find itself.
 */
internal object HardcodedPackageNameFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "J",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    strings = listOf("com.fstop.photo"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getPackageInfo",
        ),
    ),
)
