/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.io.File;

import hf.y0;
import kh.e;
import qe.d;

/**
 * Registry for per-extension default app preferences in FX Explorer.
 *
 * Stores file extension to app component (packageName/className) mappings
 * in SharedPreferences. This allows the "Open With Default" feature to
 * remember which app the user selected for a given file extension.
 *
 * This class is designed to be safe — all public methods that are called
 * from smali injections catch ALL exceptions and silently fail, so that
 * any bug here will NOT crash the host app.
 *
 * Flow:
 *   1. User clicks the (wildcard) button in "Open With Default" section
 *   2. OpenWithDefaultClickListener.onClick() is called
 *   3. If a stored default exists for this file extension, try to open
 *      directly using y0.h() (the app launch method). If successful,
 *      dismiss the dialog. If not, fall through.
 *   4. If no stored default, set selectingDefault=true, set MIME to wildcard,
 *      call y0.i() to re-resolve with wildcard (showing ALL apps).
 *   5. User picks an app → y0.h() is called → we intercept and save
 *      the default if selectingDefault flag is set.
 */
@SuppressWarnings("unused")
public class DefaultAppRegistry {

    private static final String PREFS_NAME = "fx_default_apps";
    private static final String KEY_PREFIX = "ext_";
    private static final String SEPARATOR = "\n";

    /** Flag indicating we're in "selecting default app" mode */
    private static boolean selectingDefault = false;

    /**
     * Set the "selecting default" flag.
     */
    public static void setSelectingDefault(boolean value) {
        selectingDefault = value;
    }

    /**
     * Determine whether auto-open with default app should be attempted.
     *
     * This uses call stack inspection to distinguish between two code paths
     * that both call y0.j(Context, kh/e, lf/b):
     *
     * 1. Single-click "Open" path:
     *    h0.b(1) → h0.F() → a.m() → b0.a() → y0.j()
     *    In this case, the user just tapped a file and expects it to open
     *    directly with the default app if one is stored.
     *
     * 2. Explicit "Open With" path:
     *    h0.b(2) → h0.G() → y0.j()
     *    In this case, the user hold-touched the file and clicked the
     *    bottom-right "Open With" button, explicitly requesting the dialog.
     *    We must NOT auto-open and must show the dialog instead.
     *
     * Both paths call y0.j() with the SAME parameters (lf/b is always null),
     * so parameter inspection cannot distinguish them. Stack trace is the
     * only reliable way.
     *
     * The key discriminator: class "hf.b0" appears in the stack ONLY for
     * the single-click path (b0.a() is the file-opener method). It does NOT
     * appear for the explicit "Open With" path.
     *
     * Performance note: Thread.getStackTrace() is "expensive" but this method
     * is called only once per file-open action (not in a tight loop), so the
     * overhead is negligible.
     *
     * @return true if auto-open should be attempted, false if the dialog
     *         should always be shown
     */
    public static boolean shouldAutoOpen() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack == null) return false;
            for (StackTraceElement element : stack) {
                if (element == null) continue;
                String className = element.getClassName();
                // "hf.b0" is the obfuscated class that contains the a() method
                // which is the single-click file-opener. If it's in the stack,
                // we arrived at y0.j() from the single-click "Open" path.
                if ("hf.b0".equals(className)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // If stack inspection fails, be safe and don't auto-open
        }
        return false;
    }

    /**
     * Check if we're in "selecting default" mode.
     */
    public static boolean isSelectingDefault() {
        return selectingDefault;
    }

    /**
     * Save a default app for a file extension.
     */
    public static void saveDefault(Context ctx, String extension, ResolveInfo ri) {
        if (ri == null || ri.activityInfo == null) return;
        saveDefault(ctx, extension, ri.activityInfo.packageName, ri.activityInfo.name);
    }

    /**
     * Save a default app for a file extension.
     */
    public static void saveDefault(Context ctx, String extension, String packageName, String className) {
        if (extension == null || packageName == null || className == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PREFIX + extension, packageName + SEPARATOR + className).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Get the default app for a file extension.
     * @return String array [packageName, className] or null if no default is stored
     */
    public static String[] getDefault(Context ctx, String extension) {
        if (extension == null || ctx == null) return null;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String value = prefs.getString(KEY_PREFIX + extension, null);
            if (value == null) return null;
            String[] parts = value.split(SEPARATOR, 2);
            if (parts.length != 2) return null;
            return parts;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the file extension from a filename.
     */
    public static String getFileExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * Try to open a file with its stored default app from within the dialog.
     * Called by OpenWithDefaultClickListener when the (wildcard) button is clicked.
     *
     * This method is SAFE - all exceptions are caught, it will never crash
     * the host app. If anything fails, it returns false.
     *
     * The approach: use the dialog's existing h() method to launch the app,
     * which is the same method used when the user clicks an app in the dialog.
     * This ensures proper intent construction, URI handling, and permissions.
     *
     * @param dialog The y0 dialog instance
     * @return true if the file was opened with the default app, false otherwise
     */
    public static boolean tryOpenWithDefault(y0 dialog) {
        if (dialog == null) return false;
        try {
            Context ctx = dialog.getContext();
            if (ctx == null) return false;

            e fileItem = dialog.X;
            if (fileItem == null) return false;

            String filename = fileItem.getName();
            String ext = getFileExtension(filename);
            if (ext == null) return false;

            String[] defaultApp = getDefault(ctx, ext);
            if (defaultApp == null) return false;

            // Create a ResolveInfo-like object to pass to y0.h()
            // But y0.h() takes a real ResolveInfo, so we need a different approach.
            // Instead, create the intent directly and start the activity.
            String packageName = defaultApp[0];
            String className = defaultApp[1];

            // Get the file URI from the dialog's resolver
            d resolver = dialog.f;
            if (resolver == null) return false;

            String mimeType = resolver.i; // MIME type from resolver
            Uri uri = null;

            // Try to get URI from resolver's file field or URI field
            File file = resolver.f;
            if (file != null) {
                uri = nextapp.fx.fileprovider.FileProvider.a(ctx, file);
            }
            if (uri == null) {
                uri = resolver.h; // Cloud file URI
            }
            if (uri == null) return false;

            // Create and launch the intent
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (mimeType != null) {
                intent.setDataAndType(uri, mimeType);
            } else {
                intent.setData(uri);
            }
            intent.setClassName(packageName, className);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            ctx.startActivity(intent);
            dialog.dismiss();
            return true;
        } catch (Exception e) {
            // Silently fail — never crash the host app
            return false;
        }
    }

    /**
     * Try to open a file directly with its stored default app, BEFORE
     * the "Open With" dialog is created. Called from the smali injection
     * in y0.j() (FileOpenDialogFingerprint hook).
     *
     * This method is SAFE - all exceptions are caught, it will never crash
     * the host app. If anything fails, it returns false and the normal
     * dialog flow proceeds.
     *
     * IMPORTANT DESIGN DECISION: This method takes only String parameters,
     * NOT kh/e. The smali injection code extracts filename and file path
     * by calling interface methods DIRECTLY in smali, then passes the
     * resulting strings to this method. This completely avoids ALL
     * descriptor mismatch issues because:
     * 1. The smali code runs inside the APK — it calls interface methods
     *    using the exact descriptors that exist in the DEX
     * 2. Our Java method only uses standard types (String, Context) which
     *    can never have descriptor mismatches
     * 3. Previously, calling fileItem.w() from Java compiled as
     *    invoke-interface Lkh/j;->w() (wrong! w() is in kh/e, not kh/j)
     *    → NoSuchMethodError → tryOpenDirectly always returned false!
     *
     * MIME type is derived from the file extension using Android's
     * MimeTypeMap, so we don't need to call p1.w() at all. This also
     * reduces the number of registers needed in the smali injection.
     *
     * @param filename  The file's name (from p1.getName() in smali)
     * @param pathStr   The file's path string (from p1.getPath().toString() in smali)
     * @param ctx       The Android context (p0 parameter in y0.j)
     * @return true if the file was opened with a stored default app, false otherwise
     */
    public static boolean tryOpenDirectly(String filename, String pathStr, Context ctx) {
        if (ctx == null || filename == null || pathStr == null) return false;
        try {
            String ext = getFileExtension(filename);
            if (ext == null) return false;

            String[] defaultApp = getDefault(ctx, ext);
            if (defaultApp == null) return false;

            String packageName = defaultApp[0];
            String className = defaultApp[1];

            // Ensure the path is absolute (hh/f.toString() may not include leading '/')
            if (!pathStr.startsWith("/")) {
                pathStr = "/" + pathStr;
            }

            if (pathStr.isEmpty()) return false;

            File file = new File(pathStr);
            if (!file.exists()) return false;

            Uri uri = nextapp.fx.fileprovider.FileProvider.a(ctx, file);
            if (uri == null) return false;

            // Derive MIME type from file extension
            String mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext);

            // Create ACTION_VIEW intent with the specific component
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (mimeType != null && !mimeType.isEmpty()) {
                intent.setDataAndType(uri, mimeType);
            } else {
                intent.setData(uri);
            }
            intent.setClassName(packageName, className);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            ctx.startActivity(intent);
            return true;
        } catch (Exception ex) {
            // If anything goes wrong, fall through to normal dialog flow
            return false;
        }
    }

    /**
     * Called from smali injection in y0.h() to save default app when
     * selectingDefault flag is set.
     *
     * This is the Part 4 hook — called at the START of y0.h().
     * It checks if we're in "selecting default" mode, and if so,
     * saves the launched app as the default for the current file's extension.
     *
     * @param dialog The y0 dialog instance ('this' in the instance method)
     * @param ri     The ResolveInfo of the launched app (p1 parameter)
     */
    public static void onAppLaunchedFromDialog(y0 dialog, ResolveInfo ri) {
        if (!selectingDefault) return;
        selectingDefault = false;
        if (dialog == null || ri == null || ri.activityInfo == null) return;
        try {
            Context ctx = dialog.getContext();
            if (ctx == null) return;

            e fileItem = dialog.X;
            if (fileItem == null) return;

            String filename = fileItem.getName();
            String ext = getFileExtension(filename);
            if (ext != null) {
                saveDefault(ctx, ext, ri);
            }
        } catch (Exception ignored) {
            // Silently fail — don't crash the app
        }
    }
}
