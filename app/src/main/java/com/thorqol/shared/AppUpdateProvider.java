package com.thorqol.shared;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Only the verified private APK; Android receives a temporary read grant. */
public final class AppUpdateProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{if(!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".thorqol.updates").equals(uri.getAuthority())||!"/update.apk".equals(uri.getPath())||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException("Unknown update URI");File f=new File(getContext().getFilesDir(),"thorqol-update/update.apk");if(!f.isFile())throw new FileNotFoundException("No update");return f;}
    @Override public String getType(Uri uri){try{file(uri);return "application/vnd.android.package-archive";}catch(IOException e){throw new IllegalArgumentException("Unknown update URI");}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort){try{File f=file(uri);String[] names=columns==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:columns;MatrixCursor cursor=new MatrixCursor(names);Object[] values=new Object[names.length];for(int i=0;i<names.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(names[i])?"ThorQOL-update.apk":OpenableColumns.SIZE.equals(names[i])?f.length():null;cursor.addRow(values);return cursor;}catch(IOException e){throw new IllegalArgumentException("Unknown update URI");}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
