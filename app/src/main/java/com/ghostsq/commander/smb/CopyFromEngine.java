package com.ghostsq.commander.smb;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.InputStream;
import java.util.Date;

import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

class CopyFromEngine extends Engine // From a SMB share to receiver
{
    public final static String TAG = "CopyFromEngine";
    protected static int BLOCK_SIZE = 8192;
    private Commander commander;
    private Context   ctx;
    private CommanderAdapter rcp;
    private SmbFile[] mList;
    private boolean   move;
    private WifiLock  wifiLock;
    private WakeLock  wakeLock;

    static {
        long free = Runtime.getRuntime().freeMemory();
        BLOCK_SIZE = free < 65536 ? 32768 : (int)free / 3;
        Log.d( TAG, "Buffer size: " + BLOCK_SIZE + " free=" + free );        
    }
    
    CopyFromEngine( Commander commander_, SmbFile[] list, boolean move_, CommanderAdapter rcp ) {
        commander = commander_;
        ctx = commander.getContext();
        mList = list;
        move = move_;
        this.rcp = rcp;
        WifiManager manager = (WifiManager)ctx.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG );
        wifiLock.setReferenceCounted( false );
        PowerManager pm = (PowerManager)ctx.getSystemService( Context.POWER_SERVICE );
        wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "GC:CopyFromSMB" );
        wakeLock.setReferenceCounted( false );
    }
    @Override
    public void run() {
        if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
            FSAdapter fsa = new FSAdapter( commander.getContext() );
            fsa.setUri( Uri.parse( Utils.createTempDir( ctx ).getAbsolutePath() ) );
            super.recipient = rcp.getReceiver();
            rcp = fsa;
        } else
            recipient = null;
        wifiLock.acquire();
        wakeLock.acquire();
        int total = copyFiles( mList, "", rcp.getUri() );
        wakeLock.release();
        wifiLock.release();
        if( recipient != null ) {
            sendReceiveReq( new File( rcp.getUri().getPath() ), move );
            return;
        }
        sendResult( Utils.getOpReport( ctx, total, Utils.RR.copied.r() ) );
        super.run();
    }

    private final int copyFiles( SmbFile[] list, String path, Uri dest_dir_uri ) {
        int counter = 0;
        try {
            IReceiver receiver = rcp.getReceiver( dest_dir_uri );
            int total_progress = 0;
            long total_copied = 0, total_size = 0;
            long dir_size = 0;
            for( int i = 0; i < list.length; i++ ) {
                SmbFile f = list[i];
                synchronized( f ) {
                    if( !f.isDirectory() )
                        dir_size += f.length();
                }
            }

            for( int i = 0; i < list.length; i++ ) {
                SmbFile f = list[i];
                if( f == null ) continue;
                String rel_name;
                synchronized( f ) {
                    if( !f.exists() )
                        continue;
                    String file_name = f.getName();
                    rel_name = path + file_name;
                    if( f.isDirectory() ) {
                        Uri dest_subdir_uri = super.handleDirOnReceiver( commander, receiver, file_name );
                        if( dest_subdir_uri == null )
                            break;
                        SmbFile[] subItems = f.listFiles();
                        if( subItems == null ) {
                            errMsg = "Failed to get the file list of the subfolder '" + rel_name + "'.\n";
                            break;
                        }
                        counter += copyFiles( subItems, rel_name, dest_subdir_uri );
                        if( errMsg != null )
                            break;
                        if( move )
                            f.delete();
                    } else {
                        Uri dest_item_uri = receiver.getItemURI( file_name, false );
                        int res = handleItemOnReceiver( commander, receiver, dest_item_uri, file_name );
                        if( res == Commander.ABORT ) break;
                        if( res == Commander.SKIP ) continue;
                        InputStream in = f.getInputStream();
                        boolean ok;
                        long file_size = f.length();
                        if( file_size < 1000000 || rcp.hasFeature( CommanderAdapter.Feature.FS ) ||
                                ( BuildConfig.DEBUG && file_name.charAt( 0 ) == 'I' ) )
                            // FIXME - use super.progress instead of total_progress
                            ok = super.copyStreamToReceiver( ctx, receiver, in, file_name, file_size, new Date( f.getLastModified() ), total_progress );
                        else
                            ok = super.conveyStreamToReceiver( ctx, receiver, in, file_name, file_size, new Date( f.getLastModified() ) );
                        if( ok ) {
                            total_copied += file_size;
                            total_progress = dir_size > 0 ? (int)(total_copied * 100 / dir_size) : 100;
                            counter++;
                            if( move )
                                f.delete();
                        }
                    }
                }
            }
        } catch( SmbException e ) {
            error( e.getMessage() );
            Log.e( TAG, "", e );
            Throwable t = e.getCause();
            if( t != null )
                error( t.getMessage() );
        } catch( Throwable e ) {
            error( e.getMessage() );
            Log.e( TAG, "", e );
        }
        return counter;
    }
      
}
