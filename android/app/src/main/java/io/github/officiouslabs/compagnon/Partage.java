package io.github.officiouslabs.compagnon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Prête à une autre appli (OneDrive) le fichier reçu par le menu Partager, le temps qu'elle l'enregistre.
 * Seul le fichier en attente, dans le cache de l'appli, est servi ; en lecture seule.
 */
public class Partage extends ContentProvider {
    static File fichier(android.content.Context c) {
        File d = new File(c.getCacheDir(), "envoi");
        File[] l = d.listFiles();
        return l != null && l.length > 0 ? l[0] : null;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String tri) {
        File f = fichier(getContext());
        String[] cols = proj != null ? proj : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        if (f == null) return c;
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++)
            row[i] = OpenableColumns.DISPLAY_NAME.equals(cols[i]) ? f.getName() : OpenableColumns.SIZE.equals(cols[i]) ? f.length() : null;
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fichier(getContext());
        if (f == null) throw new FileNotFoundException("aucun fichier");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
