package com.vinh.listcalculatorfold2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public class ShareImageProvider extends ContentProvider {
    @Override public boolean onCreate(){ return true; }

    private File fileFor(Uri uri){
        String name=uri.getLastPathSegment();
        if(name==null)name="";
        return new File(new File(getContext().getCacheDir(),"share"),name);
    }

    @Override public String getType(Uri uri){
        String n=uri.getLastPathSegment();
        if(n==null)return "application/octet-stream";
        n=n.toLowerCase();
        if(n.endsWith(".png"))return "image/png";
        if(n.endsWith(".pdf"))return "application/pdf";
        if(n.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        File f=fileFor(uri);
        if(!f.exists())throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        File f=fileFor(uri);
        String[] cols=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor c=new MatrixCursor(cols);
        MatrixCursor.RowBuilder row=c.newRow();
        for(String col:cols){
            if(OpenableColumns.DISPLAY_NAME.equals(col))row.add(f.getName());
            else if(OpenableColumns.SIZE.equals(col))row.add(f.length());
            else row.add(null);
        }
        return c;
    }

    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
}