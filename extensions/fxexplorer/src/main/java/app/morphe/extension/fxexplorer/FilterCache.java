/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Per-directory, per-tab filter state cache for FX Explorer.
 *
 * Stores the active filter text keyed by composite key (tabHash + ":" + directoryPath).
 * Also tracks the last directory path per tab (lastPathMap).
 *
 * Persistence:
 * - In-memory: HashMap with composite keys for tab-aware lookups during runtime.
 * - SharedPreferences: path-only keys as fallback for app restart scenarios.
 *   On save: writes to both in-memory and SharedPreferences.
 *   On get: checks in-memory first (tab-specific), falls back to SharedPreferences (persistent).
 *
 * Tab awareness:
 * - Each tab (WindowModel) gets a unique identity hash via System.identityHashCode().
 * - This ensures filter state is isolated between tabs viewing the same directory.
 * - identityHashCode is stable within a JVM session but changes on app restart.
 * - SharedPreferences fallback handles cross-restart persistence with path-only keys.
 *
 * Cache key consistency:
 * - Both save and restore use the same composite key format built from
 *   getDirectory() conversion + tabHash, guaranteeing no key mismatch.
 *
 * All methods are static and thread-safe (synchronized on the class).
 */
@SuppressWarnings("unused")
public class FilterCache {

    private static final HashMap<String, String> filterMap = new HashMap<>();
    private static final HashMap<Integer, String> lastPathMap = new HashMap<>();
    private static SharedPreferences prefs = null;
    private static final String PREFS_NAME = "morphe_filter_cache";

    /**
     * Initialize the cache with a Context for SharedPreferences access.
     * Safe to call multiple times — subsequent calls are no-ops once initialized.
     *
     * @param context Android context (activity or application context)
     */
    public static synchronized void init(Context context) {
        if (prefs != null || context == null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Build composite key from tab hash and path string.
     */
    private static String compositeKey(int tabHash, String path) {
        return tabHash + ":" + path;
    }

    /**
     * Save the filter text for a tab+directory combination.
     * If filterText is null or empty, removes the cache entry for that combination.
     * If path is null, does nothing.
     *
     * Persists to SharedPreferences with path-only key (no tabHash) so filters
     * survive app restart. Uses apply() for non-blocking async disk write.
     *
     * @param tabHash   Unique identity hash of the tab's WindowModel
     * @param path      The directory path string
     * @param filterText The active filter text to save, or null/empty to remove
     */
    public static synchronized void saveFilter(int tabHash, String path, String filterText) {
        if (path == null) return;
        String key = compositeKey(tabHash, path);
        if (filterText != null && !filterText.isEmpty()) {
            filterMap.put(key, filterText);
            persistToDisk(path, filterText);
        } else {
            filterMap.remove(key);
            persistToDisk(path, null);
        }
    }

    /**
     * Get the cached filter text for a tab+directory combination.
     * Checks in-memory (tab-specific) first, then falls back to SharedPreferences
     * (persistent, path-only) for cross-restart persistence.
     *
     * @param tabHash Unique identity hash of the tab's WindowModel
     * @param path    The directory path string to look up
     * @return The cached filter text, or null if no filter was cached
     */
    public static synchronized String getFilter(int tabHash, String path) {
        if (path == null) return null;
        // Check in-memory (tab-specific) first
        String key = compositeKey(tabHash, path);
        String result = filterMap.get(key);
        if (result != null) return result;
        // Fall back to SharedPreferences (persistent, path-only)
        if (prefs != null) {
            result = prefs.getString(path, null);
            if (result != null) {
                // Promote to in-memory for this tab so subsequent lookups are faster
                filterMap.put(key, result);
                return result;
            }
        }
        return null;
    }

    /**
     * Set the last directory path for a specific tab.
     * Called during L0() after converting getDirectory() to a path string,
     * so the next L0() call can save the filter for this directory.
     *
     * @param tabHash Unique identity hash of the tab's WindowModel
     * @param path    The directory path string
     */
    public static synchronized void setLastPath(int tabHash, String path) {
        lastPathMap.put(tabHash, path);
    }

    /**
     * Get the last directory path for a specific tab.
     *
     * @param tabHash Unique identity hash of the tab's WindowModel
     * @return The last directory path, or null if not set
     */
    public static synchronized String getLastPath(int tabHash) {
        return lastPathMap.get(tabHash);
    }

    /**
     * Remove the cached filter for a tab+directory combination.
     *
     * @param tabHash Unique identity hash of the tab's WindowModel
     * @param path    The directory path string
     */
    public static synchronized void removeFilter(int tabHash, String path) {
        if (path == null) return;
        String key = compositeKey(tabHash, path);
        filterMap.remove(key);
        persistToDisk(path, null);
    }

    /**
     * Clear all cached filters and reset all state.
     */
    public static synchronized void clearAll() {
        filterMap.clear();
        lastPathMap.clear();
        if (prefs != null) {
            prefs.edit().clear().apply();
        }
    }

    /**
     * Persist a filter entry to SharedPreferences.
     * Uses path-only key (no tabHash) so filters survive app restart
     * regardless of which tab they were saved from.
     *
     * @param path  The directory path (used as SharedPreferences key)
     * @param value The filter text to persist, or null to remove the entry
     */
    private static void persistToDisk(String path, String value) {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (value != null) {
            editor.putString(path, value);
        } else {
            editor.remove(path);
        }
        editor.apply();
    }
}
