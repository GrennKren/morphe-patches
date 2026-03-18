/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.BlockPlaylistAutonextPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Toggle button for blocking playlist auto-next.
 * Only visible when watching a video that's part of a playlist or mix.
 */
@SuppressWarnings("unused")
public class BlockPlaylistAutonextButton {
    @Nullable
    private static PlayerControlButton instance;

    /**
     * Injection point — initialize the button.
     */
    public static void initializeButton(View controlsView) {
        try {
            instance = new PlayerControlButton(
                    controlsView,
                    "morphe_block_playlist_autonext_button",
                    null,
                    // Visibility condition: button setting enabled AND in playlist context
                    () -> Settings.BLOCK_PLAYLIST_AUTONEXT_BUTTON.get() &&
                           BlockPlaylistAutonextPatch.isInPlaylistOrMix(),
                    view -> toggleAutonext(),
                    null
            );
            // Update icon to reflect current state on init
            updateIcon();
            Logger.printDebug(() -> "BlockPlaylistAutonextButton: initialized");
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
            Logger.printDebug(() -> "BlockPlaylistAutonextButton: toggled to " + !current);
        } catch (Exception ex) {
            Logger.printException(() -> "toggleAutonext failure", ex);
        }
    }

    /**
     * Update icon to reflect current block state.
     * ON  (blocked)  = skip icon with strike-through (red)
     * OFF (allowed)  = normal skip icon (white)
     */
    private static void updateIcon() {
        try {
            if (instance == null) return;

            String drawableName = Settings.BLOCK_PLAYLIST_AUTONEXT.get()
                    ? "morphe_block_playlist_autonext_on"
                    : "morphe_block_playlist_autonext_off";

            instance.setIcon(
                app.morphe.extension.shared.ResourceUtils.getDrawableIdentifier(drawableName)
            );
        } catch (Exception ex) {
            Logger.printException(() -> "updateIcon failure", ex);
        }
    }

    /**
     * Called when playlist context changes — update button visibility.
     * This is called from BlockPlaylistAutonextPatch.onNavigationEvent.
     */
    public static void onPlaylistContextChanged(boolean inPlaylist) {
        try {
            if (instance != null) {
                boolean shouldShow = Settings.BLOCK_PLAYLIST_AUTONEXT_BUTTON.get() && inPlaylist;
                // Update visibility with animation
                instance.setVisibility(shouldShow, true);
                Logger.printDebug(() -> "BlockPlaylistAutonextButton: visibility = " + shouldShow);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPlaylistContextChanged failure", ex);
        }
    }

    /**
     * Injection point — visibility control.
     */
    public static void setVisibilityNegatedImmediate() {
        if (instance != null) instance.setVisibilityNegatedImmediate();
    }

    /**
     * Injection point — visibility control.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (instance != null) instance.setVisibilityImmediate(visible);
    }

    /**
     * Injection point — visibility control.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (instance != null) instance.setVisibility(visible, animated);
    }
}