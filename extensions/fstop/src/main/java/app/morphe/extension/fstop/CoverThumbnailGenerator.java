/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Extension class for the Generate cover thumbnails patch.
 *
 * Called from patched bytecode in SettingsFragmentCache.onCreatePreferences().
 * Uses reflection to avoid compile-time dependency on obfuscated AndroidX
 * preference classes (PreferenceFragmentCompat → h, OnPreferenceClickListener → d, etc.).
 *
 * DATABASE SCHEMA (verified from APK decompilation):
 * - IncludedFolder(_ID INTEGER PRIMARY KEY, FullPath TEXT UNIQUE)
 * - FolderData(_ID INTEGER PRIMARY KEY, FullPath TEXT UNIQUE, ThumbnailImageId INTEGER)
 * - Thumbnail(_ID INTEGER PRIMARY KEY, ImageId INTEGER, FullPath TEXT, MicroThumbnail BLOB)
 * - Image table has columns: _ID, FullPath, Folder, Orientation, IsVideo, ...
 *
 * THUMBNAIL GENERATION PIPELINE:
 * 1. Query IncludedFolder for all included folder paths
 * 2. If empty (no included folders set), query all distinct Folder values from Image table
 * 3. For each folder:
 *    a. Try FolderData.ThumbnailImageId → get that image's path and ID
 *    b. If no FolderData entry, get first image in folder (ORDER BY _ID LIMIT 1)
 *    c. Check if Thumbnail already exists for this image (skip if so)
 *    d. Generate thumbnail bitmap using BitmapFactory with inSampleSize
 *    e. Apply EXIF orientation if available
 *    f. Compress to JPEG and save to Thumbnail table
 * 4. Update notification with progress throughout
 */
@SuppressWarnings("unused")
public class CoverThumbnailGenerator {

    private static final String CHANNEL_ID = "cover_thumb_gen";
    private static final int NOTIFICATION_ID = 2001;
    private static final int THUMBNAIL_SIZE = 256;

    /**
     * Inject a "Generate cover thumbnails" preference into the Cache settings screen.
     * Called from patched bytecode via invoke-static {p0}.
     *
     * Uses reflection throughout because AndroidX preference classes are obfuscated
     * in the APK (PreferenceFragmentCompat → h, Preference.d → OnPreferenceClickListener).
     * At compile time we only have Object; at runtime the real classes are available.
     *
     * @param fragmentObj The SettingsFragmentCache instance (passed as Object)
     */
    public static void injectPreference(final Object fragmentObj) {
        try {
            Class<?> fragClass = fragmentObj.getClass();

            // Call getContext() — available on all Fragment subclasses
            Method getContextMethod = fragClass.getMethod("getContext");
            final Context context = (Context) getContextMethod.invoke(fragmentObj);
            if (context == null) return;

            // Call getPreferenceScreen()
            Method getScreenMethod = fragClass.getMethod("getPreferenceScreen");
            Object screen = getScreenMethod.invoke(fragmentObj);
            if (screen == null) return;

            // Load Preference class
            Class<?> prefClass = Class.forName("androidx.preference.Preference");

            // Create new Preference(context)
            Object pref = prefClass.getConstructor(Context.class).newInstance(context);

            // Set key, title, summary
            prefClass.getMethod("setKey", String.class).invoke(pref, "generateCoverThumbnails");
            prefClass.getMethod("setTitle", CharSequence.class).invoke(pref, "Generate cover thumbnails");
            prefClass.getMethod("setSummary", CharSequence.class)
                .invoke(pref, "Generate thumbnails for the cover image of each included folder");

            // Add to preference screen
            screen.getClass().getMethod("addPreference", prefClass).invoke(screen, pref);

            // Set click listener using Proxy for the obfuscated interface
            // Interface: androidx.preference.Preference$d (OnPreferenceClickListener after R8)
            // Method: boolean a(Preference) → onPreferenceClick(Preference)
            Class<?> listenerInterface = Class.forName("androidx.preference.Preference$d");
            Object clickListener = Proxy.newProxyInstance(
                listenerInterface.getClassLoader(),
                new Class<?>[]{listenerInterface},
                new ClickListenerInvocationHandler(context)
            );

            prefClass.getMethod("setOnPreferenceClickListener", listenerInterface)
                .invoke(pref, clickListener);

        } catch (Throwable ignored) {
            // Silently fail — if reflection fails, the preference simply won't appear
        }
    }

    // ========================================================================
    // NOTIFICATION HELPERS
    // ========================================================================

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Cover Thumbnail Generation",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Progress for cover thumbnail generation");
            channel.setShowBadge(false);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private static void updateProgressNotification(Context context, String title,
                                                    String text, int current, int total) {
        try {
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification notification = buildNotification(context, title, text, current, total);
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Throwable ignored) {}
    }

    private static Notification buildNotification(Context context, String title,
                                                   String text, int current, int total) {
        // Use Notification.Builder (not Compat) to avoid dependency issues
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(current < total);

        if (total > 0) {
            builder.setProgress(total, current, false);
        }

        // Allow cancellation when complete
        if (current >= total) {
            builder.setOngoing(false)
                .setAutoCancel(true);
        }

        return builder.build();
    }

    // ========================================================================
    // DATABASE ACCESS (via reflection)
    // ========================================================================

    /**
     * Get the app's SQLiteDatabase instance by finding it through reflection.
     *
     * Strategy: Look for a static field in b0 that contains an object with
     * an SQLiteDatabase field. This is robust against field name obfuscation
     * because we search by type rather than by name.
     *
     * b0 has a static field (jadx: f8689p) of type e3.b (database helper),
     * which has an instance field (jadx: f37078a) of type SQLiteDatabase.
     */
    private static SQLiteDatabase getDatabase(Context context) {
        try {
            Class<?> b0Class = Class.forName("com.fstop.photo.b0");
            for (Field f : b0Class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object fieldValue = f.get(null);
                if (fieldValue == null) continue;

                // Check if this field's class has a SQLiteDatabase member
                for (Field f2 : fieldValue.getClass().getDeclaredFields()) {
                    if (SQLiteDatabase.class.isAssignableFrom(f2.getType())) {
                        f2.setAccessible(true);
                        SQLiteDatabase db = (SQLiteDatabase) f2.get(fieldValue);
                        if (db != null && db.isOpen()) {
                            return db;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ========================================================================
    // THUMBNAIL GENERATION
    // ========================================================================

    private static void startGeneration(final Context context) {
        createNotificationChannel(context);

        new Thread(() -> {
            try {
                SQLiteDatabase db = getDatabase(context);
                if (db == null || !db.isOpen()) {
                    updateProgressNotification(context, "Cover Thumbnails",
                        "Could not access database", 0, 0);
                    return;
                }

                // Get included folder paths
                List<String> folderPaths = getIncludedFolderPaths(db);

                // If no included folders configured, get all distinct folders
                if (folderPaths.isEmpty()) {
                    folderPaths = getAllImageFolders(db);
                }

                if (folderPaths.isEmpty()) {
                    updateProgressNotification(context, "Cover Thumbnails",
                        "No folders found", 0, 0);
                    return;
                }

                int total = folderPaths.size();
                int processed = 0;
                int generated = 0;

                updateProgressNotification(context, "Cover Thumbnails",
                    "Starting... (0/" + total + ")", 0, total);

                for (String folderPath : folderPaths) {
                    processed++;

                    try {
                        String folderName = new File(folderPath).getName();

                        // Find cover image for this folder
                        CoverImageData coverImage = getCoverImageData(db, folderPath);
                        if (coverImage == null) {
                            updateProgressNotification(context, "Cover Thumbnails",
                                folderName + " — no images found (" + processed + "/" + total + ")",
                                processed, total);
                            continue;
                        }

                        // Check if thumbnail already exists
                        if (hasThumbnail(db, coverImage.imagePath)) {
                            updateProgressNotification(context, "Cover Thumbnails",
                                folderName + " — already cached (" + processed + "/" + total + ")",
                                processed, total);
                            continue;
                        }

                        // Generate thumbnail
                        Bitmap thumbnail = generateThumbnail(coverImage.imagePath, coverImage.orientation);
                        if (thumbnail != null) {
                            saveThumbnail(db, coverImage.imageId, coverImage.imagePath, thumbnail);
                            thumbnail.recycle();
                            generated++;
                        }

                        updateProgressNotification(context, "Cover Thumbnails",
                            folderName + " — " + generated + " generated (" + processed + "/" + total + ")",
                            processed, total);

                    } catch (Throwable ignored) {
                        // Skip this folder on error, continue with next
                    }
                }

                updateProgressNotification(context, "Cover Thumbnails — Complete",
                    "Generated " + generated + "/" + total + " cover thumbnails",
                    total, total);

            } catch (Throwable e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                updateProgressNotification(context, "Cover Thumbnails — Error", msg, 0, 0);
            }
        }).start();
    }

    /**
     * Query the IncludedFolder table for all included folder paths.
     */
    private static List<String> getIncludedFolderPaths(SQLiteDatabase db) {
        List<String> paths = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT FullPath FROM IncludedFolder", null);
            if (cursor != null && cursor.moveToFirst()) {
                int colIndex = cursor.getColumnIndex("FullPath");
                if (colIndex >= 0) {
                    do {
                        String path = cursor.getString(colIndex);
                        if (path != null && !path.isEmpty()) {
                            paths.add(path);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return paths;
    }

    /**
     * Get all distinct folder paths from the Image table.
     * Used when no IncludedFolders are configured (app scans entire storage).
     */
    private static List<String> getAllImageFolders(SQLiteDatabase db) {
        List<String> paths = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                "SELECT DISTINCT Folder FROM Image WHERE Folder IS NOT NULL ORDER BY Folder",
                null
            );
            if (cursor != null && cursor.moveToFirst()) {
                int colIndex = cursor.getColumnIndex("Folder");
                if (colIndex >= 0) {
                    do {
                        String path = cursor.getString(colIndex);
                        if (path != null && !path.isEmpty()) {
                            paths.add(path);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return paths;
    }

    /**
     * Find the cover image for a folder.
     *
     * Strategy:
     * 1. Try FolderData table: FolderData.ThumbnailImageId → Image._ID
     * 2. Fallback: first image in that folder (ORDER BY _ID ASC LIMIT 1)
     */
    private static CoverImageData getCoverImageData(SQLiteDatabase db, String folderPath) {
        // Method 1: Check FolderData for explicit cover image
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                "SELECT Image._ID, Image.FullPath, Image.Orientation " +
                    "FROM Image INNER JOIN FolderData " +
                    "ON FolderData.ThumbnailImageId = Image._ID " +
                    "WHERE FolderData.FullPath = ?",
                new String[]{folderPath}
            );
            if (cursor != null && cursor.moveToFirst()) {
                CoverImageData data = new CoverImageData();
                data.imageId = cursor.getInt(0);
                data.imagePath = cursor.getString(1);
                data.orientation = cursor.getInt(2);
                cursor.close();
                if (data.imagePath != null && new File(data.imagePath).exists()) {
                    return data;
                }
            }
            if (cursor != null) cursor.close();
        } catch (Throwable ignored) {
            if (cursor != null) cursor.close();
        }

        // Method 2: Get first image in the folder
        try {
            cursor = db.rawQuery(
                "SELECT _ID, FullPath, Orientation FROM Image " +
                    "WHERE Folder = ? AND IsVideo = 0 " +
                    "ORDER BY _ID ASC LIMIT 1",
                new String[]{folderPath}
            );
            if (cursor != null && cursor.moveToFirst()) {
                CoverImageData data = new CoverImageData();
                data.imageId = cursor.getInt(0);
                data.imagePath = cursor.getString(1);
                data.orientation = cursor.getInt(2);
                cursor.close();
                if (data.imagePath != null && new File(data.imagePath).exists()) {
                    return data;
                }
            }
            if (cursor != null) cursor.close();
        } catch (Throwable ignored) {
            if (cursor != null) cursor.close();
        }

        return null;
    }

    /**
     * Check if a thumbnail already exists for the given image path.
     */
    private static boolean hasThumbnail(SQLiteDatabase db, String imagePath) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                "SELECT _ID FROM Thumbnail WHERE FullPath = ? AND MicroThumbnail IS NOT NULL",
                new String[]{imagePath}
            );
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Generate a thumbnail bitmap from an image file path.
     *
     * Uses BitmapFactory with inSampleSize for memory-efficient decoding,
     * then scales to the target thumbnail size. Applies EXIF orientation
     * correction based on the orientation value from the database.
     *
     * @param imagePath Full path to the image file
     * @param orientation EXIF orientation value from the Image table
     * @return Generated thumbnail Bitmap, or null if failed
     */
    private static Bitmap generateThumbnail(String imagePath, int orientation) {
        try {
            // First pass: decode bounds only (inJustDecodeBounds = true)
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, options);

            if (options.outWidth <= 0 || options.outHeight <= 0) return null;

            // Calculate inSampleSize for efficient downscaling
            options.inSampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight, THUMBNAIL_SIZE, THUMBNAIL_SIZE
            );
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Save memory

            // Second pass: decode at sampled size
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);
            if (bitmap == null) return null;

            // Scale to exact thumbnail size if needed
            if (bitmap.getWidth() != THUMBNAIL_SIZE || bitmap.getHeight() != THUMBNAIL_SIZE) {
                // Calculate aspect-ratio-preserving dimensions
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                float scale = Math.min(
                    (float) THUMBNAIL_SIZE / width,
                    (float) THUMBNAIL_SIZE / height
                );
                int scaledWidth = Math.round(width * scale);
                int scaledHeight = Math.round(height * scale);

                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                bitmap = scaled;
            }

            // Apply EXIF orientation correction
            if (orientation > 0 && orientation != 1) {
                Matrix matrix = new Matrix();
                switch (orientation) {
                    case 2: matrix.setScale(-1, 1); break;           // FLIP_HORIZONTAL
                    case 3: matrix.setRotate(180); break;             // ROTATE_180
                    case 4: matrix.setScale(1, -1); break;           // FLIP_VERTICAL
                    case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;  // TRANSPOSE
                    case 6: matrix.setRotate(90); break;              // ROTATE_90
                    case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break; // TRANSVERSE
                    case 8: matrix.setRotate(270); break;             // ROTATE_270
                }
                Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                );
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }

            return bitmap;

        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Calculate inSampleSize for BitmapFactory.Options.
     * Returns a power-of-2 value that downsamples the image to be at least
     * the requested width/height.
     */
    private static int calculateInSampleSize(int srcWidth, int srcHeight,
                                              int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            int halfHeight = srcHeight / 2;
            int halfWidth = srcWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Save a thumbnail to the SQLite Thumbnail table.
     *
     * Follows the same pattern as e3.b.Y1(int, String, Bitmap):
     * - Compress bitmap to JPEG (or PNG for SVG/1px images)
     * - INSERT or UPDATE the Thumbnail row by FullPath
     */
    private static void saveThumbnail(SQLiteDatabase db, int imageId,
                                       String imagePath, Bitmap bitmap) {
        try {
            // Compress bitmap to byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (bitmap.getWidth() <= 1) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            }
            byte[] blob = baos.toByteArray();

            // Check if thumbnail already exists for this path
            boolean exists = false;
            int existingId = -1;
            Cursor cursor = null;
            try {
                cursor = db.rawQuery(
                    "SELECT _ID FROM Thumbnail WHERE FullPath = ?",
                    new String[]{imagePath}
                );
                if (cursor != null && cursor.moveToFirst()) {
                    exists = true;
                    existingId = cursor.getInt(0);
                }
            } finally {
                if (cursor != null) cursor.close();
            }

            // Use direct SQL for reliable upsert (no ContentValues dependency issues)
            if (exists) {
                Object[] bindArgs = new Object[]{imageId, blob, existingId};
                db.execSQL(
                    "UPDATE Thumbnail SET ImageId = ?, MicroThumbnail = ? WHERE _ID = ?",
                    bindArgs
                );
            } else {
                Object[] bindArgs = new Object[]{imagePath, imageId, blob};
                db.execSQL(
                    "INSERT INTO Thumbnail (FullPath, ImageId, MicroThumbnail) VALUES (?, ?, ?)",
                    bindArgs
                );
            }

        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    /**
     * Simple data class for cover image information from the database.
     */
    private static class CoverImageData {
        int imageId;
        String imagePath;
        int orientation;
    }

    /**
     * InvocationHandler for the Preference.OnPreferenceClickListener proxy.
     *
     * The interface method is obfuscated: boolean a(Preference) → onPreferenceClick(Preference)
     * We match by return type (boolean) to be robust against name obfuscation.
     */
    private static class ClickListenerInvocationHandler implements InvocationHandler {
        private final Context context;

        ClickListenerInvocationHandler(Context context) {
            this.context = context;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // The click listener has a single method returning boolean
            if (method.getReturnType() == boolean.class) {
                startGeneration(context);
                return true;
            }
            // Handle Object methods
            if (method.getName().equals("toString")) return "CoverThumbnailClickListener";
            if (method.getName().equals("hashCode")) return System.identityHashCode(this);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        }
    }
}
