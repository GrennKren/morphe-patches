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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fstop.photo.FilmStrip;
import com.fstop.photo.activity.ViewImageActivityNew;
import com.fstop.photo.p1;

import c3.t;

/**
 * Helper class for the Quick Select patch in F-Stop's media viewer.
 *
 * ARCHITECTURE (v9 — proper bidirectional sync via g() hook):
 *
 * SELECTION STATE MODEL (verified from DEX):
 * - c3/t.s (boolean): Per-item selected flag. Set by X(Z)V, read by z()Z.
 * - c3/t.L (int): FAVORITE counter. DO NOT touch — NOT selection!
 * - c3/t.S (int): DataSourceType. DO NOT touch — NOT selection!
 * - b0.H4 (static int): Selection MODE flag. DO NOT SET — setting it shows
 *   crop icon (2131362433) on ALL images which the user doesn't want!
 * - FilmStrip.D (boolean): Enables checkmark drawing on thumbnails.
 *   Default false in ViewImageActivityNew! Must set true for checkmarks.
 * - p1.m (boolean): Per-thumbnail selected flag for FilmStrip checkmark drawing.
 *
 * BIDIRECTIONAL SYNC:
 * Direction 1 (Button → FilmStrip): Our toggleCurrentItemSelection() sets
 *   X(!z()), p1.m, FilmStrip.D=true, FilmStrip.invalidate()
 *
 * Direction 2 (FilmStrip → Button): Hook g(I Z)V — the native selection
 *   callback called by FilmStrip$a.onLongPress(). When native selection
 *   changes (user long-presses thumbnail), g() is called which calls X().
 *   Our hook onNativeSelectionChange() fires after X(), updating the button.
 *
 * NATIVE SELECTION FLOW (from FilmStrip$a.onLongPress, verified from DEX):
 *   1. Toggle p1.m (thumbnail flag)
 *   2. FilmStrip.invalidate() (redraw)
 *   3. activity.g(index, p1.m) → c3/t.X(selected)
 *
 * OUR FLOW (replicates native for Direction 1):
 *   1. X(!z()) — set per-item selected flag
 *   2. p1.m = newState — set FilmStrip thumbnail flag
 *   3. FilmStrip.D = true — enable checkmark drawing (gated by D in b())
 *   4. FilmStrip.invalidate() — redraw
 *
 * CRITICAL RULES:
 * - Do NOT call c0() — sets L (FAVORITES), causes blank images
 * - Do NOT set b0.H4 — shows crop icon on ALL images
 * - Do NOT call invalidateOptionsMenu() — causes menu refresh loops
 * - Do NOT touch c3/t.g1 — controls image rendering
 * - Do NOT modify ALL p1.m values — causes blank images during page transitions
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // Menu item IDs to hide (verified from DEX):
    // 0x7F0A03D3 (2131362771) — controlled by BOTH t2() and x2(). We hide it.
    // 0x7F0A03A9 (2131362729) — controlled by BOTH t2() and x2(). We hide it.
    // 0x7F0A0420 (2131362848) — "showHideThumbnailsMenuItem" / panel toggle.
    //   NOT controlled by t2() or x2() — always visible from menu XML.
    //   This is the CROSS-ARROW ICON (showAsAction=2). User wants it gone.
    // 0x7F0A0241 (2131362433) — crop/select item, visible when b0.H4 > 0.
    //   Normally hidden (H4=-1). Show as backup in case H4 gets set.
    private static final int MENU_ITEM_1 = 2131362771;   // 0x7F0A03D3
    private static final int MENU_ITEM_2 = 2131362729;   // 0x7F0A03A9
    private static final int MENU_ITEM_PANEL = 2131362848; // 0x7F0A0420 (cross-arrow)
    private static final int MENU_ITEM_CROP = 2131362433;  // 0x7F0A0241 (crop icon)

    /**
     * Add the quick select button to the media viewer layout.
     * Called from onCreateOptionsMenu hook (after menu inflation).
     */
    public static void addSelectButton(Activity activity, Menu menu) {
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
            selectButton.setFocusable(false);

            // Solid white background with dark border for visibility
            selectButton.setBackground(createButtonBackground(activity));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
            params.gravity = Gravity.END | Gravity.TOP;
            params.setMargins(0, marginTop, marginEnd, 0);

            selectButton.setOnClickListener(v -> {
                try {
                    toggleCurrentItemSelection(activity);
                    boolean nowSelected = isCurrentItemSelected(activity);
                    ((ImageView) v).setImageDrawable(createSelectIcon(activity, nowSelected));
                } catch (Throwable ignored) {}
            });

            contentView.addView(selectButton, params);

            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                selectButton.setVisibility(vian.H0 ? View.VISIBLE : View.GONE);
            }

            // Hide unwanted menu items AFTER the entire menu setup is done.
            // Use Handler.post to ensure this runs AFTER t2() and x2().
            MAIN_HANDLER.post(() -> hideUnwantedMenuItems(activity));
        } catch (Throwable ignored) {}
    }

    /**
     * Update the select button's icon — called from onPrepareOptionsMenu
     * and from M3() hook (via onPageChanged) for instant response on swipe.
     */
    public static void updateSelectButtonIcon(Activity activity, Menu menu) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton instanceof ImageView) {
                updateButtonState(activity, selectButton);
            }
            // Hide unwanted menu items after x2() has run
            hideUnwantedMenuItems(menu);
        } catch (Throwable ignored) {}
    }

    /**
     * Called when a page is selected (swipe). Updates button icon immediately.
     * Also syncs the current thumbnail's p1.m with c3/t.s.
     */
    public static void onPageChanged(Activity activity) {
        try {
            MAIN_HANDLER.post(() -> {
                try {
                    // Update button icon
                    View selectButton = activity.findViewById(BUTTON_ID);
                    if (selectButton instanceof ImageView) {
                        updateButtonState(activity, selectButton);
                    }

                    // Sync current thumbnail only
                    if (activity instanceof ViewImageActivityNew) {
                        syncCurrentThumbnail((ViewImageActivityNew) activity);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Called when native selection changes (user long-presses a FilmStrip thumbnail).
     * Hooked from g(I Z)V in ViewImageActivityNew — the callback that
     * FilmStrip$a.onLongPress() invokes after toggling p1.m.
     *
     * When this fires, c3/t.X(selected) has already been called inside g(),
     * so z() on the affected item reflects the new state.
     *
     * BIDIRECTIONAL SYNC — Direction 2: FilmStrip → Quick Select button.
     */
    public static void onNativeSelectionChange(Activity activity) {
        try {
            // Ensure FilmStrip.D is true so checkmarks are drawn
            if (activity instanceof ViewImageActivityNew) {
                ViewImageActivityNew vian = (ViewImageActivityNew) activity;
                FilmStrip filmStrip = vian.Q0;
                if (filmStrip != null && !filmStrip.D) {
                    filmStrip.D = true;
                    filmStrip.invalidate();
                }
            }

            // Update our button icon based on the current item's selection state
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton instanceof ImageView) {
                boolean isSelected = isCurrentItemSelected(activity);
                ((ImageView) selectButton).setImageDrawable(createSelectIcon(activity, isSelected));
            }
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

    // ========================================================================
    // SELECTION LOGIC — replicates native FilmStrip$a.onLongPress flow
    // ========================================================================

    /**
     * Toggle selection on the current image.
     * Replicates the native flow: p1.m toggle + X() call + FilmStrip invalidate.
     *
     * CRITICAL RULES:
     * - Only call X(!z()) — do NOT call c0() (sets favorites, not selection)
     * - Do NOT set b0.H4 — it shows crop icon on ALL images
     * - Do NOT call invalidateOptionsMenu() — causes menu refresh loops
     */
    private static void toggleCurrentItemSelection(Activity activity) {
        if (!(activity instanceof ViewImageActivityNew)) return;
        ViewImageActivityNew vian = (ViewImageActivityNew) activity;

        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            boolean wasSelected = currentItem.z();
            boolean newState = !wasSelected;

            // Step 1: Set the per-item selected flag (native: g() → X())
            currentItem.X(newState);

            // Step 2: Update FilmStrip — native flow: p1.m toggle + invalidate
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip != null) {
                // Enable checkmark drawing (required — default is false!)
                filmStrip.D = true;

                // Update the matching thumbnail's selected flag
                String itemPath = currentItem.j;
                if (itemPath != null) {
                    p1 thumb = filmStrip.e(itemPath);
                    if (thumb != null) {
                        thumb.m = newState;
                    }
                }

                filmStrip.invalidate();
            }

            // Step 3: Update our button icon immediately
            View selectButton = vian.findViewById(BUTTON_ID);
            if (selectButton instanceof ImageView) {
                ((ImageView) selectButton).setImageDrawable(createSelectIcon(vian, newState));
            }

            // Step 4: Re-hide unwanted menu items (x2 may have re-shown them)
            hideUnwantedMenuItems(activity);

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

    // ========================================================================
    // FILMSTRIP SYNC (Direction 1: Button → FilmStrip)
    // ========================================================================

    /**
     * Sync ONLY the current thumbnail's p1.m with c3/t.s.
     * Called on page change to keep the FilmStrip consistent.
     * Do NOT sync all thumbnails — causes blank images!
     */
    private static void syncCurrentThumbnail(ViewImageActivityNew vian) {
        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null) return;

            boolean currentSelected = currentItem.z();

            // Enable checkmark drawing if current item is selected
            if (currentSelected) {
                filmStrip.D = true;
            }

            // Update current thumbnail's p1.m
            String itemPath = currentItem.j;
            if (itemPath != null) {
                p1 thumb = filmStrip.e(itemPath);
                if (thumb != null && thumb.m != currentSelected) {
                    thumb.m = currentSelected;
                    filmStrip.invalidate();
                }
            }
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // MENU ITEM HIDING
    // ========================================================================

    /**
     * Hide unwanted toolbar menu items.
     *
     * These items are NOT needed because our floating button handles selection:
     * 1. MENU_ITEM_1 (0x7F0A03D3) — select/favorite toggle (controlled by t2+x2)
     * 2. MENU_ITEM_2 (0x7F0A03A9) — sync/unfavorite toggle (controlled by t2+x2)
     * 3. MENU_ITEM_PANEL (0x7F0A0420) — cross-arrow panel toggle (ALWAYS visible,
     *    not controlled by t2 or x2, user explicitly wants it removed)
     * 4. MENU_ITEM_CROP (0x7F0A0241) — crop icon (visible when b0.H4>0, normally hidden)
     *
     * This method tries BOTH approaches:
     * A) Menu.findItem() — standard approach
     * B) View hierarchy traversal — fallback if findItem doesn't work
     */
    private static void hideUnwantedMenuItems(Activity activity) {
        try {
            // Try to get the menu from the activity
            // We can't store the Menu reference, so we find items through the toolbar view
            View toolbar = findToolbarView(activity);
            if (toolbar instanceof ViewGroup) {
                hideMenuItemsInViewHierarchy((ViewGroup) toolbar);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideUnwantedMenuItems(Menu menu) {
        if (menu == null) return;
        try {
            // Try standard approach first
            for (int id : new int[]{MENU_ITEM_1, MENU_ITEM_2, MENU_ITEM_PANEL, MENU_ITEM_CROP}) {
                MenuItem item = menu.findItem(id);
                if (item != null) {
                    item.setVisible(false);
                }
            }

            // Also iterate all items to catch any we might have missed
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                int id = item.getItemId();
                if (id == MENU_ITEM_1 || id == MENU_ITEM_2 ||
                    id == MENU_ITEM_PANEL || id == MENU_ITEM_CROP) {
                    item.setVisible(false);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Find the toolbar/action bar view in the activity's view hierarchy.
     */
    private static View findToolbarView(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            return findViewByClass(decorView, "android.support.v7.widget.Toolbar",
                "androidx.appcompat.widget.Toolbar",
                "android.widget.Toolbar");
        } catch (Throwable ignored) {}
        return null;
    }

    private static View findViewByClass(View root, String... classNames) {
        if (root == null) return null;
        for (String className : classNames) {
            if (root.getClass().getName().equals(className)) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findViewByClass(group.getChildAt(i), classNames);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Try to hide menu items by traversing the toolbar's view hierarchy.
     * This is a fallback for when Menu.findItem() doesn't work.
     * We look for ImageViews that might be the cross-arrow icon.
     */
    private static void hideMenuItemsInViewHierarchy(ViewGroup toolbar) {
        try {
            // The cross-arrow panel toggle (0x7F0A0420) is likely an ImageButton
            // or ImageView in the toolbar. We identify it by its content description
            // or view ID.
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                int id = child.getId();
                // Check if this view has the menu item ID we want to hide
                if (id == MENU_ITEM_PANEL || id == MENU_ITEM_CROP ||
                    id == MENU_ITEM_1 || id == MENU_ITEM_2) {
                    child.setVisibility(View.GONE);
                }
                // Recurse into child ViewGroups (like ActionMenuView)
                if (child instanceof ViewGroup) {
                    hideMenuItemsInViewHierarchy((ViewGroup) child);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // UI HELPERS
    // ========================================================================

    private static void updateButtonState(Activity activity, View button) {
        if (button instanceof ImageView) {
            boolean isSelected = isCurrentItemSelected(activity);
            ((ImageView) button).setImageDrawable(createSelectIcon(activity, isSelected));
        }
    }

    private static Drawable createButtonBackground(Context context) {
        int size = (int) (40 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        float cx = size / 2f;
        float cy = size / 2f;
        float radius = size / 2f - 2f;

        paint.setColor(Color.parseColor("#B0000000"));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size / 2f - 0.5f, paint);

        paint.setColor(Color.parseColor("#F5F5F5"));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, paint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

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
            paint.setColor(Color.parseColor("#4CAF50"));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.08f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float checkSize = size * 0.16f;
            canvas.drawLine(cx - checkSize, cy + checkSize * 0.1f,
                cx - checkSize * 0.15f, cy + checkSize * 0.7f, paint);
            canvas.drawLine(cx - checkSize * 0.15f, cy + checkSize * 0.7f,
                cx + checkSize, cy - checkSize * 0.5f, paint);
        } else {
            paint.setColor(Color.parseColor("#757575"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.07f);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
