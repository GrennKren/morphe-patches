/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fstop.photo.FilmStrip;
import com.fstop.photo.activity.ViewImageActivityNew;
import com.fstop.photo.p1;

import java.util.ArrayList;

import c3.t;

/**
 * Helper class for the Quick Select patch in F-Stop's media viewer.
 *
 * ARCHITECTURE (v12 — floating draggable PiP-style button + persistent visibility toggle):
 *
 * SELECTION STATE MODEL (verified from DEX):
 * - c3/t.s (boolean): Per-item selected flag. Set by X(Z)V, read by z()Z.
 * - c3/t.L (int): FAVORITE counter. DO NOT touch — NOT selection!
 * - c3/t.S (int): DataSourceType. DO NOT touch — NOT selection!
 * - b0.H4 (static int): Selection MODE flag. DO NOT SET — shows crop icon!
 * - FilmStrip.D (boolean): Enables checkmark drawing on thumbnails.
 *   Default false in ViewImageActivityNew! Must set true for checkmarks.
 * - FilmStrip.l (ArrayList<p1>): Thumbnails list, PARALLEL with u0.a (same index).
 * - p1.m (boolean): Per-thumbnail selected flag for FilmStrip checkmark drawing.
 *
 * BIDIRECTIONAL SYNC:
 *
 * NATIVE FLOW (from FilmStrip$a.onLongPress, verified from DEX):
 *   1. f(x, y) → position (1-indexed)
 *   2. l.get(position - 1) → p1
 *   3. Toggle p1.m (xor with 1)
 *   4. FilmStrip.invalidate()
 *   5. Cast activity to u3/f interface
 *   6. Call g(index, p1.m) → u0.a.get(index).X(selected)
 *
 * OUR FLOW (Direction 1: Button → FilmStrip):
 *   1. currentItem.X(!z()) — set per-item selected flag (DIRECTLY, not via g())
 *   2. Find current item's index in u0.a (parallel with FilmStrip.l)
 *   3. Get p1 from FilmStrip.l.get(index) directly
 *   4. Set p1.m = newState
 *   5. FilmStrip.D = true (enable checkmark drawing)
 *   6. FilmStrip.requestLayout() + invalidate() (forceful redraw)
 *   7. Update button icon immediately
 *
 *   NOTE: We do NOT call vian.g() because the stub method is EMPTY —
 *   calling it would do nothing. Instead we call X() directly (same as
 *   what g() does internally).
 *
 * Direction 2 (FilmStrip → Button):
 *   Hook g(I Z)V — fires after X() is called, updates button icon.
 *   When user long-presses thumbnail, g() is called, our hook updates button.
 *
 * FLOATING DRAGGABLE BUTTON (YouTube PiP-style):
 *   - Button uses absolute X/Y coordinates (FrameLayout.LayoutParams with
 *     Gravity.TOP|Gravity.START as base, then setX/setY for position).
 *   - OnTouchListener distinguishes tap vs drag via touchSlop threshold:
 *       * Movement < threshold → treated as tap → calls performClick()
 *       * Movement >= threshold → treated as drag → updates X/Y in real-time
 *   - Position is persisted to SharedPreferences so it survives app restarts.
 *   - Position is clamped to screen bounds to prevent button going off-screen.
 *
 * PERSISTENT VISIBILITY:
 *   - Default: button is VISIBLE in BOTH fullscreen and non-fullscreen modes.
 *   - User can toggle visibility via the "Hide Quick Select" / "Show Quick Select"
 *     menu item added to the 3-dot context menu (onCreateOptionsMenu).
 *   - Visibility state is persisted to SharedPreferences.
 *   - The auto-hide behavior that previously hid the button when entering
 *     fullscreen mode (I2()) has been REMOVED. The button now stays visible
 *     across fullscreen transitions unless the user explicitly hides it.
 *
 * CRITICAL RULES:
 * - Do NOT call c0() — sets L (FAVORITES), causes blank images
 * - Do NOT set b0.H4 — shows crop icon on ALL images
 * - Do NOT call invalidateOptionsMenu() — causes menu refresh loops
 * - Do NOT touch c3/t.g1 — controls image rendering
 * - Do NOT modify ALL p1.m values — causes blank images during page transitions
 * - Do NOT call vian.g() — it's a stub method (empty body)!
 * - Do NOT hide button on I2()/a4() — visibility is user-controlled only
 */
@SuppressWarnings("unused")
public class QuickSelectHelper {

    private static final int BUTTON_ID = 0x7f09fffe;
    private static final int TOGGLE_MENU_ITEM_ID = 0x7f09fffd;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // SharedPreferences keys — used to persist user's visibility preference and
    // the floating button's last drag position across app restarts.
    private static final String PREFS_NAME = "morphe_fstop_quickselect";
    private static final String KEY_VISIBLE = "button_visible";
    private static final String KEY_X = "button_x";
    private static final String KEY_Y = "button_y";

    // Default to VISIBLE — the user's expectation is that the button shows up
    // until they explicitly toggle it off via the 3-dot menu.
    private static boolean buttonVisible = true;
    private static float savedX = -1f;
    private static float savedY = -1f;
    private static boolean prefsLoaded = false;

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
     *
     * The button is added as a floating, draggable view positioned via absolute
     * X/Y coordinates (PiP-style). A "Hide/Show Quick Select" item is added to
     * the supplied Menu so the user can toggle visibility from the 3-dot menu.
     */
    public static void addSelectButton(Activity activity, Menu menu) {
        try {
            loadPrefsOnce(activity);

            View existing = activity.findViewById(BUTTON_ID);
            if (existing != null) {
                updateButtonState(activity, existing);
                addOrRefreshToggleMenuItem(activity, menu);
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

            // Use absolute X/Y positioning (PiP-style). Gravity is START|TOP as
            // a base; setX/setY then move the view to the desired location.
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setMargins(0, 0, 0, 0);

            // Calculate initial position: top-right by default, or restore the
            // last drag position if the user has previously moved the button.
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int initialX = savedX >= 0 ? (int) savedX : (screenWidth - buttonSize - marginEnd);
            int initialY = savedY >= 0 ? (int) savedY : marginTop;

            selectButton.setX(initialX);
            selectButton.setY(initialY);

            // Set up combined drag + tap behavior on the button itself.
            setupDragAndClick(selectButton, activity);

            contentView.addView(selectButton, params);

            // Apply visibility based on the user's persisted preference — NOT on
            // the toolbar's H0 flag. The button stays visible across fullscreen
            // transitions unless the user explicitly hides it.
            selectButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);

            // Add the "Hide/Show Quick Select" entry to the 3-dot menu.
            addOrRefreshToggleMenuItem(activity, menu);

            // Hide unwanted menu items AFTER the entire menu setup is done.
            // Use Handler.post to ensure this runs AFTER t2() and x2().
            MAIN_HANDLER.post(() -> hideUnwantedMenuItems(activity));
        } catch (Throwable ignored) {}
    }

    /**
     * Update the select button's icon — called from onPrepareOptionsMenu
     * and from M3() hook (via onPageChanged) for instant response on swipe.
     * Also refreshes the toggle menu item's title so it matches the current
     * visibility state.
     */
    public static void updateSelectButtonIcon(Activity activity, Menu menu) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton instanceof ImageView) {
                updateButtonState(activity, selectButton);
            }
            // Refresh the toggle menu item's title (Show/Hide Quick Select).
            addOrRefreshToggleMenuItem(activity, menu);
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

    /**
     * Called when the toolbar is shown (exiting fullscreen mode).
     *
     * In v12, this NO LONGER forces the button visible. The button's visibility
     * is now controlled exclusively by the user's persisted preference
     * ({@link #buttonVisible}). This method only ensures the button's visibility
     * matches the user's preference in case something else changed it.
     */
    public static void onToolbarShown(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                // Respect user's preference — do NOT override it.
                selectButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Called when the toolbar is hidden (entering fullscreen mode).
     *
     * In v12, this NO LONGER hides the button. Previously, entering fullscreen
     * would hide the Quick Select button — which is the behavior the user
     * explicitly wants removed. The button now stays visible across fullscreen
     * transitions unless the user explicitly hides it via the 3-dot menu.
     */
    public static void onToolbarHidden(Activity activity) {
        try {
            View selectButton = activity.findViewById(BUTTON_ID);
            if (selectButton != null) {
                // Respect user's preference — do NOT hide on fullscreen entry.
                selectButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // VISIBILITY TOGGLE (user-controlled via 3-dot menu)
    // ========================================================================

    /**
     * Toggle the floating button's visibility.
     * Called when the user taps "Hide Quick Select" / "Show Quick Select" in
     * the 3-dot context menu. The new state is persisted to SharedPreferences
     * so it survives app restarts and is applied consistently across both
     * fullscreen and non-fullscreen modes.
     */
    private static void toggleQuickSelectVisibility(Activity activity) {
        buttonVisible = !buttonVisible;
        saveVisibility(activity, buttonVisible);
        try {
            View button = activity.findViewById(BUTTON_ID);
            if (button != null) {
                button.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
            }
            // Note: the menu item's title is refreshed automatically the next
            // time onPrepareOptionsMenu fires (via updateSelectButtonIcon).
            // We don't call invalidateOptionsMenu() here because doing so can
            // trigger menu refresh loops (per CRITICAL RULES).
        } catch (Throwable ignored) {}
    }

    /**
     * Add or refresh the "Hide/Show Quick Select" menu item.
     *
     * Called from both onCreateOptionsMenu (initial creation) and
     * onPrepareOptionsMenu (every time the menu is shown). The first call
     * creates the item and attaches an OnMenuItemClickListener; subsequent
     * calls only update the title to reflect the current visibility state.
     *
     * The item is added with SHOW_AS_ACTION_NEVER so it appears in the
     * 3-dot overflow menu alongside the other actions (Copy, Move, Edit tags,
     * Rate, ..., Print, Copy to clipboard), not in the toolbar's action area.
     */
    private static void addOrRefreshToggleMenuItem(final Activity activity, Menu menu) {
        if (menu == null) return;
        try {
            MenuItem item = menu.findItem(TOGGLE_MENU_ITEM_ID);
            if (item == null) {
                item = menu.add(Menu.NONE, TOGGLE_MENU_ITEM_ID, Menu.CATEGORY_SYSTEM,
                    buttonVisible ? "Hide Quick Select" : "Show Quick Select");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                final Activity act = activity;
                item.setOnMenuItemClickListener(mi -> {
                    toggleQuickSelectVisibility(act);
                    // Update the title immediately so the user sees the new label
                    // the next time they open the 3-dot menu.
                    mi.setTitle(buttonVisible ? "Hide Quick Select" : "Show Quick Select");
                    return true;
                });
            } else {
                // Refresh the title to match the current state.
                item.setTitle(buttonVisible ? "Hide Quick Select" : "Show Quick Select");
            }
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // DRAG + CLICK HANDLING (YouTube PiP-style)
    // ========================================================================

    /**
     * Attach an OnTouchListener that distinguishes between a tap and a drag.
     *
     * Behavior:
     *   - ACTION_DOWN: record the touch start position and the button's
     *     current X/Y. Mark as not-yet-dragging.
     *   - ACTION_MOVE: if movement exceeds the touch-slop threshold, mark as
     *     dragging. While dragging, update the button's X/Y in real-time and
     *     clamp to screen bounds so the button can't be dragged off-screen.
     *   - ACTION_UP: if a drag occurred, persist the new position to
     *     SharedPreferences. If no drag occurred (movement was within the
     *     threshold), treat the gesture as a tap and call performClick() to
     *     toggle the current item's selection state.
     *
     * The threshold is the system's scaled touch-slop (typically ~8dp) but we
     * enforce a minimum of 16 pixels to make tap-vs-drag detection more
     * forgiving on high-density displays.
     */
    private static void setupDragAndClick(final ImageView button, final Activity activity) {
        final float[] startTouchX = new float[1];
        final float[] startTouchY = new float[1];
        final float[] startButtonX = new float[1];
        final float[] startButtonY = new float[1];
        final boolean[] isDragging = new boolean[1];

        float touchSlop;
        try {
            touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        } catch (Throwable t) {
            touchSlop = 16f;
        }
        // Enforce a minimum threshold of 16px so a small finger jitter on a
        // high-DPI screen doesn't accidentally trigger a drag instead of a tap.
        final float dragThreshold = Math.max(touchSlop, 16f);

        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                try {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startTouchX[0] = event.getRawX();
                            startTouchY[0] = event.getRawY();
                            startButtonX[0] = button.getX();
                            startButtonY[0] = button.getY();
                            isDragging[0] = false;
                            return true;

                        case MotionEvent.ACTION_MOVE: {
                            float dx = event.getRawX() - startTouchX[0];
                            float dy = event.getRawY() - startTouchY[0];
                            if (!isDragging[0] &&
                                (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                                isDragging[0] = true;
                            }
                            if (isDragging[0]) {
                                float newX = startButtonX[0] + dx;
                                float newY = startButtonY[0] + dy;

                                // Clamp to screen bounds so the button stays
                                // at least partially visible at all times.
                                int sw = activity.getResources().getDisplayMetrics().widthPixels;
                                int sh = activity.getResources().getDisplayMetrics().heightPixels;
                                int bs = button.getWidth();
                                if (bs <= 0) {
                                    bs = (int) (40 * activity.getResources().getDisplayMetrics().density);
                                }
                                newX = Math.max(0, Math.min(newX, sw - bs));
                                newY = Math.max(0, Math.min(newY, sh - bs));

                                button.setX(newX);
                                button.setY(newY);
                                return true;
                            }
                            return false;
                        }

                        case MotionEvent.ACTION_UP:
                            if (isDragging[0]) {
                                // Persist the new floating position.
                                savePosition(activity, button.getX(), button.getY());
                                isDragging[0] = false;
                                return true;
                            } else {
                                // Treat as a tap — toggle the current item's
                                // selection state. performClick() triggers the
                                // OnClickListener attached below.
                                button.performClick();
                                return true;
                            }

                        case MotionEvent.ACTION_CANCEL:
                            isDragging[0] = false;
                            return true;
                    }
                } catch (Throwable ignored) {}
                return false;
            }
        });

        // The actual tap behavior (toggle selection) — invoked by
        // performClick() from the touch listener when no drag occurred.
        selectButtonSetOnClickListener(button, activity);
    }

    private static void selectButtonSetOnClickListener(final ImageView button, final Activity activity) {
        button.setOnClickListener(v -> {
            try {
                toggleCurrentItemSelection(activity);
                // ALWAYS update icon — even if FilmStrip sync partially fails
                boolean nowSelected = isCurrentItemSelected(activity);
                ((ImageView) v).setImageDrawable(createSelectIcon(activity, nowSelected));
            } catch (Throwable ignored) {}
        });
    }

    // ========================================================================
    // PREFERENCES (persisted visibility + drag position)
    // ========================================================================

    private static void loadPrefsOnce(Context ctx) {
        if (prefsLoaded) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            buttonVisible = prefs.getBoolean(KEY_VISIBLE, true);
            savedX = prefs.getFloat(KEY_X, -1f);
            savedY = prefs.getFloat(KEY_Y, -1f);
            prefsLoaded = true;
        } catch (Throwable ignored) {}
    }

    private static void saveVisibility(Context ctx, boolean visible) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_VISIBLE, visible).apply();
        } catch (Throwable ignored) {}
    }

    private static void savePosition(Context ctx, float x, float y) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply();
            savedX = x;
            savedY = y;
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    // SELECTION LOGIC
    // ========================================================================

    /**
     * Toggle selection on the current image.
     *
     * Step 1: Call X(!z()) DIRECTLY on c3/t — this sets the authoritative
     *         selected flag (s field). Do NOT call g() — it's a stub!
     * Step 2: Sync FilmStrip thumbnail via direct ArrayList index access.
     *         Set p1.m + FilmStrip.D + requestLayout() + invalidate()
     *
     * CRITICAL RULES:
     * - Only call X(!z()) directly — do NOT call g() (stub, empty body)
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

            // Step 1: Set the per-item selected flag DIRECTLY
            // (same as what g() does internally: u0.a.get(index).X(selected))
            boolean wasSelected = currentItem.z();
            boolean newState = !wasSelected;
            currentItem.X(newState);

            // Step 2: Sync FilmStrip thumbnail checkmark
            FilmStrip filmStrip = vian.Q0;
            if (filmStrip != null && filmStrip.l != null) {
                // Find current item's index in u0.a (parallel with FilmStrip.l)
                int currentIndex = findCurrentItemIndex(vian);
                if (currentIndex >= 0 && currentIndex < filmStrip.l.size()) {
                    Object thumbObj = filmStrip.l.get(currentIndex);
                    if (thumbObj instanceof p1) {
                        p1 thumb = (p1) thumbObj;
                        thumb.m = newState;
                    }
                }

                // Enable checkmark drawing (required — FilmStrip.b() returns immediately if D=false)
                filmStrip.D = true;

                // Force redraw — use BOTH requestLayout() and invalidate()
                filmStrip.requestLayout();
                filmStrip.invalidate();

                // Post a delayed invalidation as a safety net
                MAIN_HANDLER.postDelayed(() -> {
                    try {
                        filmStrip.invalidate();
                    } catch (Throwable ignored) {}
                }, 50);
            }

            // Step 3: Re-hide unwanted menu items (x2 may have re-shown them)
            hideUnwantedMenuItems(activity);

        } catch (Throwable ignored) {}
    }

    /**
     * Find the index of the current item (u0.o()) in u0.a ArrayList.
     * Returns -1 if not found.
     * FilmStrip.l and u0.a use the SAME index (verified from DEX).
     */
    private static int findCurrentItemIndex(ViewImageActivityNew vian) {
        try {
            if (vian.u0 == null) return -1;
            t currentItem = vian.u0.o();
            if (currentItem == null) return -1;
            ArrayList items = vian.u0.a;
            if (items == null) return -1;

            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) == currentItem) {
                    return i;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
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
     *
     * Uses DIRECT ArrayList index access (same as native flow)
     * instead of e(String) which may fail due to path format mismatch.
     */
    private static void syncCurrentThumbnail(ViewImageActivityNew vian) {
        try {
            t currentItem = vian.u0 != null ? vian.u0.o() : null;
            if (currentItem == null) return;

            FilmStrip filmStrip = vian.Q0;
            if (filmStrip == null || filmStrip.l == null) return;

            boolean currentSelected = currentItem.z();

            // Enable checkmark drawing if current item is selected
            if (currentSelected && !filmStrip.D) {
                filmStrip.D = true;
            }

            // Find the current item's index
            int currentIndex = findCurrentItemIndex(vian);
            if (currentIndex < 0 || currentIndex >= filmStrip.l.size()) return;

            // Update current thumbnail's p1.m using same index
            Object thumbObj = filmStrip.l.get(currentIndex);
            if (thumbObj instanceof p1) {
                p1 thumb = (p1) thumbObj;
                if (thumb.m != currentSelected) {
                    thumb.m = currentSelected;
                    filmStrip.requestLayout();
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
     */
    private static void hideUnwantedMenuItems(Activity activity) {
        try {
            View toolbar = findToolbarView(activity);
            if (toolbar instanceof ViewGroup) {
                hideMenuItemsInViewHierarchy((ViewGroup) toolbar);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideUnwantedMenuItems(Menu menu) {
        if (menu == null) return;
        try {
            for (int id : new int[]{MENU_ITEM_1, MENU_ITEM_2, MENU_ITEM_PANEL, MENU_ITEM_CROP}) {
                MenuItem item = menu.findItem(id);
                if (item != null) {
                    item.setVisible(false);
                }
            }

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

    private static void hideMenuItemsInViewHierarchy(ViewGroup toolbar) {
        try {
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                int id = child.getId();
                if (id == MENU_ITEM_PANEL || id == MENU_ITEM_CROP ||
                    id == MENU_ITEM_1 || id == MENU_ITEM_2) {
                    child.setVisibility(View.GONE);
                }
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
