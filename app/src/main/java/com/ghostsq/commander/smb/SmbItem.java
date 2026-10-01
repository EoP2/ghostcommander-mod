package com.ghostsq.commander.smb;

import android.content.res.Resources;
import android.util.Log;

import com.ghostsq.commander.R;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;

import java.util.Date;

import jcifs.smb.SmbFile;

public class SmbItem extends Item {
    private static final String TAG = "SmbItem";
    public SmbFile f;
    public SmbItem( SmbFile f_, int mode, Resources smb_res ) {
        try {
            f = f_;
            synchronized( f ) {
                dir = f.isDirectory();
                name = fixName( f.getName() );
                size = dir ? -1 : f.length();
                long item_time = f.getDate();
                date = item_time > 0 ? new Date( item_time ) : null;
                if( smb_res != null && mode == CommanderAdapter.ICON_MODE ) {
                    int type = f.getType();
                    int drawable_resource = 0;
                    switch( type ) {
                    case SmbFile.TYPE_SHARE:
                        drawable_resource = R.drawable.share;
                        break;
                    case SmbFile.TYPE_SERVER:
                        drawable_resource = R.drawable.server;
                        break;
                    case SmbFile.TYPE_WORKGROUP:
                        drawable_resource = R.drawable.workgroup;
                        break;
                    }
                    if( drawable_resource != 0 ) {
                        setIcon( smb_res.getDrawable( drawable_resource ) );
                    } else {
                        int aa = f.getAttributes();
                        if( ( aa | SmbFile.ATTR_READONLY ) != 0 )
                            attr = "R/O"; 
                    }
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, "SmbItem()", e );
        }
    }
    public final static String fixName( String name ) {
        if( name == null ) return "";
        try {
            byte[] bb = name.getBytes();
            if( bb != null ) {
                int shift = 0;
                for( int i = 0; i < bb.length; i++ ) {
                    if( bb[i] == 0 )
                        shift++;
                    else
                        bb[i-shift] = bb[i];
                }
                if( shift > 0 )
                    return new String( bb, 0, bb.length - shift );
            }
        } catch( IndexOutOfBoundsException e ) {
            Log.e( TAG, "fixName()", e );
        }
        return name;
    }

}   
