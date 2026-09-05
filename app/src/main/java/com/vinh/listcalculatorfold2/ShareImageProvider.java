package com.vinh.listcalculatorfold2;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class ShareImageProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            File root=new File(getContext().getCacheDir(),"share").getCanonicalFile();
            File f=new File(root,uri.getLastPathSegment()).getCanonicalFile();
            if(!f.getPath().startsWith(root.getPath()+File.separator)||!f.exists())throw new FileNotFoundException();
            return f;
        }catch(IOException e){throw new FileNotFoundException();}
    }
    @Override public String getType(Uri uri){return "image/png";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=resolve(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(Exception e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String s,String[] a){return 0;}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){return 0;}
}
