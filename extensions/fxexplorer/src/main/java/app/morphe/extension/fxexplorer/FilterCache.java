/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import android.content.Context;

import java.util.HashMap;

/**
 * Per-directory, per-tab filter state cache for FX Explorer.
 *
 * Stores the active filter text keyed by composite key (tabHash + ":" + directoryPath).
 * Also tracks the last directory path per tab (lastPathMap).
 *
 * In-memory only — cache is automatically cleared when the app process restarts.
 * This prevents stale filter data from causing crashes when the filter bar (o2)
 * has not been created yet in a new session.
 *
 * Tab awareness:
 * - Each tab (WindowModel) gets a unique identity hash via System.identityHashCode().
 * - This ensures filter state is isolated between tabs viewing the same directory.
 * - identityHashCode is stable within a JVM session but changes on app restart.
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

    /**
     * Initialize the cache with a Context.
     * Kept as a no-op for compatibility with existing patch smali that calls init().
     *
     * @param context Android context (unused, kept for API compatibility)
     */
    public static synchronized void init(Context context) {
        // No-op: SharedPreferences removed. Cache is in-memory only.
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
     * In-memory only — data does not persist across app restarts.
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
        } else {
            filterMap.remove(key);
        }
    }

    /**
     * Get the cached filter text for a tab+directory combination.
     * Checks in-memory (tab-specific) only.
     *
     * @param tabHash Unique identity hash of the tab's WindowModel
     * @param path    The directory path string to look up
     * @return The cached filter text, or null if no filter was cached
     */
    public static synchronized String getFilter(int tabHash, String path) {
        if (path == null) return null;
        String key = compositeKey(tabHash, path);
        return filterMap.get(key);
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
    }

    /**
     * Clear all cached filters and reset all state.
     */
    public static synchronized void clearAll() {
        filterMap.clear();
        lastPathMap.clear();
    }
}
