/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextButton {
    @Nullable
    private static LegacyPlayerControlButton legacyInstance;

    @Nullable
    private static WeakReference<ImageView> overlayButtonRef;

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
     * Injection point — NEW bold overlay button system.
     * Called from the fullscreen button creation method.
     * The View parameter is the fullscreen button, used as position/style anchor.
     */
    public static void initializeButton(View sourceButton) {
        try {
            if (RESTORE_OLD_PLAYER_BUTTONS || !shouldShowButton()) {
                return;
            }

            String drawableName = Settings.BLOCK_PLAYLIST_AUTONEXT.get()
                    ? "morphe_block_playlist_autonext_bold"
                    : "morphe_block_playlist_autonext_off_bold";

            PlayerOverlayButton.addButton(
                    sourceButton,
                    drawableName,
                    view -> toggleAutonext(),
                    null
            );

            // PlayerOverlayButton.addButton() returns void, but we can grab
            // the ImageView it just created — it's always the last child of
            // the sourceButton's parent ViewGroup.
            if (sourceButton.getParent() instanceof ViewGroup parent) {
                int lastIdx = parent.getChildCount() - 1;
                if (lastIdx >= 0 && parent.getChildAt(lastIdx) instanceof ImageView iv) {
                    overlayButtonRef = new WeakReference<>(iv);
                }
            }
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
            iv.setImageResource(
                    app.morphe.extension.shared.ResourceUtils.getDrawableIdentifier(drawableName)
            );
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
            legacyInstance.setIcon(
                    app.morphe.extension.shared.ResourceUtils.getDrawableIdentifier(drawableName)
            );
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
