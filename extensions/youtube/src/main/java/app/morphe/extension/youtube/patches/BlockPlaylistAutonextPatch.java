/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextPatch {

    /**
     * Injection point.
     *
     * Called at the start of moj.c(alyc), which is the method that returns
     * the PlaybackStartDescriptor for the next video in a playlist/mix.
     * Returning true here will cause the patch to return null (block navigation).
     *
     * @param navigationCommand The alyc object containing the navigation type (alyc.e = alyb enum)
     * @return true if auto-navigation should be blocked, false to allow it
     */
    public static boolean shouldBlockAutonext(Object navigationCommand) {
        try {
            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                return false;
            }

            // navigationCommand.e is the alyb enum (AUTONAV, AUTOPLAY, NEXT, PREVIOUS, etc.)
            // We only block AUTONAV (playlist auto-next) and AUTOPLAY (end-of-video autoplay)
            // Manual NEXT/PREVIOUS navigation is always allowed
            if (navigationCommand == null) {
                return false;
            }

            String navType = navigationCommand.getClass().getSimpleName();
            Logger.printDebug(() -> "BlockPlaylistAutonext: navigation type = " + navType);

            return true; // The patch injects before the alyb check, so reaching here means
                         // the caller already resolved the nav type via smali field access.
                         // The filtering is done in the Kotlin patch via opcode targeting.

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockAutonext failure", ex);
        }

        return false;
    }

    /**
     * Injection point (alternative, field-level check).
     *
     * Called with the alyb enum value directly (the .e field of alyc),
     * after the smali patch reads iget-object v0, p1, Lalyc;->e:Lalyb
     *
     * @param navTypeEnum The alyb enum value (AUTONAV, AUTOPLAY, NEXT, etc.)
     * @return true if this navigation type should be blocked
     */
    public static boolean shouldBlockNavType(Enum<?> navTypeEnum) {
        try {
            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                return false;
            }

            if (navTypeEnum == null) {
                return false;
            }

            String name = navTypeEnum.name();

            // Only block automatic navigations, not manual ones
            boolean isAutoNav = "AUTONAV".equals(name) || "AUTOPLAY".equals(name);

            if (isAutoNav) {
                Logger.printDebug(() -> "BlockPlaylistAutonext: blocking " + name);
            }

            return isAutoNav;

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockNavType failure", ex);
        }

        return false;
    }
}
