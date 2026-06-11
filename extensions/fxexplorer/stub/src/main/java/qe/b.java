/*
 * Stub for FX Explorer's resolved app item (Lqe/b;).
 * Used only at compile time so extension code can reference APK classes.
 * At runtime, the real class from the APK is used instead.
 *
 * CRITICAL: This stub exists so that y0.e() has the correct 3rd parameter
 * type in the compiled descriptor. Previously, y0.e() was declared as
 * e(CharSequence, Drawable, Object, OnClickListener), which caused R8 to
 * compile the call with descriptor Ljava/lang/Object; for the 3rd parameter.
 * But the real APK has Lqe/b; — a descriptor mismatch that causes
 * NoSuchMethodError at runtime!
 *
 * Since NoSuchMethodError extends Error (NOT Exception), catch(Exception)
 * does NOT catch it, causing the app to crash.
 */
package qe;

@SuppressWarnings("unused")
public class b {
}
