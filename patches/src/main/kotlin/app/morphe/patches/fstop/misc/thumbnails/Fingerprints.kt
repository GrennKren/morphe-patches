package app.morphe.patches.fstop.misc.thumbnails

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the y1.b() method that clears ALL thumbnail caches.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/y1;->b()V
 * - PUBLIC, no parameters, returns void
 * - Registers: 2
 * - Calls y1.c(I) four times with indices 0, 1, 2, 4 to clear each cache slot
 *
 * The patch replaces this method body with:
 *   const/4 v0, 0x1
 *   sput-boolean v0, b0.X2  (force prescanThumbnails ON)
 *   return-void
 * This prevents ALL cache clearing AND re-forces X2=true.
 */
internal object ThumbnailClearCacheFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1;",
            name = "c",
        ),
    ),
)

/**
 * Fingerprint for the ThumbnailManager (y1) constructor.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/y1;-><init>()V
 * - PUBLIC constructor, no parameters, returns void
 * - Registers: 11 (p0 = this)
 * - Creates 4 LRU caches (y1$a extends j0 extends LinkedHashMap)
 * - Each cache is initialized with size from static field y1.e (default 50 = 0x32)
 *
 * The patch injects code at the start of the constructor (after super() call)
 * to set y1.e = 500 and force b0.X2 = true.
 */
internal object ThumbnailManagerInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/y1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/y1\$a;",
            name = "<init>",
        ),
    ),
)

/**
 * Fingerprint for the p.R2(SharedPreferences) method that loads all preferences.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->R2(Landroid/content/SharedPreferences;)V
 * - PUBLIC STATIC, takes SharedPreferences parameter, returns void
 * - Reads dozens of preference keys and stores them in b0 static fields
 *
 * The patch replaces the move-result v0 with const/4 v0, 0x1, ensuring
 * that X2 is ALWAYS set to true regardless of the user's preference.
 */
internal object PrescanThumbnailsPrefFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/SharedPreferences;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getBoolean",
        ),
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getString",
        ),
    ),
)
