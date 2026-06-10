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
