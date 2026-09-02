package com.ghostsq.commander.sftp;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.PowerManager;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.OutputStream;

class CopyFromEngine extends SFTPEngineBase 
{
    private   final static int BLOCK_SIZE = 32768;
    private   Commander     commander;
    private   String        src_path;
    private   CommanderAdapter rcp;
    private   boolean       move;
    protected WifiLock      wifiLock;
    private   PowerManager.WakeLock wakeLock;

    CopyFromEngine( Commander c, SFTPAdapter a, Item[] list, boolean move_, CommanderAdapter rcp ) {
        super( a, list );
        commander = c;
        src_path = Utils.mbAddSl( adapter.getUri().getPath() );
        mList = list;
        move = move_;
        this.rcp = rcp;

        WifiManager manager = (WifiManager)ctx.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( WifiManager.WIFI_MODE_FULL, TAG );
        wifiLock.setReferenceCounted( false );
        PowerManager pm = (PowerManager)ctx.getSystemService( Context.POWER_SERVICE );
        wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "GC:CopyToSFTP" );
        wakeLock.setReferenceCounted( false );
    }
    @Override
    public void run() {
        try {
            sftp = adapter.getChannel();
            if( sftp == null ) throw new Exception( "Not connected" );

            if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
                FSAdapter fsa = new FSAdapter( ctx );
                fsa.setUri( Uri.parse( Utils.createTempDir( ctx ).getAbsolutePath() ) );
                super.recipient = rcp.getReceiver();
                rcp = fsa;
            } else
                recipient = null;

            wifiLock.acquire();
            wakeLock.acquire( 3600000 );
            int total = copyFiles( mList, "", rcp.getUri() );
            if( recipient != null ) {
                  sendReceiveReq( new File( rcp.getUri().getPath() ), move );
                  return;
            }
            sendResult( Utils.getOpReport( ctx, total, move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
        } catch( InterruptedException e ) {
            Log.e( TAG, null, e );
            error( ctx.getString( Utils.RR.interrupted.r() ) );
        } catch( Exception e ) {
            Log.e( TAG, null, e );
            error( ctx.getString( Utils.RR.failed.r(), e.getLocalizedMessage() ) );
        } finally {
            finalize();
            wifiLock.release();
            wakeLock.release();
        }
    }
    
    private final int copyFiles( Item[] list, String path, Uri dest_dir_uri ) throws InterruptedException {
        int counter = 0;
        try {
            boolean top_level = path.length() == 0;
            if( top_level )
                super.progress = 0;
            IReceiver receiver = rcp.getReceiver( dest_dir_uri );
            for( int i = 0; i < list.length; i++ ) {
                if( stop || isInterrupted() ) {
                    error( ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                if( top_level )
                    super.progress = i * 100 / list.length;
                Item item = list[i];
                if( item == null || skip( item ) )
                    continue;

                String file_name = item.name;
                String rel_path_name = path + item.getPath();
                String sftp_path_name = src_path + rel_path_name;
                if( item.dir ) {
                    Uri dest_subdir_uri = super.handleDirOnReceiver( commander, receiver, file_name );
                    if( dest_subdir_uri == null )
                        break;
                    Item[] subItems = getItems( sftp_path_name );
                    if( subItems != null & subItems.length > 0 )
                        counter += copyFiles( subItems, rel_path_name + CommanderAdapterBase.SLS, dest_subdir_uri );
                    if( !noErrors() ) break;
                    if( move ) try {
                        sftp.rmdir( sftp_path_name );
                    } catch( SftpException sftp_e ) {
                        Log.e( TAG, sftp_path_name, sftp_e );
                    }
                }
                else {
                    Uri dest_item_uri = receiver.getItemURI( file_name, false );
                    int res = handleItemOnReceiver( commander, receiver, dest_item_uri, file_name );
                    if( res == Commander.ABORT ) break;
                    if( res == Commander.SKIP ) continue;

                    OutputStream os = receiver.receive( file_name );
                    if( os == null ) {
                        Log.e( TAG, "No output stream, file: " + sftp_path_name );
                        error( ctx.getString( Utils.RR.rtexcept.r(), file_name, "" ) );
                        break;
                    }
                    try {
                        sftp.get( sftp_path_name, os, this );
                        if( !stop && move && super.operationSuccess )
                            sftp.rm( sftp_path_name );
                        if( stop )
                            throw new Exception("Interrupted");
                    } catch( Exception e ) {
                        Log.e( TAG, "file: " + sftp_path_name, e );
                        error( e.getMessage() );
                        error( ctx.getString( Utils.RR.fail_del.r(), rel_path_name ) );
                        Uri diu = receiver.getItemURI( file_name, false );
                        if( diu != null )
                            receiver.delete( diu );
                        break;
                    }
                }
                Uri dest_item_uri = receiver.getItemURI( item.name, item.dir );
                java.util.Date ftp_file_date = item.date;
                if( dest_item_uri != null && ftp_file_date != null )
                    receiver.setDate( dest_item_uri, ftp_file_date );
                counter++;
            }
        }
        catch( RuntimeException e ) {
            Log.e( TAG, "", e );
            error( "Runtime Exception: " + e.getLocalizedMessage() );
        }
        return counter;
    }
}
