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
 * needing the "Unlock premium" bypass patch.
 *
 * IMPORTANT: This class uses reflection to access ActivityThread (a hidden
 * Android API) to obtain the Application context internally, rather than
 * accepting a Context parameter. This avoids relying on obfuscated field
 * names (like b0.r) from the APK that may differ between decompiler output
 * and the actual DEX bytecode.
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
     * This method obtains Context internally via ActivityThread reflection,
     * avoiding any dependency on obfuscated field names from the APK.
     *
     * @return true if the key app is installed, false otherwise
     */
    public static boolean isKeyAppInstalled() {
        try {
            // Use reflection to access ActivityThread.currentActivityThread()
            // since it's a hidden API not in the public Android SDK.
            // ActivityThread.currentActivityThread().getApplication() returns
            // the Application context, which we can use to get PackageManager.
            Class<?> activityThreadClass =
                Class.forName("android.app.ActivityThread");
            Object activityThread =
                activityThreadClass.getMethod("currentActivityThread")
                    .invoke(null);
            if (activityThread == null) return false;

            Context context = (Context)
                activityThreadClass.getMethod("getApplication")
                    .invoke(activityThread);
            if (context == null) return false;

            context.getPackageManager()
                .getPackageInfo(KEY_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // Key app is not installed — not a premium user
            return false;
        } catch (Throwable ignored) {
            // Any reflection or runtime error — fail safe (not premium)
            return false;
        }
    }
}
