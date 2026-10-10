package com.ghostsq.commander.sftp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.PowerManager;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.SftpATTRS;

import java.io.File;

class CopyToEngine extends SFTPEngineBase // From a local fs to SFTP
{
    protected final static int BLOCK_SIZE = 32768;
    private   Commander commander;
    private   File[]  mList;
    private   boolean move = false;
    private   boolean del_src_dir = false;
    private   WifiLock  wifiLock;
    private   PowerManager.WakeLock wakeLock;

    CopyToEngine( Commander c, SFTPAdapter a, File[] list, int move_mode_ ) {
        super( a, null );
        commander = c;
        mList = list;
        move = ( move_mode_ & CommanderAdapter.MODE_MOVE ) != 0;
        del_src_dir = ( move_mode_ & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
        WifiManager manager = (WifiManager)ctx.getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG );
        wifiLock.setReferenceCounted( false );
        PowerManager pm = (PowerManager)ctx.getSystemService( Context.POWER_SERVICE );
        wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "GC:CopyToSFTP" );
        wakeLock.setReferenceCounted( false );
    }

    @Override
    public void run() {
        try {
            int cl_res = adapter.connectAndLogin();
            if( cl_res < 0 ) {
                if( cl_res < 0 ) { 
                    sendProgress( null, Commander.OPERATION_FAILED );
                    return;
                }
            }
            sftp = adapter.getChannel();
            if( sftp == null ) { 
                sendProgress( null, Commander.OPERATION_FAILED );
                return;
            }
            wakeLock.acquire( 3600000 );
            wifiLock.acquire();
            String dest_path = Utils.mbAddSl(adapter.getUri().getPath() );
            if( !Utils.str( dest_path ) )
                dest_path = "/";
                 
            int cnt = copyFiles( mList, dest_path );
            sendResult( Utils.getOpReport( ctx, cnt, move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
            super.run();
        } catch( InterruptedException e ) {
        } catch( Exception e ) {
            sendProgress( e.getLocalizedMessage(), Commander.OPERATION_FAILED );
            Log.e( TAG, "", e );
        }
        finally {
            if( sftp != null ) sftp.disconnect();
            sftp = null;
            if( del_src_dir )
                deleteDir( mList[0].getParentFile() );
            wifiLock.release();
            wakeLock.release();
        }
    }

    private final int copyFiles( File[] list, String dest ) {
        int counter = 0;
        try {
            long num = list.length;
            long dir_size = 0, byte_count = 0;
            for( int i = 0; i < num; i++ ) {
                File f = list[i];               
                if( !f.isDirectory() )
                    dir_size += f.length();
            }
            double conv = PERC/(double)dir_size;
            for( int i = 0; i < num; i++ ) {
                if( stop || isInterrupted() ) {
                    error( ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                File f = list[i];
                if( f == null || !f.exists() ) continue;
                boolean dir = f.isDirectory(); 
                String fn = f.getName();
                if( dir ) fn += "/";
                String sftp_fn = Utils.mbAddSl( dest )  + fn;
                boolean sftp_exists = false;
                try {
                    SftpATTRS sftp_file_attr = sftp.lstat( sftp_fn );
                    sftp_exists = sftp_file_attr != null;
                    if( !dir && sftp_exists ) {
                        int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), sftp_fn ), commander );
                        if( res == Commander.ABORT ) break;
                        if( res == Commander.SKIP )  continue;
                        if( res == Commander.REPLACE ) 
                            sftp.rm( sftp_fn );
                    }
                } catch( Exception e1 ) {
                    sftp_exists = false;
                }
                if( dir ) {
                    if( !sftp_exists )
                        sftp.mkdir( sftp_fn );
                    counter += copyFiles( f.listFiles(), sftp_fn );
                    try {
                        sftp.setMtime( sftp_fn, (int)(f.lastModified() / 1000) );
                    } catch( Exception e ) {}
                    if( !noErrors() ) break;
                } else if( f.isFile() ) {
                    try {
                        sftp.put( f.getAbsolutePath(), sftp_fn, this );
                        if( stop )
                            throw new Exception("Interrupted");
                    } catch( Exception e ) {
                        error( ctx.getString( Utils.RR.fail_del.r(), sftp_fn ) );
                        sftp.rm( sftp_fn );
                        break;
                    }
                }
                sftp.setMtime( sftp_fn, (int)(f.lastModified() / 1000) );
                counter++;
                if( !stop && move && !f.delete() ) {
                    error( ctx.getString( Utils.RR.cant_del.r(), f.getCanonicalPath() ) );
                    break;
                }
            }
        } catch( Exception e ) {
            e.printStackTrace();
            error( e.getLocalizedMessage() );
        }
        return counter;
    }
}
