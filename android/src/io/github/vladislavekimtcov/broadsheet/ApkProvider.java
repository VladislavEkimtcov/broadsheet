package io.github.vladislavekimtcov.broadsheet;

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
 * Read-only provider serving the staged update APK over {@code content://} without external
 * libraries (Android 7.0+ forbids {@code file://} intents to the package installer).
 */
public final class ApkProvider extends ContentProvider {
    static final String MIME_TYPE = "application/vnd.android.package-archive";
    static final Uri CONTENT_URI = Uri.parse("content://io.github.vladislavekimtcov.broadsheet.apk/broadsheet.apk");

    static File updateDir(Context ctx) {
        return new File(ctx.getFilesDir(), "update");
    }

    static File stagedFile(Context ctx) {
        return new File(updateDir(ctx), "broadsheet.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        File file = ctx != null ? stagedFile(ctx) : null;
        String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = "broadsheet.apk";
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = file != null && file.isFile() ? file.length() : 0L;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        File file = stagedFile(ctx);
        if (!file.isFile()) throw new FileNotFoundException("no staged apk");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
