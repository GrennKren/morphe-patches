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
 * When clicked, this listener sets the MIME type override to the wildcard
 * MIME type on the parent y0 dialog and triggers i() to re-resolve apps with
 * the wildcard MIME type. This is the same behavior as clicking the
 * wildcard button in the "Open As" dialog, but directly accessible from
 * the "Open With" dialog without needing to navigate to "Open As" first.
 *
 * The flow mirrors the existing "Open As" handler in Laf/g case 0x13:
 *   1. Set y0.e2 = wildcard MIME string
 *   2. Call y0.i() to re-resolve with the new MIME type
 *
 * Unlike the "Open As" handler, we do NOT dismiss a z0 dialog because
 * this listener is attached directly to a button in the y0 dialog itself.
 * The y0.i() call will clear and rebuild the y0 dialog content.
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
        // Set MIME type override to wildcard - same as clicking wildcard in "Open As"
        dialog.e2 = "*/*";
        // Re-resolve apps with the new MIME type - clears and rebuilds the dialog
        dialog.i();
    }
}
