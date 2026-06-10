/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import android.view.View;

import hf.y0;

/**
 * Click listener for the "Open With Default" button in the "Open With" dialog.
 *
 * When clicked, this listener:
 * 1. Checks if there's a stored default app for the current file's extension
 * 2. If found -> opens the file directly with that app (dismisses dialog)
 * 3. If not found -> sets selectingDefault=true, sets MIME to wildcard type,
 *    and re-resolves apps, showing ALL apps that can open any file type.
 *    The next app the user picks will be saved as the default.
 *
 * This class is SAFE - all exceptions are caught in DefaultAppRegistry methods,
 * so it will never crash the host app.
 */
@SuppressWarnings("unused")
public class OpenWithDefaultClickListener implements View.OnClickListener {

    private final y0 dialog;

    /**
     * Create a new click listener for the "Open With Default" button.
     *
     * @param dialog The "Open With" dialog instance
     */
    public OpenWithDefaultClickListener(y0 dialog) {
        this.dialog = dialog;
    }

    @Override
    public void onClick(View v) {
        // First, try to open with a stored default app
        if (DefaultAppRegistry.tryOpenWithDefault(dialog)) {
            // Default app found and launched — dialog is already dismissed
            return;
        }

        // No default stored — set selecting mode and re-resolve with wildcard
        DefaultAppRegistry.setSelectingDefault(true);
        dialog.e2 = "*/*";
        dialog.i();
    }
}
