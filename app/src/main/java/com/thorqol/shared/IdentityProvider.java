package com.thorqol.shared;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** No query, files or credentials are exported. Only a non-secret opaque device identity is shared. */
public final class IdentityProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();boolean allowed=uid==android.os.Process.myUid();
        String[] callers=getContext().getPackageManager().getPackagesForUid(uid);
        if(callers!=null)for(String pkg:callers)if(DeviceIdentity.trusted(getContext(),pkg))allowed=true;
        if(!allowed)throw new SecurityException("Untrusted caller");
        try{
            if("peek".equals(method))return DeviceIdentity.local(getContext(),null);
            if("adopt".equals(method))return DeviceIdentity.local(getContext(),extras);
            throw new IllegalArgumentException("Unknown identity operation");
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Identity unavailable");}
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new SecurityException("No data access");}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("No data access");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException("No data access");}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException("No data access");}
}
