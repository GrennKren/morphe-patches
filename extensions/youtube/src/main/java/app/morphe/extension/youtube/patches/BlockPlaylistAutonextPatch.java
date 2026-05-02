/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextPatch {

    private static Activity mActivity;

    public static void setMainActivity(Activity activity) {
        mActivity = activity;
    }

    /**
     * Injection point — navigation handler methods.
     * Hooked in ALL navigation handler paths (V2 and V1Fallback).
     *
     * YouTube 20.45 and earlier: single 1-param handler (Lamah;->d(Lalzf;)V)
     *   - Enum type Lalze with values AUTONAV, AUTOPLAY, NEXT, etc.
     *
     * YouTube 20.47+: TWO handlers:
     *   V2 (primary):     2-param (Lamfk;Lamfc;)V — enum type Lamfa
     *   V1Fallback:       1-param (Lameq;)V — enum type Lamep
     *
     * The navType enum values across all versions include:
     *   NONE, PREV, NEXT, AUTOPLAY, AUTONAV, JUMP
     *
     * Only AUTONAV and AUTOPLAY are blocked. These represent automatic/
     * autonomous navigation (playlist auto-next). NEXT is NOT blocked
     * because it represents a user-initiated action (pressing the next
     * button), which should remain functional.
     *
     * This matches the behavior of v1.23.0-experimental.1 and the
     * browser userscript approach (which blocks autonav flag, not next).
     *
     * @param navTypeEnum The navigation type enum (AUTONAV, AUTOPLAY, NEXT, etc.)
     * @return true if navigation should be blocked
     */
    public static boolean shouldBlockNavType(Enum<?> navTypeEnum) {
        try {
            String name = navTypeEnum != null ? navTypeEnum.name() : "null";
            Logger.printDebug(() -> "BlockPlaylistAutonext: nav type = " + name);

            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                return false;
            }

            // Only block AUTONAV and AUTOPLAY — automatic navigation types.
            // NEXT is a user-initiated action and must NOT be blocked.
            // This is consistent with v1.23.0 behavior and the userscript approach.
            if ("AUTONAV".equals(name) || "AUTOPLAY".equals(name)) {
                Logger.printDebug(() -> "BlockPlaylistAutonext: BLOCKING nav type = " + name);
                return true;
            }

            return false;

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockNavType failure", ex);
        }

        return false;
    }
}
