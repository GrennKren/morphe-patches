package app.morphe.patches.fstop.misc.telemetry

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the Bugsnag initialization method in MyApplication.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/MyApplication;->f()V
 * - Package-private (no access modifiers)
 * - No parameters, returns void
 * - Contains the Bugsnag client initialization sequence:
 *   1. Checks b0.h2 (crash reporting disabled flag)
 *   2. Gets default uncaught exception handler
 *   3. Creates Bugsnag config via com.bugsnag.android.w.J(this)
 *   4. Adds callback via com.bugsnag.android.w.a(callback)
 *   5. Initializes Bugsnag client via com.bugsnag.android.n.f(this, config)
 *   6. Sets custom uncaught exception handler
 *
 * The patch will replace this method body with a simple return-void,
 * preventing all Bugsnag crash reporting from initializing.
 */
internal object BugsnagInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/MyApplication;",
    returnType = "V",
    accessFlags = listOf(), // package-private
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/bugsnag/android/w;",
            name = "J",
        ),
        methodCall(
            definingClass = "Lcom/bugsnag/android/n;",
            name = "f",
        ),
    ),
)

/**
 * Fingerprint for the FirebaseAnalytics initialization in MyApplication.onCreate().
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/MyApplication;->onCreate()V
 * - PUBLIC, no parameters, returns void
 * - Calls FirebaseAnalytics.getInstance(applicationContext)
 * - Stores result in b0.j0 (static FirebaseAnalytics field)
 * - Also calls MyApplication.f() (Bugsnag init) and
 *   NativeMethods.performNativeBugsnagSetup()
 *
 * The patch will replace the FirebaseAnalytics.getInstance() call with null,
 * preventing Firebase Analytics from collecting any data.
 */
internal object FirebaseAnalyticsInitFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/MyApplication;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    strings = listOf("activity"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/firebase/analytics/FirebaseAnalytics;",
            name = "getInstance",
        ),
    ),
)

/**
 * Fingerprint for the native Bugsnag setup method.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/Native/NativeMethods;->performNativeBugsnagSetup()V
 * - PUBLIC NATIVE method (implemented in JNI)
 * - Called from MyApplication.onCreate() after Java Bugsnag init
 *
 * The patch will replace this method with a no-op stub,
 * preventing native Bugsnag library from initializing.
 */
internal object NativeBugsnagSetupFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/Native/NativeMethods;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.NATIVE),
    parameters = listOf(),
)

/**
 * Fingerprint for Bugsnag.leaveBreadcrumb() static method.
 *
 * From APK decompilation:
 * - Method: Lcom/bugsnag/android/n;->c(Ljava/lang/String;)V
 * - PUBLIC STATIC, takes String, returns void
 * - Internally calls n.b() which throws if Bugsnag hasn't been started
 * - Called from FolderScannerService, ListOfSomethingActivity, p, etc.
 *
 * The patch will replace this method body with return-void,
 * preventing crashes when Bugsnag hasn't been initialized.
 */
internal object BugsnagLeaveBreadcrumbFingerprint : Fingerprint(
    definingClass = "Lcom/bugsnag/android/n;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
)

/**
 * Fingerprint for Bugsnag.notifyError() static method.
 *
 * From APK decompilation:
 * - Method: Lcom/bugsnag/android/n;->e(Ljava/lang/Throwable;)V
 * - PUBLIC STATIC, takes Throwable, returns void
 * - Internally calls n.b() which throws if Bugsnag hasn't been started
 * - Called from FolderScannerService, ListOfSomethingActivity, etc.
 *
 * The patch will replace this method body with return-void,
 * preventing crashes when Bugsnag hasn't been initialized.
 */
internal object BugsnagNotifyErrorFingerprint : Fingerprint(
    definingClass = "Lcom/bugsnag/android/n;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/Throwable;"),
)

/**
 * Fingerprint for Bugsnag.addMetadata() static method.
 *
 * From APK decompilation:
 * - Method: Lcom/bugsnag/android/n;->a(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
 * - PUBLIC STATIC, takes section, key, value, returns void
 * - Internally calls n.b() which throws if Bugsnag hasn't been started
 * - Called from MyApplication anonymous class (Bugsnag callback)
 *
 * The patch will replace this method body with return-void,
 * preventing crashes when Bugsnag hasn't been initialized.
 */
internal object BugsnagAddMetadataFingerprint : Fingerprint(
    definingClass = "Lcom/bugsnag/android/n;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Object;"),
)
