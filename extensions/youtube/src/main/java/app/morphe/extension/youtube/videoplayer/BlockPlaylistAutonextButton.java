/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.HidePlayerOverlayButtonsPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextButton {
    @Nullable
    private static LegacyPlayerControlButton legacyInstance;

    @Nullable
    private static WeakReference<ImageView> overlayButtonRef;

    @Nullable
    private static WeakReference<View> sourceButtonRef;

    /** Tracks the ConstantState of the source button background to detect real drawable changes. */
    @Nullable
    private static Drawable.ConstantState sourceBgSnapshot;

    /**
     * Check if the button should be visible.
     * Button is shown only when:
     * 1. The button visibility setting is enabled
     * 2. The current video is part of a playlist or mix
     */
    private static boolean shouldShowButton() {
        return Settings.BLOCK_PLAYLIST_AUTONEXT_BUTTON.get()
                && !VideoInformation.getPlaylistId().isEmpty();
    }

    /**
     * Width percentage for overlay button spacing, matching PlayerOverlayButton.
     * Controls how much horizontal space each button slot occupies, relative
     * to the source (fullscreen) button width. More buttons = tighter spacing
     * so they don't overlap the time bar.
     */
    private static float getWidthPercentage(int totalButtons) {
        return switch (totalButtons) {
            case 2 -> 0.90f;
            case 3 -> 0.80f;
            case 4 -> 0.70f;
            default -> 1.0f;
        };
    }

    /**
     * Count other overlay buttons that are currently active (bold mode).
     * This is used to calculate the correct position for our button.
     * We check the settings directly instead of scanning the ViewGroup,
     * which is more reliable across different YouTube versions.
     */
    private static int countOtherOverlayButtons() {
        int count = 0;
        if (Settings.COPY_VIDEO_URL_BUTTON.get()) count++;
        if (Settings.PLAYBACK_SPEED_DIALOG_BUTTON.get()) count++;
        if (Settings.VIDEO_QUALITY_DIALOG_BUTTON.get()) count++;
        return count;
    }

    /**
     * Injection point — Bold overlay button system.
     * Called from the fullscreen button creation method.
     * The View parameter is the fullscreen button, used as position/style anchor.
     *
     * <p><b>Why self-managed?</b>
     * <p>Other overlay buttons (Copy URL, Speed, Quality) use
     * {@link PlayerOverlayButton#addButton} which registers them in the internal
     * {@code buttonControllers} list. The {@code PlayerOverlayButtonController}
     * then positions every registered button on each frame, reserving a layout
     * slot for each one. If a registered button is set to {@code GONE}, its slot
     * remains reserved — creating a visible gap.
     *
     * <p>This button needs to appear <em>only</em> during playlist/mix playback.
     * When hidden, it must leave no trace — no gap, no empty slot. The only way
     * to achieve that without modifying {@code PlayerOverlayButton.java} is to
     * keep this button out of {@code buttonControllers} entirely and manage
     * positioning, visibility, and styling ourselves.
     *
     * <p><b>How it works:</b>
     * <ol>
     *   <li>Create an {@link ImageView} and add it to the source button's parent
     *       {@link ViewGroup} — without calling {@code PlayerOverlayButton.addButton()}.</li>
     *   <li>An {@link ViewTreeObserver.OnPreDrawListener} runs every frame and:
     *       <ul>
     *         <li>If not in a playlist/mix → {@code GONE} (no gap, since the button
     *             isn't in {@code buttonControllers}).</li>
     *         <li>If in a playlist/mix → mirrors the source button's visibility,
     *             alpha, background, padding, layout params, and position.</li>
     *       </ul>
     *   </li>
     * </ol>
     */
    public static void initializeButton(View sourceButton) {
        try {
            // Only skip if using legacy buttons OR the button setting is entirely disabled.
            // Do NOT check shouldShowButton() here — the pre-draw listener handles that.
            if (RESTORE_OLD_PLAYER_BUTTONS || !Settings.BLOCK_PLAYLIST_AUTONEXT_BUTTON.get()) {
                return;
            }

            if (!(sourceButton.getParent() instanceof ViewGroup parent)) return;

            // Create the button ourselves instead of using PlayerOverlayButton.addButton().
            // This keeps the button out of buttonControllers, so setting it to GONE
            // when not in a playlist removes it from the layout completely — no gap.
            ImageView button = new ImageView(sourceButton.getContext());
            button.setId(View.generateViewId());
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            String drawableName = Settings.BLOCK_PLAYLIST_AUTONEXT.get()
                    ? "morphe_block_playlist_autonext_bold"
                    : "morphe_block_playlist_autonext_off_bold";
            button.setImageResource(ResourceUtils.getDrawableIdentifier(drawableName));
            button.setOnClickListener(view -> toggleAutonext());
            parent.addView(button);

            overlayButtonRef = new WeakReference<>(button);
            sourceButtonRef = new WeakReference<>(sourceButton);

            // Set initial visibility immediately.
            if (!shouldShowButton()) {
                button.setVisibility(View.GONE);
            }

            // Add a pre-draw listener to manage positioning, visibility, and styling.
            // This runs every frame and keeps the button in sync with the source button
            // while applying the playlist visibility filter.
            button.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            ImageView btn = overlayButtonRef != null
                                    ? overlayButtonRef.get() : null;
                            View source = sourceButtonRef != null
                                    ? sourceButtonRef.get() : null;
                            if (btn == null || source == null) {
                                if (btn != null) {
                                    btn.getViewTreeObserver().removeOnPreDrawListener(this);
                                }
                                return true;
                            }

                            // ── Visibility filter: only show in playlist/mix ──
                            if (!shouldShowButton()) {
                                if (btn.getVisibility() != View.GONE) {
                                    btn.setVisibility(View.GONE);
                                }
                                // Skip all other updates when hidden — nothing to style.
                                return true;
                            }

                            // ── In playlist: mirror source button visibility ──
                            final int sourceVis = source.getVisibility();
                            if (btn.getVisibility() != sourceVis) {
                                btn.setVisibility(sourceVis);
                            }

                            // ── Alpha ──
                            final float sourceAlpha = source.getAlpha();
                            if (btn.getAlpha() != sourceAlpha) {
                                btn.setAlpha(sourceAlpha);
                            }

                            // ── Layout params & padding ──
                            if (!(source.getPaddingLeft() == btn.getPaddingLeft()
                                    && source.getPaddingTop() == btn.getPaddingTop()
                                    && source.getPaddingRight() == btn.getPaddingRight()
                                    && source.getPaddingBottom() == btn.getPaddingBottom())) {
                                ViewGroup.LayoutParams lp = source.getLayoutParams();
                                if (VersionCheckPatch.IS_21_15_OR_GREATER) {
                                    // Fullscreen button has a custom margin layout parameters
                                    // class and if used directly causes a broken layout with
                                    // 21.15+. Must wrap in MarginLayoutParams like the controller.
                                    lp = new ViewGroup.MarginLayoutParams(lp);
                                }
                                btn.setLayoutParams(lp);
                                btn.setPadding(
                                        source.getPaddingLeft(),
                                        source.getPaddingTop(),
                                        source.getPaddingRight(),
                                        source.getPaddingBottom()
                                );
                            }

                            // ── Position (X) ──
                            // Use the same formula as PlayerOverlayButtonController:
                            //   buttonNumber = index + (hideFullscreen ? 0 : 1)
                            //   xOffset = sourceX - (buttonNumber * widthPct * sourceWidth)
                            //
                            // Since our button is NOT in buttonControllers, the other
                            // overlay buttons' positions are based on their count only.
                            // We position our button as the next slot after them, using
                            // the same widthPct as the existing buttons for consistent
                            // visual spacing.
                            final boolean hideFullscreen = Settings.HIDE_FULLSCREEN_BUTTON.get();
                            final float sourceWidth = source.getWidth();

                            final int otherCount = countOtherOverlayButtons();
                            // Use the same widthPct as existing buttons for consistent spacing.
                            // Existing buttons use getWidthPercentage(otherCount), and we
                            // place our button with the same spacing to maintain visual alignment.
                            final float widthPct = getWidthPercentage(otherCount);
                            final int buttonNumber = otherCount + (hideFullscreen ? 0 : 1);
                            final float xOffset = source.getX()
                                    - (buttonNumber * widthPct * sourceWidth);
                            if (btn.getX() != xOffset) {
                                btn.setX(xOffset);
                            }

                            // ── Position (Y) ──
                            float yPos = source.getY();
                            if (hideFullscreen) {
                                yPos += HidePlayerOverlayButtonsPatch.FULLSCREEN_HIDDEN_Y_OFFSET;
                            }
                            if (btn.getY() != yPos) {
                                btn.setY(yPos);
                            }

                            // ── Background mirroring ──
                            // Use newDrawable() instead of mutate() so each button gets a
                            // fully independent Drawable instance with its own hotspot/ripple
                            // state. Same approach as PlayerOverlayButtonController.
                            final Drawable sourceBg = source.getBackground();
                            final Drawable.ConstantState newCs = sourceBg != null
                                    ? sourceBg.getConstantState() : null;
                            if (sourceBgSnapshot != newCs) {
                                final Drawable newBg = newCs != null
                                        ? newCs.newDrawable().mutate() : sourceBg;
                                btn.setBackground(newBg);
                                sourceBgSnapshot = newCs;
                            }

                            return true;
                        }
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton (overlay) failure", ex);
        }
    }

    /**
     * Injection point — Legacy button system.
     * Used when "Restore old player buttons" setting is enabled.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacyInstance = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_block_playlist_autonext_button",
                    null,
                    null,
                    // Button is shown only when in a playlist/mix AND setting is enabled
                    BlockPlaylistAutonextButton::shouldShowButton,
                    view -> toggleAutonext(),
                    null
            );
            // Update icon to reflect current state on init
            updateLegacyIcon();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    /**
     * Toggle the block autonext setting and update button icon.
     */
    private static void toggleAutonext() {
        try {
            boolean current = Settings.BLOCK_PLAYLIST_AUTONEXT.get();
            Settings.BLOCK_PLAYLIST_AUTONEXT.save(!current);
            updateOverlayIcon();
            updateLegacyIcon();
            Utils.showToastShort(
                    !current ? "Playlist auto-next: Blocked" : "Playlist auto-next: Allowed"
            );
        } catch (Exception ex) {
            Logger.printException(() -> "toggleAutonext failure", ex);
        }
    }

    /**
     * Update overlay button icon to reflect current block state.
     * ON  (blocked)  = skip icon with red strike-through (bold style)
     * OFF (allowed)  = plain skip icon (bold style)
     */
    private static void updateOverlayIcon() {
        try {
            ImageView iv = overlayButtonRef != null ? overlayButtonRef.get() : null;
            if (iv == null) return;
            String drawableName = Settings.BLOCK_PLAYLIST_AUTONEXT.get()
                    ? "morphe_block_playlist_autonext_bold"
                    : "morphe_block_playlist_autonext_off_bold";
            iv.setImageResource(ResourceUtils.getDrawableIdentifier(drawableName));
        } catch (Exception ex) {
            Logger.printException(() -> "updateOverlayIcon failure", ex);
        }
    }

    /**
     * Update legacy button icon to reflect current block state.
     * ON  (blocked)  = skip icon with red strike-through + animation
     * OFF (allowed)  = dimmed skip icon + animation
     */
    private static void updateLegacyIcon() {
        try {
            if (legacyInstance == null) return;
            String drawableName = Settings.BLOCK_PLAYLIST_AUTONEXT.get()
                    ? "morphe_block_playlist_autonext_on"
                    : "morphe_block_playlist_autonext_off";
            legacyInstance.setIcon(ResourceUtils.getDrawableIdentifier(drawableName));
        } catch (Exception ex) {
            Logger.printException(() -> "updateLegacyIcon failure", ex);
        }
    }

    /**
     * Injection point — Legacy visibility hook.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacyInstance != null) legacyInstance.setVisibilityNegatedImmediate();
    }

    /**
     * Injection point — Legacy visibility hook.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacyInstance != null) legacyInstance.setVisibilityImmediate(visible);
    }

    /**
     * Injection point — Legacy visibility hook.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacyInstance != null) legacyInstance.setVisibility(visible, animated);
    }
}
