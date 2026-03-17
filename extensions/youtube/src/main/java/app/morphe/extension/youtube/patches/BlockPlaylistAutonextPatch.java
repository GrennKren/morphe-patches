/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.widget.Toast;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BlockPlaylistAutonextPatch {

    private static Activity mActivity;

    /**
     * Called from YouTubeActivityOnCreateFingerprint hook (same pattern as ShortsAutoplayPatch).
     * Used to get a context for Toast messages during debugging.
     */
    public static void setMainActivity(Activity activity) {
        mActivity = activity;
    }

    /**
     * Injection point.
     * Called at the start of alzf.d(alyc) — the method that executes navigation.
     *
     * @param navTypeEnum The alyb enum value (AUTONAV, AUTOPLAY, NEXT, etc.)
     * @return true if this navigation should be blocked
     */
    public static boolean shouldBlockNavType(Enum<?> navTypeEnum) {
        try {
            String name = navTypeEnum != null ? navTypeEnum.name() : "null";

            // Debug toast — remove after confirming it works
            if (mActivity != null) {
                mActivity.runOnUiThread(() ->
                    Toast.makeText(mActivity, "[Morphe] nav: " + name, Toast.LENGTH_SHORT).show()
                );
            }

            Logger.printDebug(() -> "BlockPlaylistAutonext: nav type = " + name);

            if (!Settings.BLOCK_PLAYLIST_AUTONEXT.get()) {
                return false;
            }

            boolean shouldBlock = "AUTONAV".equals(name) || "AUTOPLAY".equals(name);

            if (shouldBlock) {
                Logger.printDebug(() -> "BlockPlaylistAutonext: BLOCKING " + name);
            }

            return shouldBlock;

        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockNavType failure", ex);
        }

        return false;
    }
}