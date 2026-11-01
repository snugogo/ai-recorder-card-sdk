package com.aihwstack.recordersample;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only provider that hands files in cache/share/ to other apps (mail, chat, cloud drive).
 * A few lines instead of AndroidX FileProvider, so the sample keeps zero dependencies.
 */
public class ShareProvider extends ContentProvider {
    static File dir(Context c) { File d = new File(c.getCacheDir(), "share"); d.mkdirs(); return d; }
    static Uri uriFor(Context c, File f) { return Uri.parse("content://" + c.getPackageName() + ".share/" + Uri.encode(f.getName())); }

    private File fileFor(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || n.contains("/") || n.contains("..")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), n);
        if (!f.exists()) throw new FileNotFoundException(n);
        return f;
    }

    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "audio/ogg"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
