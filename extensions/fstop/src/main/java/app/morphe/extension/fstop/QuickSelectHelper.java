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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fstop.photo.FilmStrip;
import com.fstop.photo.activity.ViewImageActivityNew;

import c3.t;

@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Add the quick select button to the media viewer layout.
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

            int density = (int) activity.getResources().getDisplayMetrics().density;
            int buttonSize = 40 * density;
            int marginEnd = 12 * density;
            int marginTop = 56 * density;

            boolean isSelected = isCurrentItemSelected(activity);

            ImageView selectButton = new ImageView(activity);
            selectButton.setId(BUTTON_ID);
            selectButton.setImageDrawable(createSelectIcon(activity, isSelected));
            selectButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            selectButton.setClickable(true);
            selectButton.setFocusable(true);

            // Solid white background with border for visibility
            selectButton.setBackground(createButtonBackground(activity));

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

            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                selectButton.setVisibility(vian.H0 ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Update the select button's icon — called from onPrepareOptionsMenu
     * AND from onPageSelected (via hook) for instant response on swipe.
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
     * Called when a page is selected (swipe). Updates button icon immediately
     * without waiting for onPrepareOptionsMenu.
     */
    public static void onPageChanged(Activity activity) {
        try {
            MAIN_HANDLER.post(() -> updateSelectButtonIcon(activity));
        } catch (Throwable ignored) {}
    }

    public static void onToolbarShown(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                selectButton.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    public static void onToolbarHidden(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                selectButton.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Toggle selection — updates BOTH s and L fields, syncs FilmStrip.
     */
    private static void toggleCurrentItemSelection(Activity activity) {
        if (!(activity instanceof ViewImageActivityNew)) return;
        ViewImageActivityNew vian = (ViewImageActivityNew) activity;

        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            boolean isSelected = currentItem.z();
            boolean newState = !isSelected;

            currentItem.X(newState);
            currentItem.c0(newState ? 1 : 0);

            syncFilmStripSelection(vian, currentItem, newState);

            // Also sync ALL other thumbnails with their c3.t selection state
            // so the FilmStrip stays consistent with the app's internal state
            syncAllFilmStripSelections(vian);
        } catch (Throwable ignored) {}
    }

    private static boolean isCurrentItemSelected(Activity activity) {
        try {
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                t currentItem = vian.u0 != null ? vian.u0.o() : null;
                if (currentItem != null) {
                    return currentItem.z();
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Sync a single thumbnail's p1.m with the c3.t selection state.
     */
    private static void syncFilmStripSelection(ViewImageActivityNew vian, t item, boolean selected) {
        try {
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null) return;

            filmStrip.D = true;

            String itemPath = item.j;
            if (itemPath != null) {
                Object thumbObj = filmStrip.e(itemPath);
                if (thumbObj instanceof com.fstop.photo.p1) {
                    com.fstop.photo.p1 thumb = (com.fstop.photo.p1) thumbObj;
                    thumb.m = selected;
                }
            }

            filmStrip.invalidate();
        } catch (Throwable ignored) {}
    }

    /**
     * Sync ALL FilmStrip thumbnails with their c3.t selection states.
     * This ensures bidirectional consistency — when the user selects via
     * the quick select button, ALL FilmStrip thumbnails update, and when
     * the user long-presses a thumbnail, the quick select button updates
     * on the next page change.
     */
    private static void syncAllFilmStripSelections(ViewImageActivityNew vian) {
        try {
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null || filmStrip.l == null) return;

            java.util.ArrayList thumbnails = filmStrip.l;
            for (int i = 0; i < thumbnails.size(); i++) {
                Object thumbObj = thumbnails.get(i);
                if (thumbObj instanceof com.fstop.photo.p1) {
                    com.fstop.photo.p1 thumb = (com.fstop.photo.p1) thumbObj;
                    String thumbPath = thumb.h;
                    if (thumbPath != null) {
                        // Find matching c3.t in l3.k.a
                        t matchItem = findItemByPath(vian, thumbPath);
                        if (matchItem != null) {
                            thumb.m = matchItem.z();
                        }
                    }
                }
            }

            filmStrip.invalidate();
        } catch (Throwable ignored) {}
    }

    /**
     * Find a c3.t item by its file path.
     * Uses l3.k.a (ArrayList of c3.t media items).
     */
    private static t findItemByPath(ViewImageActivityNew vian, String path) {
        try {
            if (vian.u0 == null || vian.u0.a == null) return null;
            java.util.ArrayList items = vian.u0.a;
            for (int i = 0; i < items.size(); i++) {
                Object obj = items.get(i);
                if (obj instanceof t) {
                    t item = (t) obj;
                    if (path.equals(item.j)) return item;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void updateButtonState(Activity activity, View button) {
        if (button instanceof ImageView) {
            boolean isSelected = isCurrentItemSelected(activity);
            ((ImageView) button).setImageDrawable(createSelectIcon(activity, isSelected));
        }
    }

    /**
     * Create a visible button background: solid white circle with dark border.
     * This ensures the button is clearly visible against any background.
     */
    private static Drawable createButtonBackground(Context context) {
        int size = (int) (40 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        float cx = size / 2f;
        float cy = size / 2f;
        float radius = size / 2f - 2f; // Leave room for border

        // Dark border ring (contrast outline)
        paint.setColor(Color.parseColor("#B0000000")); // Semi-transparent dark
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size / 2f - 0.5f, paint);

        // Solid white fill
        paint.setColor(Color.parseColor("#F5F5F5")); // Off-white
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, paint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /**
     * Create a select/deselect icon as a BitmapDrawable.
     * - Unselected: simple circle outline (dark gray) — NO cross-arrow, NO dot
     * - Selected: filled green circle with white checkmark
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
            // Selected: filled green circle
            paint.setColor(Color.parseColor("#4CAF50"));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);

            // White checkmark
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.08f);
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
            // Unselected: just a circle outline (dark gray, clearly visible)
            paint.setColor(Color.parseColor("#757575")); // Dark gray — clearly visible
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.07f);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
