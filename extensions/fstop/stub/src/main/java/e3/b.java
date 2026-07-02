package e3;

import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;

import java.util.ArrayList;

@SuppressWarnings("unused")
public class b {
    public SQLiteDatabase a;
    public void Y1(int imageId, String fullPath, Bitmap bitmap) {}
    public void O0(c3.e eVar) {}
    public boolean b2() { return a != null && a.isOpen(); }

    /** Stock: actually deletes the file (File.delete / SMB / remote). Returns true on success. */
    public boolean w(c3.t tVar) { return false; }

    /** Stock: batched SQL `DELETE FROM Thumbnail/Image WHERE _ID IN (...)`. */
    public void U2(ArrayList arrayList) {}

    /** Stock: batched SQL `DELETE FROM ProtectedFolderImage WHERE ImageId IN (...)`. */
    public void Y2(ArrayList arrayList) {}

    /** Stock: batched MediaScannerConnection.scanFile() with all paths. */
    public void L2(ArrayList arrayList) {}
}
