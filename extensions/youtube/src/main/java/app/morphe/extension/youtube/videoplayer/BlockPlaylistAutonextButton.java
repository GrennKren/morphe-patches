/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextButton {
    @Nullable
    private static PlayerControlButton instance;

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
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            instance = new PlayerControlButton(
                    controlsView,
                    "morphe_block_playlist_autonext_button",
                    null,
                    // Button is shown only when in a playlist/mix AND setting is enabled
                    BlockPlaylistAutonextButton::shouldShowButton,
                    view -> toggleAutonext(),
                    null
            );
            // Update icon to reflect current state on init
            updateIcon();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Toggle the block autonext setting and update button icon.
     */
    private static void toggleAutonext() {
        try {
            boolean current = Settings.BLOCK_PLAYLIST_AUTONEXT.get();
            Settings.BLOCK_PLAYLIST_AUTONEXT.save(!current);
            updateIcon();
        } catch (Exception ex) {
            Logger.printException(() -> "toggleAutonext failure", ex);
        }
    }

    /**
     * Update icon to reflect current block state.
     * ON  (blocked)  = skip icon with X / disabled look
     * OFF (allowed)  = normal skip icon
     */
    private static void updateIcon() {
        try {
            if (instance == null) return;
            if (Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                instance.setIcon(
                    app.morphe.extension.shared.ResourceUtils.getDrawableIdentifier(
                        "morphe_block_playlist_autonext_on"
                    )
                );
            } else {
                instance.setIcon(
                    app.morphe.extension.shared.ResourceUtils.getDrawableIdentifier(
                        "morphe_block_playlist_autonext_off"
                    )
                );
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateIcon failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (instance != null) instance.setVisibilityNegatedImmediate();
    }

    /**
     * Injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (instance != null) instance.setVisibilityImmediate(visible);
    }

    /**
     * Injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (instance != null) instance.setVisibility(visible, animated);
    }
}
