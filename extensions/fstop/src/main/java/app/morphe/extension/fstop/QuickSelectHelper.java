/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;

import com.fstop.photo.activity.ViewImageActivityNew;

import c3.t;

/**
 * Helper class for the "Quick Select" feature in F-Stop's media viewer.
 *
 * Adds a select/toggle button to the toolbar menu that allows the user
 * to quickly select/deselect the currently viewed image or video without
 * needing to long-press on the FilmStrip thumbnail.
 *
 * This is especially useful because:
 * - In the grid view, users must long-press a thumbnail to select it
 * - In the media viewer's FilmStrip, users must also long-press
 * - There was no quick way to select from the toolbar/header area
 *
 * The select button appears in the toolbar (NOT in fullscreen mode)
 * and toggles the selection state of the currently displayed media item.
 * Its icon changes to indicate the current selection state:
 * - Unselected: outline checkbox icon
 * - Selected: filled checkbox icon
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    /**
     * Custom menu item ID for the select button.
     * Uses a value that won't conflict with F-Stop's existing menu IDs.
     * F-Stop uses IDs in the 0x7F09xxxx range (C0333R.id).
     * We use 0x7f09fffe as a safe custom ID.
     */
    public static final int MENU_ITEM_ID = 0x7f09fffe;

    /**
     * Add the select menu item to the toolbar menu.
     *
     * Called from the patched onCreateOptionsMenu after the menu is inflated.
     * Adds a "Select" item that will appear in the toolbar, following
     * the existing menu style.
     *
     * @param activity The ViewImageActivityNew instance
     * @param menu The toolbar menu to add the item to
     */
    public static void addSelectMenuItem(Activity activity, Menu menu) {
        try {
            MenuItem selectItem = menu.add(
                Menu.NONE,
                MENU_ITEM_ID,
                Menu.FIRST,  // Show at the beginning of the action items
                "Select"
            );

            // Set the icon based on current selection state
            boolean isSelected = isCurrentItemSelected(activity);
            selectItem.setIcon(createSelectIcon(activity, isSelected));
            selectItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            selectItem.setOnMenuItemClickListener(item -> {
                try {
                    toggleCurrentItemSelection(activity);
                    // Update icon after toggling
                    boolean nowSelected = isCurrentItemSelected(activity);
                    item.setIcon(createSelectIcon(activity, nowSelected));
                } catch (Throwable e) {
                    // Catch Throwable (not Exception) to handle NoSuchMethodError etc.
                }
                return true;
            });
        } catch (Throwable e) {
            // Catch Throwable to handle descriptor mismatches gracefully
        }
    }

    /**
     * Update the select button's icon to reflect the current selection state.
     *
     * Called from onPrepareOptionsMenu to keep the icon in sync
     * when the user navigates between images.
     *
     * @param activity The ViewImageActivityNew instance
     * @param menu The toolbar menu
     */
    public static void updateSelectButtonIcon(Activity activity, Menu menu) {
        try {
            MenuItem selectItem = menu.findItem(MENU_ITEM_ID);
            if (selectItem != null) {
                boolean isSelected = isCurrentItemSelected(activity);
                selectItem.setIcon(createSelectIcon(activity, isSelected));
            }
        } catch (Throwable e) {
            // Silently ignore errors
        }
    }

    /**
     * Toggle the selection state of the currently viewed media item.
     *
     * @param activity The ViewImageActivityNew instance
     */
    private static void toggleCurrentItemSelection(Activity activity) {
        if (activity instanceof ViewImageActivityNew) {
            ViewImageActivityNew vian = (ViewImageActivityNew) activity;
            t currentItem = vian.f8486u0 != null ? vian.f8486u0.o() : null;
            if (currentItem != null) {
                boolean isSelected = currentItem.z();
                currentItem.X(!isSelected);
            }
        }
    }

    /**
     * Check if the currently viewed media item is selected.
     *
     * @param activity The ViewImageActivityNew instance
     * @return true if the current item is selected
     */
    private static boolean isCurrentItemSelected(Activity activity) {
        try {
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                t currentItem = vian.f8486u0 != null ? vian.f8486u0.o() : null;
                if (currentItem != null) {
                    return currentItem.z();
                }
            }
        } catch (Throwable e) {
            // Silently ignore errors
        }
        return false;
    }

    /**
     * Create a select/deselect icon as a BitmapDrawable.
     *
     * Generates a simple checkbox icon programmatically:
     * - Unselected: outline square with rounded corners
     * - Selected: filled square with checkmark
     *
     * @param context The context for resource access
     * @param isSelected Whether to draw the selected variant
     * @return A Drawable for the menu item icon
     */
    private static Drawable createSelectIcon(Context context, boolean isSelected) {
        int size = (int) (24 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        float padding = size * 0.15f;
        float cornerRadius = size * 0.1f;

        if (isSelected) {
            // Selected: filled background with checkmark
            paint.setColor(Color.parseColor("#4CAF50")); // Green
            paint.setStyle(Paint.Style.FILL);
            drawRoundRect(canvas, paint, padding, padding,
                size - padding, size - padding, cornerRadius);

            // Draw checkmark
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.08f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float cx = size / 2f;
            float cy = size / 2f;
            float checkSize = size * 0.22f;

            // Checkmark path: bottom-left to center to top-right
            canvas.drawLine(
                cx - checkSize, cy,
                cx - checkSize * 0.2f, cy + checkSize * 0.6f,
                paint
            );
            canvas.drawLine(
                cx - checkSize * 0.2f, cy + checkSize * 0.6f,
                cx + checkSize, cy - checkSize * 0.6f,
                paint
            );
        } else {
            // Unselected: outline square
            paint.setColor(Color.parseColor("#B0BEC5")); // Light gray
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.06f);
            drawRoundRect(canvas, paint, padding, padding,
                size - padding, size - padding, cornerRadius);
        }

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /**
     * Draw a rounded rectangle on the canvas.
     */
    private static void drawRoundRect(Canvas canvas, Paint paint,
                                       float left, float top,
                                       float right, float bottom,
                                       float radius) {
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }
}
