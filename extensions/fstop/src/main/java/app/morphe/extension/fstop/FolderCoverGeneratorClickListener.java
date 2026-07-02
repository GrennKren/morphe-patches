/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fstop;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.preference.Preference;

/**
 * Click listener for the "Generate all folder covers" preference.
 * Starts {@link FolderCoverGeneratorService} as a foreground service.
 *
 * Implements Preference.d (the obfuscated OnPreferenceClickListener
 * interface) with method a(Preference)Z — matching the runtime signature.
 *
 * The Context is obtained from the Preference itself (preference.getContext()),
 * which is the same Context used by the PreferenceFragment. This avoids
 * needing to access the Fragment directly.
 */
@SuppressWarnings("unused")
public final class FolderCoverGeneratorClickListener
        implements Preference.d {

    @Override
    public boolean a(Preference preference) {
        if (preference == null) return false;

        Context ctx = preference.getContext();
        if (ctx == null) return false;

        Intent intent = new Intent(ctx, FolderCoverGeneratorService.class);
        intent.setAction(FolderCoverGeneratorService.ACTION_START);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }

        return true;
    }
}
