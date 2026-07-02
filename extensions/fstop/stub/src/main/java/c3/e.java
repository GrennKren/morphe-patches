/*
 * Stub class for F-Stop's folder/media item data class (c3/e).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * ALL fields are PUBLIC → direct access OK from any package.
 *
 * Field names verified from actual DEX smali (NOT jadx names):
 * - m (String, public) — folder path. Key in c.a HashMap.
 * - n (w1, public) — cover image wrapper.
 * - r (int, public) — ThumbnailImageId from FolderData.
 * - s (ArrayList<w1>, public transient) — sub-thumbnails list.
 */
package c3;

import com.fstop.photo.w1;
import java.util.ArrayList;

@SuppressWarnings("unused")
public class e {
    public int g;
    public String i;
    public String m;
    public w1 n;
    public int r;
    public boolean q;
    public ArrayList s;
}
