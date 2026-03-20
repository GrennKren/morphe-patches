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
     * Injection point — amah.d(alzf) for YouTube 20.45+.
     * Previous versions (20.44.x): alzf.d(alyc) with alyb enum.
     * @param navTypeEnum The alze enum (AUTONAV, AUTOPLAY, NEXT, etc.)
     * @return true if navigation should be blocked
     */
    public static boolean shouldBlockNavType(Enum<?> navTypeEnum) {
        try {
            String name = navTypeEnum != null ? navTypeEnum.name() : "null";
            Logger.printDebug(() -> "BlockPlaylistAutonext: nav type = " + name);

            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                return false;
            }

            return "AUTONAV".equals(name) || "AUTOPLAY".equals(name);

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockNavType failure", ex);
        }

        return false;
    }
}
