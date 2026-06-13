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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fstop.photo.FilmStrip;
import com.fstop.photo.activity.ViewImageActivityNew;

import c3.t;

/**
 * Helper class for the "Quick Select" feature in F-Stop's media viewer.
 *
 * Adds a select/deselect toggle icon button positioned BELOW the header bar
 * in the media viewer. The button:
 * 1. Auto-hides when toolbar is hidden (fullscreen mode) — via bytecode hooks
 *    on I2() and a4() methods
 * 2. Shows selection indicator on the currently viewed image
 * 3. Functions as a toggle (select/deselect)
 * 4. Syncs selection state with FilmStrip thumbnails bidirectionally
 *
 * IMPORTANT: All field/method names match the REAL DEX bytecode, verified
 * via androguard against the actual APK DEX files:
 * - ViewImageActivityNew.u0 = l3.k data holder (public)
 * - ViewImageActivityNew.H0 = boolean toolbar visibility (public)
 * - ViewImageActivityNew.Q0 = FilmStrip instance
 * - ViewImageActivityNew.L0 = MyAppToolbar
 * - l3.k.o() = getCurrentItem -> c3.t
 * - c3.t.U()Z = isSelected (checks c3.t.L > 0)
 * - c3.t.X(Z)V = setSelected (sets c3.t.s = val)
 * - FilmStrip.l = ArrayList of p1 thumbnails (public)
 * - FilmStrip.D = boolean selection mode enabled (public)
 * - FilmStrip.invalidate() = trigger redraw to show checkmarks
 * - p1.m = boolean selected state for FilmStrip drawing (public)
 * - b0.r = static Application Context
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;

    /**
     * Add the quick select button to the media viewer layout.
     * Called from the patched onCreateOptionsMenu (after menu inflation).
     *
     * Places the button as a floating ImageView below the header bar,
     * on the right side of the screen. Also sets initial visibility
     * based on current toolbar state (H0 field).
     */
    public static void addSelectButton(Activity activity) {
        try {
            View existing = activity.findViewById(BUTTON_ID);
            if (existing != null) {
                // Already added, just update state
                updateButtonState(activity, existing);
                return;
            }

            // Find the main content view to add our button
            ViewGroup contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) return;

            // Create the select button as an ImageView
            int buttonSize = (int) (40 * activity.getResources().getDisplayMetrics().density);
            int marginEnd = (int) (12 * activity.getResources().getDisplayMetrics().density);
            int marginTop = (int) (56 * activity.getResources().getDisplayMetrics().density); // Below standard toolbar height

            boolean isSelected = isCurrentItemSelected(activity);

            ImageView selectButton = new ImageView(activity);
            selectButton.setId(BUTTON_ID);
            selectButton.setImageDrawable(createSelectIcon(activity, isSelected));
            selectButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            selectButton.setClickable(true);
            selectButton.setFocusable(true);

            // Set background with ripple effect
            android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1A000000"));
            android.graphics.drawable.RippleDrawable ripple =
                new android.graphics.drawable.RippleDrawable(
                    rippleColor,
                    createCircleBackground(activity),
                    null
                );
            selectButton.setBackground(ripple);

            // Layout params: positioned below toolbar, right side
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
            params.gravity = Gravity.END | Gravity.TOP;
            params.setMargins(0, marginTop, marginEnd, 0);

            selectButton.setOnClickListener(v -> {
                try {
                    toggleCurrentItemSelection(activity);
                    boolean nowSelected = isCurrentItemSelected(activity);
                    selectButton.setImageDrawable(createSelectIcon(activity, nowSelected));
                } catch (Throwable ignored) {}
            });

            contentView.addView(selectButton, params);

            // Set initial visibility based on toolbar state (H0 field)
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                // H0 = true means toolbar is visible (not fullscreen)
                // H0 = false means toolbar is hidden (fullscreen)
                selectButton.setVisibility(vian.H0 ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Update the select button's icon to reflect the current selection state.
     * Called from onPrepareOptionsMenu to keep in sync when navigating images.
     */
    public static void updateSelectButtonIcon(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton instanceof ImageView) {
                updateButtonState(activity, selectButton);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Called when toolbar is shown (a4() method invoked).
     * Makes the quick select button visible.
     */
    public static void onToolbarShown(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                selectButton.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Called when toolbar is hidden (I2() method invoked).
     * Makes the quick select button hidden.
     */
    public static void onToolbarHidden(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                selectButton.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Toggle the selection state of the currently displayed media item.
     *
     * Uses the REAL DEX field/method names:
     * - ViewImageActivityNew.u0 (l3.k data holder, public)
     * - l3.k.o() to get current c3.t item
     * - c3.t.U() to check if selected (checks L > 0)
     * - c3.t.X(boolean) to set selected (sets s = val)
     *
     * After toggling, also syncs the FilmStrip thumbnail (p1.m) and
     * triggers a redraw via FilmStrip.invalidate().
     */
    private static void toggleCurrentItemSelection(Activity activity) {
        if (!(activity instanceof ViewImageActivityNew)) return;
        ViewImageActivityNew vian = (ViewImageActivityNew) activity;

        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            boolean isSelected = currentItem.U();  // Real DEX: U()Z checks L > 0
            currentItem.X(!isSelected);            // Real DEX: X(Z)V sets s = val

            // Sync FilmStrip thumbnail selection state
            syncFilmStripSelection(vian, currentItem, !isSelected);
        } catch (Throwable ignored) {}
    }

    /**
     * Check if the currently displayed media item is selected.
     */
    private static boolean isCurrentItemSelected(Activity activity) {
        try {
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                t currentItem = vian.u0 != null ? vian.u0.o() : null;
                if (currentItem != null) {
                    return currentItem.U();  // Real DEX: U()Z
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Sync the FilmStrip thumbnail selection state with c3.t state.
     *
     * FilmStrip draws checkmarks based on p1.m (boolean selected),
     * while the app logic uses c3.t.U()/X() (which checks/sets c3.t.L/s).
     * We need to keep both in sync for bidirectional selection.
     *
     * After updating p1.m, we enable FilmStrip.D (selection mode) and
     * call invalidate() to trigger a redraw showing the checkmark.
     */
    private static void syncFilmStripSelection(ViewImageActivityNew vian, t item, boolean selected) {
        try {
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null) return;

            // Enable selection mode in FilmStrip so checkmarks are drawn
            filmStrip.D = true;

            // Find the matching p1 thumbnail in FilmStrip.l and update its m field
            java.util.ArrayList thumbnails = filmStrip.l;
            if (thumbnails == null) return;

            String itemPath = item.j;  // c3.t.j = file path (public String)

            for (int i = 0; i < thumbnails.size(); i++) {
                Object thumbObj = thumbnails.get(i);
                if (thumbObj instanceof com.fstop.photo.p1) {
                    com.fstop.photo.p1 thumb = (com.fstop.photo.p1) thumbObj;
                    // p1.h = file path string, compare with c3.t.j
                    if (itemPath != null && itemPath.equals(thumb.h)) {
                        thumb.m = selected;  // p1.m = boolean selected state
                        break;
                    }
                }
            }

            // Trigger FilmStrip redraw to show/hide checkmark
            filmStrip.invalidate();
        } catch (Throwable ignored) {}
    }

    private static void updateButtonState(Activity activity, View button) {
        if (button instanceof ImageView) {
            boolean isSelected = isCurrentItemSelected(activity);
            ((ImageView) button).setImageDrawable(createSelectIcon(activity, isSelected));
        }
    }

    /**
     * Create a circular background for the button.
     */
    private static Drawable createCircleBackground(Context context) {
        int size = (int) (40 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.parseColor("#0DFFFFFF")); // Very subtle white
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /**
     * Create a select/deselect icon as a BitmapDrawable.
     * - Unselected: outline circle (gray)
     * - Selected: filled circle with checkmark (green)
     */
    private static Drawable createSelectIcon(Context context, boolean isSelected) {
        int size = (int) (24 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        float cx = size / 2f;
        float cy = size / 2f;
        float radius = size * 0.38f;

        if (isSelected) {
            // Selected: filled circle background
            paint.setColor(Color.parseColor("#4CAF50")); // Green
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);

            // Checkmark
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.07f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float checkSize = size * 0.16f;
            canvas.drawLine(
                cx - checkSize, cy + checkSize * 0.1f,
                cx - checkSize * 0.15f, cy + checkSize * 0.7f,
                paint
            );
            canvas.drawLine(
                cx - checkSize * 0.15f, cy + checkSize * 0.7f,
                cx + checkSize, cy - checkSize * 0.5f,
                paint
            );
        } else {
            // Unselected: circle outline
            paint.setColor(Color.parseColor("#B0BEC5")); // Light gray
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.05f);
            canvas.drawCircle(cx, cy, radius, paint);

            // Small dot in center (unselected indicator)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#90A4AE"));
            canvas.drawCircle(cx, cy, size * 0.06f, paint);
        }

        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
