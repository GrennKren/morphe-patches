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

import com.fstop.photo.activity.ViewImageActivityNew;

import c3.t;

/**
 * Helper class for the "Quick Select" feature in F-Stop's media viewer.
 *
 * Adds a select/deselect toggle icon button positioned BELOW the header bar
 * in the media viewer. This is NOT inside the 3-dot menu or the toolbar itself,
 * but as a standalone floating icon button that appears below the toolbar area.
 *
 * The button follows the toolbar show/hide behavior:
 * - Visible when toolbar is shown
 * - Hidden when in fullscreen mode
 * - Easy one-tap access to toggle selection
 *
 * The icon changes to indicate current selection state:
 * - Unselected: outline checkbox (gray)
 * - Selected: filled checkbox with checkmark (green)
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;

    /**
     * Add the quick select button to the media viewer layout.
     * Called from the patched onCreateOptionsMenu (or after setContentView).
     *
     * Places the button as a floating ImageView below the header bar,
     * on the right side of the screen.
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
            int[][] states = new int[][] { new int[] { android.R.attr.state_pressed } };
            int[] colors = new int[] { Color.parseColor("#1A000000") };
            android.content.res.ColorStateList rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A000000"));
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
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
     * Show or hide the select button based on toolbar visibility.
     * Called when toolbar show/hide state changes.
     */
    public static void setSelectButtonVisible(Activity activity, boolean visible) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                selectButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateButtonState(Activity activity, View button) {
        if (button instanceof ImageView) {
            boolean isSelected = isCurrentItemSelected(activity);
            ((ImageView) button).setImageDrawable(createSelectIcon(activity, isSelected));
        }
    }

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

    private static boolean isCurrentItemSelected(Activity activity) {
        try {
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                t currentItem = vian.f8486u0 != null ? vian.f8486u0.o() : null;
                if (currentItem != null) {
                    return currentItem.z();
                }
            }
        } catch (Throwable ignored) {}
        return false;
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
     * - Unselected: outline circle with checkmark outline (gray)
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
