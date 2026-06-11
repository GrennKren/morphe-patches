/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import hf.y0;

/**
 * Click listener and UI builder for the "Open With Default" section in
 * FX Explorer's "Open With" dialog.
 *
 * This class handles two responsibilities:
 * 1. Building the section UI (header, items, icons) via addSection()
 * 2. Handling click events on the wildcard button
 *
 * UI construction is done via addSection(y0) which:
 * - Creates a proper gray uppercase section header using y0.b(resId)
 *   (NOT y0.f(String) which creates brown bold info text)
 * - Changes the header text to "OPEN WITH DEFAULT" by finding the
 *   TextView inside the gh/k widget
 * - Adds a wildcard grid item with a custom teal icon
 * - Adds a "Clear Default" button if a default is currently stored
 *
 * Click behavior:
 * 1. First, try to open with a stored default app
 * 2. If found -> opens the file directly (dialog is dismissed)
 * 3. If not found -> sets selectingDefault=true, sets MIME to wildcard type,
 *    and re-resolves apps, showing ALL apps that can open any file type.
 *    The next app the user picks will be saved as the default.
 *
 * This class is SAFE - all exceptions are caught in DefaultAppRegistry methods,
 * so it will never crash the host app.
 */
@SuppressWarnings("unused")
public class OpenWithDefaultClickListener implements View.OnClickListener {

    /** String resource ID for "Open with application" in FX Explorer 9.1.0.8.
     *  Used by y0.b() to create a proper gray uppercase section header.
     *  After creation, we modify the text to "OPEN WITH DEFAULT". */
    private static final int RES_ID_OPEN_WITH_APPLICATION = 0x7f1004a8;

    private final y0 dialog;

    /**
     * Create a new click listener for the "Open With Default" button.
     *
     * @param dialog The "Open With" dialog instance
     */
    public OpenWithDefaultClickListener(y0 dialog) {
        this.dialog = dialog;
    }

    /**
     * Build the entire "Open With Default" section in the dialog.
     * Called from smali injection in w0.run() (Part 2).
     *
     * This method creates:
     * 1. A proper gray uppercase section header (matching other sections)
     * 2. A wildcard grid item with custom teal icon
     * 3. A "Clear Default" button if a default is currently stored
     *
     * @param dialog The y0 dialog instance
     */
    public static void addSection(y0 dialog) {
        if (dialog == null) return;
        try {
            Context ctx = dialog.getContext();
            if (ctx == null) return;

            // 1. Create proper section header using y0.b(resId)
            // y0.b() creates a gh/k widget with gray uppercase text, proper
            // typeface (Roboto Condensed Bold), and correct spacing.
            // We use the existing "Open with application" resource ID to get
            // the correct styling, then change the text afterwards.
            dialog.b(RES_ID_OPEN_WITH_APPLICATION);

            // 2. Change the header text from "OPEN WITH APPLICATION" to "OPEN WITH DEFAULT"
            modifyHeaderText(dialog, "OPEN WITH DEFAULT");

            // 3. Add the wildcard (Open With Default) grid item with icon
            Drawable defaultIcon = createDefaultIcon(ctx);
            dialog.e("*/*", defaultIcon, null, new OpenWithDefaultClickListener(dialog));

            // 4. Add "Clear Default" button if a default is currently stored
            addClearDefaultButton(dialog, ctx);

        } catch (Exception e) {
            // Fallback: use the old brown-bold header style if proper header fails
            try {
                dialog.f("Open With Default");
                dialog.e("*/*", null, null, new OpenWithDefaultClickListener(dialog));
            } catch (Exception ignored) {
                // Never crash the host app
            }
        }
    }

    /**
     * Modify the text of the last-added section header in the dialog.
     *
     * After y0.b(resId) creates a gh/k section header and adds it to the
     * dialog's content layout, this method finds that header widget and
     * changes its text. The gh/k widget is a LinearLayout containing a
     * TextView. We find the TextView and set the new text.
     *
     * Since the text is already uppercase and the other section headers
     * also use uppercase (via Ug/a.v() transformation), this produces
     * a consistent look.
     */
    private static void modifyHeaderText(y0 dialog, String newText) {
        try {
            LinearLayout layout = dialog.i;
            if (layout == null) return;

            // Get the last child (the header we just added)
            int count = layout.getChildCount();
            if (count == 0) return;
            View header = layout.getChildAt(count - 1);

            // The header is a gh/k (extends ViewGroup/LinearLayout)
            // Find the first TextView inside it and change the text
            if (header instanceof ViewGroup) {
                TextView tv = findFirstTextView((ViewGroup) header);
                if (tv != null) {
                    tv.setText(newText);
                }
            }
        } catch (Exception ignored) {
            // If we can't modify the text, the header will show the
            // original "OPEN WITH APPLICATION" text — not ideal but not a crash
        }
    }

    /**
     * Recursively find the first TextView in a ViewGroup hierarchy.
     */
    private static TextView findFirstTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView tv = findFirstTextView((ViewGroup) child);
                if (tv != null) return tv;
            }
        }
        return null;
    }

    /**
     * Add a "Clear Default" button to the section if a default is currently
     * stored for this file's extension.
     *
     * The button appears as a second grid item in the "Open With Default"
     * section, with a red/orange icon. When clicked, it clears the stored
     * default app for the file's extension.
     */
    private static void addClearDefaultButton(y0 dialog, Context ctx) {
        try {
            kh.e fileItem = dialog.X;
            if (fileItem == null) return;

            String filename = fileItem.getName();
            String ext = DefaultAppRegistry.getFileExtension(filename);
            if (ext == null) return;

            // Only show the Clear Default button if a default is actually stored
            String[] defaultApp = DefaultAppRegistry.getDefault(ctx, ext);
            if (defaultApp == null) return;

            Drawable clearIcon = createClearDefaultIcon(ctx);
            String label = "Clear Default";
            View.OnClickListener listener = new ClearDefaultClickListener(dialog, ext);

            dialog.e(label, clearIcon, null, listener);
        } catch (Exception ignored) {
            // Don't crash — the clear button is optional
        }
    }

    /**
     * Create a custom icon for the "Open With Default" (wildcard) button.
     *
     * The icon is a teal/green rounded square with wildcard text in white,
     * matching the visual style of FX Explorer's app icons.
     */
    private static Drawable createDefaultIcon(Context ctx) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            int size = (int) (48 * density);
            int padding = (int) (4 * density);

            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // Draw rounded square background (teal, matching FX Explorer theme)
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(0xFF009688); // Material teal 500
            float radius = size * 0.2f;
            canvas.drawRoundRect(padding, padding, size - padding, size - padding,
                radius, radius, bgPaint);

            // Draw "*/*" text centered
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize((size - 2 * padding) * 0.38f);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText("*/*", size / 2f, textY, textPaint);

            return new BitmapDrawable(ctx.getResources(), bitmap);
        } catch (Exception e) {
            // If icon creation fails, return null (no icon)
            return null;
        }
    }

    /**
     * Create a custom icon for the "Clear Default" button.
     *
     * The icon is a red/orange rounded square with an "X" symbol,
     * indicating a clear/reset action.
     */
    private static Drawable createClearDefaultIcon(Context ctx) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            int size = (int) (48 * density);
            int padding = (int) (4 * density);

            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // Draw rounded square background (red/orange for "clear" action)
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(0xFFFF5722); // Material deep orange 500
            float radius = size * 0.2f;
            canvas.drawRoundRect(padding, padding, size - padding, size - padding,
                radius, radius, bgPaint);

            // Draw "X" symbol centered
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize((size - 2 * padding) * 0.5f);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText("\u2715", size / 2f, textY, textPaint); // ✕ symbol

            return new BitmapDrawable(ctx.getResources(), bitmap);
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Click listener for the "Clear Default" button.
     * Clears the stored default app for the current file's extension
     * and refreshes the dialog to reflect the change.
     */
    static class ClearDefaultClickListener implements View.OnClickListener {

        private final y0 dialog;
        private final String extension;

        ClearDefaultClickListener(y0 dialog, String extension) {
            this.dialog = dialog;
            this.extension = extension;
        }

        @Override
        public void onClick(View v) {
            try {
                Context ctx = dialog.getContext();
                if (ctx == null) return;

                // Clear the stored default
                DefaultAppRegistry.clearDefault(ctx, extension);

                // Reset selecting flag
                DefaultAppRegistry.setSelectingDefault(false);

                // Dismiss the dialog — the user will need to reopen it
                // (rebuilding the dialog in-place is too complex)
                dialog.dismiss();
            } catch (Exception ignored) {
                // Never crash
            }
        }
    }
}
