package com.ghostsq.commander.smb;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.IReceiver;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

public class Receiver implements IReceiver {
    private final static String TAG = "SMBReceiver";
    private Uri     mDest;
    private CIFSContext authContext;

    Receiver( Uri dir, CIFSContext authContext ) {
        mDest = dir;
        this.authContext = authContext;
    }

    private Uri buildItemUri( String name ) {
        return mDest.buildUpon().appendPath( name ).build();
    }

    @Override
    public OutputStream receive( String fn ) {
        try {
            Uri f_uri = buildItemUri( fn );
            if( f_uri == null ) return null;
            SmbFile smb_file = new SmbFile( f_uri.toString(), authContext );
            smb_file.connect();
            return smb_file.getOutputStream();
        } catch( Exception e ) {
            Log.e( TAG, fn, e );
        }
        return null;
    }

    @Override
    public void closeStream( Closeable s ) {
        try {
            if( s != null )
                s.close();
        } catch( IOException e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public Uri getItemURI( String name, boolean dir ) {
        try {
            Uri f_uri = buildItemUri( name );
            if( f_uri == null ) return null;
            SmbFile smb_file = new SmbFile( f_uri.toString(), authContext );
            smb_file.connect();
            if( smb_file.exists() && smb_file.isDirectory() == dir )
                return f_uri;
        } catch( Exception e ) {
            Log.e( TAG, name, e );
        }
        return null;
    }

    @Override
    public boolean isDirectory( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            SmbFile smb_file = new SmbFile( item_uri.toString(), authContext );
            smb_file.connect();
            return smb_file.isDirectory();
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public Uri makeDirectory( String new_dir_name ) {
        try {
            Uri f_uri = buildItemUri( new_dir_name );
            if( f_uri == null ) return null;
            SmbFile smb_dir = new SmbFile( f_uri.toString(), authContext );
            smb_dir.connect();
            smb_dir.mkdir();
            return f_uri;
        } catch( Exception e ) {
            Log.e( TAG, mDest + " / " + new_dir_name, e );
        }
        return null;
    }

    @Override
    public boolean delete( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            SmbFile smb_file = new SmbFile( item_uri.toString(), authContext );
            smb_file.connect();
            smb_file.delete();
            return true;
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public boolean setDate( Uri item_uri, java.util.Date timestamp ) {
        try {
            if( item_uri == null ) return false;
            SmbFile smb_file = new SmbFile( item_uri.toString(), authContext );
            smb_file.connect();
            smb_file.setLastModified( timestamp.getTime() );
            return true;
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public boolean done() {
        return false;
    }
}
