/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Helper class for license compatibility in patched F-Stop APKs.
 *
 * The original F-Stop uses PackageManager.checkSignatures() to verify that
 * com.fstop.photo and com.fstop.photo.key share the same signing certificate.
 * This always fails for patched APKs because they are re-signed with a
 * different certificate.
 *
 * This helper provides an alternative check: simply verify that the key app
 * (com.fstop.photo.key) is installed via getPackageInfo(). This allows
 * legitimate license holders to have their premium status detected without
 * needing the "Unlock Premium" patch.
 *
 * Uses b0.r (the static Application Context from the APK itself) to get
 * the PackageManager. This is the same Context that s3.a.d() uses internally
 * for its checkSignatures() call, so it is guaranteed to be initialized
 * by the time our injected code runs.
 */
@SuppressWarnings("unused")
public class LicenseHelper {

    private static final String KEY_APP_PACKAGE = "com.fstop.photo.key";

    /**
     * Check if the F-Stop premium key app is installed on the device.
     *
     * This is used as a replacement for checkSignatures() which always fails
     * for patched APKs. Since the key app is a paid app on Google Play,
     * its presence on the device is a reasonable indicator of a legitimate
     * purchase.
     *
     * This method obtains Context from b0.r — the same static Application
     * Context field that s3.a.d() uses for its own checkSignatures() call
     * at bytecode index #3. Therefore b0.r is guaranteed to be initialized.
     *
     * @param context the Application context (from b0.r)
     * @return true if the key app is installed, false otherwise
     */
    public static boolean isKeyAppInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(KEY_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // Key app is not installed — not a premium user
            return false;
        } catch (Throwable ignored) {
            // Any runtime error — fail safe (not premium)
            return false;
        }
    }
}
