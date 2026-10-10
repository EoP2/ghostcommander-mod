package com.ghostsq.commander.sftp;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

public class Receiver implements IReceiver {
    private final static String TAG = "SFTPReceiver";
    private Uri         mDest;
    private ChannelSftp sftp;

    Receiver( Uri dest, ChannelSftp sftp ) {
        mDest = dest;
        this.sftp = sftp;
    }

    private final Uri buildItemUri( String name ) {
        return mDest.buildUpon().appendPath( name ).build();
    }

    private final String getPath( String name ) {
        return Utils.mbAddSl( mDest.getPath() ) + name;
    }

    @Override
    public OutputStream receive( String fn ) {
        try {
            return sftp.put( getPath( fn ) );
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
            SftpATTRS stat = sftp.lstat( getPath( name ) );
            if( stat == null ) return null;
            if( stat.isDir() == dir )
                return buildItemUri( name );
        } catch( SftpException e ) {
            Log.e( TAG, name, e );
        }
        return null;
    }

    @Override
    public boolean isDirectory( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            SftpATTRS stat = sftp.lstat( item_uri.getPath() );
            if( stat != null )
                return stat.isDir();
        } catch( SftpException e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public Uri makeDirectory( String new_dir_name ) {
        try {
            sftp.mkdir( getPath( new_dir_name ) );
            return getItemURI( new_dir_name, true );
        } catch( Exception e ) {
            Log.e( TAG, getPath( new_dir_name ), e );
        }
        return null;
    }

    @Override
    public boolean delete( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            if( isDirectory( item_uri ) )
                sftp.rmdir( item_uri.getPath() );
            else
                sftp.rm( item_uri.getPath() );
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
            sftp.setMtime( item_uri.getPath(), (int)(timestamp.getTime() / 1000) );
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public boolean done() {
        sftp.disconnect();
        return true;
    }
}
