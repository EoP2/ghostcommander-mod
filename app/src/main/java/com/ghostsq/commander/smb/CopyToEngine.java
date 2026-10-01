package com.ghostsq.commander.smb;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

class CopyToEngine extends Engine // From a local fs to SMB share  
{
    public final static String TAG = "CopyToEngine";
    private   Commander commander;
    private   SMBAdapter owner;
    private   Context ctx;
    private   File[]  mList;
    private   String  dest_uri;
    private   boolean move = false;
    private   boolean del_src_dir = false;
    private    WifiManager.WifiLock wifiLock;
    private   PowerManager.WakeLock wakeLock;
    public    byte buf[];

    CopyToEngine( Commander commander, SMBAdapter owner, File[] list, String dest_uri, int move_mode_ ) {
        long free = Runtime.getRuntime().freeMemory();
        int buf_sz = Math.min( (int)(free / 8), 65535 );
        Log.d( TAG, "Buffer size: " + buf_sz + ", free=" + free );
        buf = new byte[buf_sz];
        this.commander = commander;
        ctx = commander.getContext();
        this.owner = owner;
        mList = list;
        this.dest_uri = dest_uri;
        move = ( move_mode_ & CommanderAdapter.MODE_MOVE ) != 0;
        del_src_dir = ( move_mode_ & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
        wifiLock = wm.createWifiLock( WifiManager.WIFI_MODE_FULL, TAG );
        wifiLock.setReferenceCounted( false );
        PowerManager pm = (PowerManager)ctx.getSystemService( Context.POWER_SERVICE );
        wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "GC:CopyToSMB" );
        wakeLock.setReferenceCounted( false );
    }

    private static class DirInfo {
        public File f_dir, f_base_dir;
        DirInfo( File f_base_dir, File f_dir ) {
            this.f_dir = f_dir;
            this.f_base_dir = f_base_dir;
        }
    }

    @Override
    public void run() {
        if( dest_uri == null || mList == null || mList.length == 0 ) return;
        try {
            ArrayList<DirInfo> dir_list = new ArrayList<DirInfo>();
            for( File f : mList ) {
                if( f.isDirectory() ) {
                    DirInfo di = new DirInfo( f.getParentFile(), f );
                    dir_list.add( di );
                    collectDirs( f.getParentFile(), f, dir_list );
                }
            }
            if( BuildConfig.DEBUG )
                sendProgress( "Found " + dir_list.size() + " directories", 0 );

            wifiLock.acquire();
            wakeLock.acquire();
            CIFSContext cifs_ctx = owner.getCIFSContext( false, true );
            if( cifs_ctx == null ) {
                Log.e( TAG, "No CIFS context!" );
                return;
            }
            SmbFile to = new SmbFile( dest_uri, cifs_ctx );
            int cnt = copyFiles( mList, to );

            for( DirInfo di : dir_list ) {
                String base_dir_path = di.f_base_dir.getAbsolutePath();
                String dir_path = di.f_dir.getAbsolutePath();
                int pp = dir_path.indexOf( base_dir_path );
                if( pp != 0 ) {
                    error("'" + base_dir_path + "' not a base of '" + dir_path + "'!" );
                    return;
                }
                base_dir_path = Utils.mbAddSl( base_dir_path );
                dir_path = Utils.mbAddSl( dir_path );
                String rel_path = dir_path.substring( base_dir_path.length() );
                SmbFile new_smb_dir = Utils.str( rel_path ) ? new SmbFile( to, Utils.unEscape( rel_path ) ) : to;
                cnt += copyFiles( di.f_dir.listFiles(), new_smb_dir );
                if( isStopReq() ) {
                    sendResult( ctx.getString( Utils.RR.interrupted.r() ) );
                    return;
                }
            }

            if( del_src_dir ) {
                try {
                    File src_dir = mList[0].getParentFile();
                    if( src_dir != null ) {
                        File[] content = src_dir.listFiles();
                        for( File file : content ) {
                            file.delete();
                        }
                        src_dir.delete();
                    }
                } catch( Exception e ) {
                    Log.e( TAG, "", e );
                }
            }
            sendResult( Utils.getOpReport( ctx, cnt, move && !del_src_dir ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
            super.run();
            return;
        } catch( Exception e ) {
            Log.e( TAG, dest_uri, e );
        } catch( OutOfMemoryError oom ) {
            Log.e( TAG, "OOM, free: " + Runtime.getRuntime().freeMemory(), oom );
            error( "Out of memory!.." );
        } finally {
            wifiLock.release();
            wakeLock.release();
        }
        sendResult( ctx.getString( Utils.RR.failed.r() ) );
    }

    private void collectDirs( File base_dir, File dir, ArrayList<DirInfo> out_list ) {
        File[] list = dir.listFiles();
        if( list == null )
            return;
        for( File f : list ) {
            if( !f.isDirectory() )
                continue;
            DirInfo di = new DirInfo( base_dir, f );
            out_list.add( di );
            collectDirs( base_dir, f, out_list );
        }
    }

    private final int copyFiles( File[] list, SmbFile dest ) {
        int counter = 0;
        try {
            long num = list.length;
            long dir_size = 0, byte_count = 0;
            for( int i = 0; i < num; i++ ) {
                File f = list[i];               
                if( !f.isDirectory() )
                    dir_size += f.length();
            }
            double conv = 100./(double)dir_size;
            for( int i = 0; i < num; i++ ) {
                if( stop || isInterrupted() ) {
                    error( ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                File f = list[i];
                if( f != null && f.exists() ) {
                    boolean dir = f.isDirectory(); 
                    String fn = f.getName();
                    if( dir ) fn += "/";
                    String unesc_fn = Utils.unEscape( fn );
                    SmbFile new_file = new SmbFile( dest, unesc_fn );
                    boolean exists = new_file.exists();
                    if( exists ) {
                        int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), new_file.getPath() ), commander );
                        if( res == Commander.ABORT ) break;
                        if( res == Commander.SKIP )  continue;
                        if( res == Commander.REPLACE ) {
                            if( !dir ) 
                                new_file.delete();
                        }
                    }
                    if( dir ) {
                        if( !exists )
                            new_file.mkdir();
/*
                        counter += copyFiles( f.listFiles(), new_file );
                        if( errMsg != null ) break;
 */
                    } else if( f.isFile() ) {
                        new_file.createNewFile();
                        OutputStream out = new_file.getOutputStream();
                        InputStream in = new FileInputStream( f );
                        
                        long done = 0, nn = 0, start_time = 0;
                        int  n = 0;
                        int  so_far = (int)(byte_count * conv);
                        try {
                            String path_name = f.getAbsolutePath();
                            int pnl = path_name.length();
                            String cur_op_s = ctx.getString( Utils.RR.uploading.r(), 
                                    pnl > CUT_LEN ? "\u2026" + path_name.substring( pnl - CUT_LEN ) : path_name );
                            String     sz_s = Utils.getHumanSize( f.length() );
                            int speed = 0;
                            while( true ) {
                                if( isStopReq() ) {
                                    in.close();
                                    out.close();
                                    error( ctx.getString( Utils.RR.fail_del.r(), new_file.getName() ) );
                                    new_file.delete();
                                    return counter;
                                }                                
                                if( nn == 0 ) {
                                    start_time = System.currentTimeMillis();
                                    sendProgress( cur_op_s + sizeOfsize( done, sz_s ), so_far, (int)(byte_count * conv), speed );
                                }
                                n = in.read( buf );
                                if( n < 0 ) break;
                                out.write( buf, 0, n );
                                byte_count += n;
                                done       += n;
                                nn         += n;
                                long time_delta = System.currentTimeMillis() - start_time;
                                if( time_delta > DELAY ) {
                                    speed = (int)(MILLI * nn / time_delta);
                                    nn = 0;
                                }
                            }
                            sendProgress( cur_op_s + sizeOfsize( done, sz_s ), so_far, (int)(byte_count * conv), speed );
                            in.close();
                        } catch( Exception e ) {
                            error( ctx.getString( Utils.RR.fail_del.r(), new_file.getName() ) );
                            new_file.delete();
                            break;
                        }
                        finally {
                            if( in  != null ) in.close();
                            if( out != null ) out.close();
                        }
                    }
                    try {
                        new_file.setLastModified( f.lastModified() );
                    } catch( Exception e ) {
                        Log.e( TAG, "can't change date for the file " + new_file.getName() );
                    }
                    counter++;
                    
                    if( move ) {
                        unesc_fn = Utils.unEscape( fn );
                        SmbFile copied_file = new SmbFile( dest, unesc_fn );
                        if( copied_file.exists() && ( f.isDirectory() || f.length() == copied_file.length() ) ) { 
                            if( !f.delete() ) {
                                error( ctx.getString( Utils.RR.cant_del.r(), f.getCanonicalPath() ) );
                                break;
                            }
                        } else {
                            Log.e( TAG, "No copied file in destination folder! " + f.getName() );
                        }
                    }
                }
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
            error( e.getLocalizedMessage() );
        }
        return counter;
    }
}
