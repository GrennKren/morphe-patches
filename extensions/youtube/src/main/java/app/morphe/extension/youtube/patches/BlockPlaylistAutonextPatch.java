/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.BlockPlaylistAutonextButton;

/**
 * Blocks automatic navigation to the next video in playlists and mixes.
 *
 * Playlist detection is done via the alyc navigation object,
 * which contains information about the navigation context including playlist data.
 */
@SuppressWarnings("unused")
public class BlockPlaylistAutonextPatch {

    private static Activity mActivity;

    // Playlist state
    private static volatile boolean isInPlaylist = false;
    private static volatile String currentPlaylistId = null;
    private static volatile boolean isAutonavEnabled = false;

    /**
     * Injection point — YouTubeActivity.onCreate.
     */
    public static void setMainActivity(Activity activity) {
        mActivity = activity;
    }

    /**
     * Injection point — alzf.d(alyc) navigation event.
     * Called every time YouTube tries to navigate to a new video.
     *
     * This is called BEFORE the navigation happens, allowing us to:
     * 1. Detect if this is a playlist navigation
     * 2. Block the navigation if needed
     * 3. Update button visibility
     *
     * @param navigationCommand The alyc object containing navigation info
     */
    public static void onNavigationEvent(@Nullable Object navigationCommand) {
        try {
            if (navigationCommand == null) {
                return;
            }

            // Extract navigation type enum (alyc.e = alyb)
            Enum<?> navType = extractNavigationType(navigationCommand);
            String navTypeName = navType != null ? navType.name() : "UNKNOWN";

            // Detect playlist context from the navigation command
            boolean wasInPlaylist = isInPlaylist;
            isInPlaylist = detectPlaylistFromNavigation(navigationCommand);

            // Extract playlist ID if available
            if (isInPlaylist) {
                currentPlaylistId = extractPlaylistIdFromNavigation(navigationCommand);
            } else {
                currentPlaylistId = null;
            }

            // Check if this is an autonav event
            isAutonavEnabled = "AUTONAV".equals(navTypeName) || "AUTOPLAY".equals(navTypeName);

            // Update button visibility if playlist context changed
            if (wasInPlaylist != isInPlaylist) {
                Logger.printDebug(() -> "Playlist context changed: " +
                        (isInPlaylist ? "IN PLAYLIST" : "NOT IN PLAYLIST") +
                        (currentPlaylistId != null ? " (ID: " + currentPlaylistId + ")" : ""));

                // Notify button to update visibility
                BlockPlaylistAutonextButton.onPlaylistContextChanged(isInPlaylist);
            }

        } catch (Exception ex) {
            Logger.printException(() -> "onNavigationEvent failure", ex);
        }
    }

    /**
     * Extract the navigation type enum from alyc object.
     * alyc.e is the alyb enum (AUTONAV, AUTOPLAY, NEXT, PREVIOUS, etc.)
     */
    @Nullable
    private static Enum<?> extractNavigationType(Object navigationCommand) {
        try {
            // Use reflection to access the 'e' field (the alyb enum)
            Class<?> clazz = navigationCommand.getClass();
            java.lang.reflect.Field field = clazz.getDeclaredField("e");
            field.setAccessible(true);
            Object value = field.get(navigationCommand);
            if (value instanceof Enum) {
                return (Enum<?>) value;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract nav type: " + ex.getMessage());
        }
        return null;
    }

    /**
     * Detect if this navigation is part of a playlist/mix.
     * The alyc object contains various fields that indicate playlist context.
     */
    private static boolean detectPlaylistFromNavigation(Object navigationCommand) {
        try {
            Class<?> clazz = navigationCommand.getClass();

            // Method 1: Check for 'b' field which often contains playlist-related data
            try {
                java.lang.reflect.Field fieldB = clazz.getDeclaredField("b");
                fieldB.setAccessible(true);
                Object valueB = fieldB.get(navigationCommand);
                if (valueB != null) {
                    String strValue = valueB.toString();
                    // Playlist IDs often have specific patterns
                    if (isPlaylistIdPattern(strValue)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}

            // Method 2: Check all String fields for playlist indicators
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(navigationCommand);
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (isPlaylistIdPattern(strValue)) {
                        return true;
                    }
                }
            }

            // Method 3: Check for Object fields that might contain playlist data
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(navigationCommand);
                if (value != null && !value.getClass().isPrimitive()) {
                    String className = value.getClass().getName().toLowerCase();
                    if (className.contains("playlist") || className.contains("list")) {
                        return true;
                    }
                }
            }

        } catch (Exception ex) {
            Logger.printDebug(() -> "detectPlaylistFromNavigation error: " + ex.getMessage());
        }
        return false;
    }

    /**
     * Check if a string matches playlist ID patterns.
     */
    private static boolean isPlaylistIdPattern(String value) {
        if (value == null || value.isEmpty()) return false;

        // YouTube playlist IDs:
        // - Start with "PL" (user playlists)
        // - Start with "LL" (liked videos)
        // - Start with "RD" (mixes)
        // - Start with "UL" (upload mixes)
        // - Start with "WL" (watch later)
        // - Are typically 13-34 characters long

        if (value.length() >= 13 && value.length() <= 40) {
            if (value.startsWith("PL") ||
                value.startsWith("LL") ||
                value.startsWith("RD") ||
                value.startsWith("UL") ||
                value.startsWith("WL") ||
                value.startsWith("FL")) { // Favorites
                return true;
            }
        }

        // Also check for URL-style playlist indicators
        if (value.contains("list=") || value.contains("&list=")) {
            return true;
        }

        return false;
    }

    /**
     * Extract playlist ID from navigation command.
     */
    @Nullable
    private static String extractPlaylistIdFromNavigation(Object navigationCommand) {
        try {
            Class<?> clazz = navigationCommand.getClass();

            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(navigationCommand);
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (isPlaylistIdPattern(strValue)) {
                        return strValue;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Check if currently in a playlist or mix.
     */
    public static boolean isInPlaylistOrMix() {
        return isInPlaylist;
    }

    /**
     * Get current playlist ID, if any.
     */
    @Nullable
    public static String getCurrentPlaylistId() {
        return currentPlaylistId;
    }

    /**
     * Check if autonav is currently enabled (for UI state).
     */
    public static boolean isAutonavEnabled() {
        return isAutonavEnabled;
    }

    // ── Navigation blocking ────────────────────────────────────────────────────

    /**
     * Injection point — alzf.d(alyc).
     *
     * @param navTypeEnum The alyb enum (AUTONAV, AUTOPLAY, NEXT, PREVIOUS, etc.)
     * @return true if navigation should be blocked
     */
    public static boolean shouldBlockNavType(Enum<?> navTypeEnum) {
        try {
            String name = navTypeEnum != null ? navTypeEnum.name() : "null";

            // Only block if setting is enabled AND in playlist context
            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                Logger.printDebug(() -> "shouldBlockNavType: setting disabled, allowing");
                return false;
            }

            // Only block AUTONAV/AUTOPLAY when in playlist
            if (!isInPlaylist) {
                Logger.printDebug(() -> "shouldBlockNavType: not in playlist, allowing " + name);
                return false;
            }

            boolean shouldBlock = "AUTONAV".equals(name) || "AUTOPLAY".equals(name);
            if (shouldBlock) {
                Logger.printDebug(() -> "shouldBlockNavType: BLOCKING " + name + " in playlist");
            }
            return shouldBlock;

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockNavType failure", ex);
        }

        return false;
    }
}