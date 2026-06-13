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
 * 2. Shows selection indicator on the currently viewed image (icon changes)
 * 3. Functions as a toggle (select/deselect)
 * 4. Syncs selection state with FilmStrip thumbnails bidirectionally
 *
 * CRITICAL DEX FINDINGS (verified via androguard bytecode analysis):
 *
 * c3.t has TWO selection state fields that must be kept in sync:
 * - c3.t.s (boolean, private) — the authoritative selected flag
 * - c3.t.L (int, private) — used by U() to check if L > 0
 *
 * c3.t methods:
 * - z()Z: returns field s (boolean selected) — RELIABLE for checking
 * - Q()Z: also returns field s (same as z())
 * - U()Z: returns (L > 0) — checks the int field, NOT s!
 * - X(Z)V: sets s = val, and if selecting, also calls b0.x() and sets B0
 * - c0(I)V: sets L = val
 *
 * BUG IN PREVIOUS VERSION: toggleCurrentItemSelection() called X(!isSelected)
 * which only sets field 's', but isCurrentItemSelected() used U() which checks
 * field 'L'. Since L was never updated by X(), U() always returned the same
 * value, making the selection indicator appear broken.
 *
 * FIX: Use z() for checking selection (returns 's' which IS updated by X()),
 * and also call c0() when toggling to keep L in sync (so other app code
 * that uses U() also sees the correct state).
 *
 * FilmStrip fields/methods:
 * - FilmStrip.l = ArrayList of p1 thumbnails (public)
 * - FilmStrip.D = boolean selection mode enabled (public)
 * - FilmStrip.e(String)p1 = finds thumbnail by file path
 * - FilmStrip.invalidate() = trigger redraw
 *
 * p1 fields:
 * - p1.h = String file path
 * - p1.m = boolean selected state (used for drawing checkmarks)
 * - p1.b() = returns h (file path)
 *
 * ViewImageActivityNew fields:
 * - u0 = l3.k data holder (public)
 * - H0 = boolean toolbar visibility (public)
 * - Q0 = FilmStrip instance
 *
 * l3.k methods:
 * - o()Lc3/t; = gets current c3.t item (based on index field f)
 *
 * b0 fields:
 * - r = static Application Context
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;

    /**
     * Add the quick select button to the media viewer layout.
     * Called from the patched onCreateOptionsMenu (after menu inflation).
     */
    public static void addSelectButton(Activity activity) {
        try {
            View existing = activity.findViewById(BUTTON_ID);
            if (existing != null) {
                updateButtonState(activity, existing);
                return;
            }

            ViewGroup contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) return;

            int buttonSize = (int) (40 * activity.getResources().getDisplayMetrics().density);
            int marginEnd = (int) (12 * activity.getResources().getDisplayMetrics().density);
            int marginTop = (int) (56 * activity.getResources().getDisplayMetrics().density);

            boolean isSelected = isCurrentItemSelected(activity);

            ImageView selectButton = new ImageView(activity);
            selectButton.setId(BUTTON_ID);
            selectButton.setImageDrawable(createSelectIcon(activity, isSelected));
            selectButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            selectButton.setClickable(true);
            selectButton.setFocusable(true);

            // Ripple background
            android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1A000000"));
            android.graphics.drawable.RippleDrawable ripple =
                new android.graphics.drawable.RippleDrawable(
                    rippleColor,
                    createCircleBackground(activity),
                    null
                );
            selectButton.setBackground(ripple);

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
     * CRITICAL: We must update BOTH fields to keep selection state consistent:
     * 1. Call X(boolean) to set field 's' (the authoritative boolean flag)
     *    — X() also sets B0 when selecting (calls b0.x())
     * 2. Call c0(int) to set field 'L' (the int counter used by U())
     *    — c0(1) when selecting (positive = selected per U())
     *    — c0(0) when deselecting (0 = not selected per U())
     *
     * If we only call X() without c0(), other app code that uses U() to check
     * selection (including the toolbar selection count display) will not see
     * the change, and our own isCurrentItemSelected() using z() will show the
     * correct state while the rest of the app disagrees.
     */
    private static void toggleCurrentItemSelection(Activity activity) {
        if (!(activity instanceof ViewImageActivityNew)) return;
        ViewImageActivityNew vian = (ViewImageActivityNew) activity;

        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            // Use z() to check selection — returns field 's' which is the
            // authoritative boolean flag. U() checks field 'L' which may
            // not be in sync if previous toggles didn't update it.
            boolean isSelected = currentItem.z();  // Real DEX: z()Z returns s
            boolean newState = !isSelected;

            // Update both fields to keep everything in sync:
            currentItem.X(newState);   // Sets s = newState (also sets B0 if selecting)
            currentItem.c0(newState ? 1 : 0);  // Sets L = 1 or 0 (keeps U() in sync)

            // Sync FilmStrip thumbnail selection state
            syncFilmStripSelection(vian, currentItem, newState);
        } catch (Throwable ignored) {}
    }

    /**
     * Check if the currently displayed media item is selected.
     *
     * Uses z() which returns field 's' (the authoritative boolean selected flag).
     * Previous version used U() which checks field 'L' (int) — but X() only
     * updates 's', not 'L', causing U() to return stale values after toggling.
     */
    private static boolean isCurrentItemSelected(Activity activity) {
        try {
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                t currentItem = vian.u0 != null ? vian.u0.o() : null;
                if (currentItem != null) {
                    return currentItem.z();  // Real DEX: z()Z returns field s
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Sync the FilmStrip thumbnail selection state with c3.t state.
     *
     * FilmStrip draws checkmarks based on p1.m (boolean selected),
     * while the app logic uses c3.t.z()/X() (which checks/sets c3.t.s)
     * and c3.t.U() (which checks c3.t.L).
     *
     * After updating both s and L fields, we also need to:
     * 1. Enable FilmStrip selection mode (D = true)
     * 2. Update p1.m on the matching thumbnail
     * 3. Trigger FilmStrip redraw to show/hide the checkmark
     *
     * We use FilmStrip.e(String) to find the matching p1 thumbnail
     * by file path, comparing c3.t.j (file path) with p1.h (file path).
     */
    private static void syncFilmStripSelection(ViewImageActivityNew vian, t item, boolean selected) {
        try {
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null) return;

            // Enable selection mode in FilmStrip so checkmarks are drawn
            filmStrip.D = true;

            // Use FilmStrip.e(String) to find the matching p1 thumbnail
            // This method compares p1.h with the given path string
            String itemPath = item.j;  // c3.t.j = file path (public String)

            if (itemPath != null) {
                Object thumbObj = filmStrip.e(itemPath);  // FilmStrip.e(String)p1
                if (thumbObj instanceof com.fstop.photo.p1) {
                    com.fstop.photo.p1 thumb = (com.fstop.photo.p1) thumbObj;
                    thumb.m = selected;  // p1.m = boolean selected state for drawing
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
        paint.setColor(Color.parseColor("#0DFFFFFF"));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /**
     * Create a select/deselect icon as a BitmapDrawable.
     * - Unselected: outline circle (gray) with dot
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
