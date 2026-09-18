package com.ghostsq.commander.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.text.format.DateFormat;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.FilterProps;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.ghostsq.commander.adapters.CommanderAdapter.HIDE_MODE;
import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_DIRSZ;
import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_HIDDEN;
import static com.ghostsq.commander.adapters.CommanderAdapter.SHOW_DIRSZ;

public final class FSEngines {
    private   final static String TAG = "FSEngines";

    public interface IFileItem {
        File f();
    }
    
    public static class ListEngine extends Engine {
        private String      pass_back_on_done;
        private FSAdapter   a;
        private FileItem[]  fileItems = null;
        private File        dir = null;
        private int         dirPathLen;
        private SearchProps sq;
        private Context     ctx;
        private int         depth = 0;

        ListEngine( FSAdapter a, SearchProps sq, Handler h, String pass_back_on_done_ ) {
            setHandler( h );
            this.a = a;
            this.ctx = a.ctx;
            this.sq = sq;
            pass_back_on_done = pass_back_on_done_;
        }
        public File getDirFile() {
            return dir;
        }       
        public FileItem[] getFileItems() {
            return fileItems;
        }       
        @Override
        public void run() {
            String err_msg = null;
            String dir_name = a.getDir();
            if( sq != null ) {
                try {
                    ArrayList<FileItem> result = new ArrayList<FileItem>();
                    dir = new File( dir_name );
                    dirPathLen = Utils.mbAddSl( dir.getAbsolutePath() ).length();
                    searchInFolder( dir, result );
                    fileItems = new FileItem[result.size()];
                    result.toArray( fileItems );
                } catch( Exception e ) {
                    Log.e( TAG, dir_name, e );
                }
                sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                return;
            }

            File[] files = null;
            while( true ) {
                dir = new File( dir_name );
                files = dir.listFiles();
                if( files != null ) break;
                if( android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.S &&
                    Utils.mbAddSl( dir_name ).endsWith( "Android/data/" ) ) {
                    files = SimulateAndroidDataContent( dir_name );
                    if( files != null ) break;
                }
                if( err_msg == null )
                    err_msg = a.ctx.getString( R.string.no_such_folder, dir_name );
                if( dir.getAbsolutePath().startsWith( Environment.getExternalStorageDirectory().getAbsolutePath() ) )
                    dir_name = dir.getParent();
                else
                    dir_name = null;
                if( dir_name == null ) {
                    Log.e( TAG, "Wrong folder '" + a.getDir() + "'" );
                    sendProgress( a.s( R.string.inv_path ), Commander.OPERATION_FAILED, pass_back_on_done );
                    return;
                }
            }

            fileItems = filesToItems( files );

            if( err_msg != null ) {
               sendReport( err_msg );
            }
            sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
        }

        private File[] SimulateAndroidDataContent( String base ) {
            try {
                PackageManager pm = ctx != null ? ctx.getPackageManager() : null;
                if( pm == null ) return null;
                Intent in = new Intent( Intent.ACTION_MAIN ).addCategory( Intent.CATEGORY_LAUNCHER );
                List<ResolveInfo> res = pm.queryIntentActivities( in, 0 );
                ArrayList<File> al = new ArrayList<>( res.size() );
                for( ResolveInfo r : res ) {
                    File dir = new File( base, r.activityInfo.packageName );
                    if( dir.exists() )
                        al.add( dir );
                }
                return al.toArray( new File[0] );
            } catch( Exception e ) {
                Log.e( TAG, base, e );
            }
            return null;
        }

        protected FileItem[] filesToItems( File[] files_ ) {
            if( files_ == null ) return null;
            int num_files = files_.length;
            int num = num_files;
            int adapter_mode = a.getMode();
            boolean hide = ( adapter_mode & MODE_HIDDEN ) == HIDE_MODE;
            boolean dirsz= ( adapter_mode & MODE_DIRSZ  ) == SHOW_DIRSZ;
            ArrayList<FileItem> al = new ArrayList<FileItem>( num_files );
            int path_length = 0;
            if( a.getSearch() != null ) {
                String path =  Utils.mbAddSl( a.getUri().getPath() );
                path_length = path.length();
            }
            FilterProps filter = a.getFilter();
            boolean deferred = android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && filter == null && num_files > 100;

            for( int i = 0; i < num_files; i++ ) {
                File f = files_[i];
                if( hide && f.isHidden() ) continue;

                String fn = null;
                String link_target = ForwardCompat.readlink( f.getAbsolutePath() );
                if( link_target != null ) {
                    fn = f.getName();
                    f = new File( link_target );
                }
                FileItem f_item = new FileItem( f, adapter_mode, deferred );
                if( dirsz && f.isDirectory() ) {
                    try {
                        f_item.size = getSizes( f );
                    } catch( Exception e ) {
                        Log.e( TAG, f.getName(), e );
                    }
                }
                if( path_length > 0 ) {
                    String file_path = f.getAbsolutePath();
                    int end_pos = file_path.length() - f.getName().length();
                    f_item.setPrefix( file_path.substring( path_length, end_pos ) );
                }
                if( filter != null && !filter.isMatched( f_item ) ) continue;

                if( fn != null ) {
                    f_item.name = fn;
                    f_item.icon_id = R.drawable.link;
                }
                al.add( f_item );
            }
            FileItem[] items_ = new FileItem[al.size()];
            al.toArray( items_ );
            return items_;
        }

        protected final long getSizes( File dir ) throws Exception {
            long count = 0;
            File[] files = dir.listFiles();
            for( int i = 0; i < files.length; i++ ) {
                if( isStopReq() ) break;
                File f = files[i];
                if( f.isDirectory() ) {
                    if( depth++ > 30 )
                        throw new Exception( a.s( R.string.too_deep_hierarchy ) );
                    count += getSizes( f );
                    depth--;
                }
                else {
                    count += f.length();
                }
            }
            return count;
        }

        protected final void searchInFolder( File dir, ArrayList<FileItem> result ) throws Exception {
            try {
                String dir_path = dir.getAbsolutePath();
                long cur_time = System.currentTimeMillis();
                if( cur_time - progress_last_sent > 500 ) {
                    progress_last_sent = cur_time;
                    sendProgress( dir_path, progress = 0 );
                }
                if( dir_path.compareTo( "/sys" ) == 0 ) return;
                if( dir_path.compareTo( "/dev" ) == 0 ) return;
                if( dir_path.compareTo( "/proc" ) == 0 ) return;
                File[] subfiles = dir.listFiles();
                if( subfiles == null || subfiles.length == 0 )
                    return;
                FilterProps filter = a.getFilter();
                final double conv = 100. / subfiles.length;
                for( int i = 0; i < subfiles.length; i++ ) {
                    sleep( 1 );
                    if( stop || isInterrupted() )
                        throw new Exception( ctx.getString( R.string.interrupted ) );
                    File f = subfiles[i];
                    int np = (int)(i * conv);
                    if( np - conv + 1 > progress ) {
                        cur_time = System.currentTimeMillis();
                        if( cur_time - progress_last_sent > 1000 ) {
                            progress_last_sent = cur_time;
                            sendProgress( f.getAbsolutePath(), progress = np );
                        }
                    }
                    //Log.v( TAG, "Looking at file " + f.getAbsolutePath() );
                    if( isMatched( f ) ) {
                        FileItem fi = new FileItem( f );
                        if( filter == null || filter.isMatched( fi ) ) {
                            fi.attr = f.getPath();
                            if( fi.attr != null && fi.attr.length() > dirPathLen ) {
                                fi.attr = fi.attr.substring( dirPathLen );
                                int fnl = f.getName().length();
                                if( fnl < fi.attr.length() )
                                    fi.attr = fi.attr.substring( 0, fi.attr.length() - fnl );
                                else
                                    fi.attr = "./";

                            }
                            result.add( fi );
                        }
                    }
                    if( !sq.olo && f.isDirectory() ) {
                        if( depth++ > 30 )
                            throw new Exception( ctx.getString( R.string.too_deep_hierarchy ) );
                        searchInFolder( f, result );
                        depth--;
                    }
                }
            } catch( Exception e ) {
                Log.e( TAG, "Exception on search: ", e );
            }
        }

        private final boolean isMatched( File f ) {
            if( f == null ) return false;
            try {
                boolean dir = f.isDirectory();
                if( dir ) {
                    if( !sq.dirs  ) return false;
                } else {
                    if( !sq.files ) return false;
                }
                long modified = f.lastModified();
                if(  sq.mod_after != null && modified <  sq.mod_after.getTime() ) return false;
                if( sq.mod_before != null && modified > sq.mod_before.getTime() ) return false;
                long size = f.length();
                if( size < sq.larger_than || size > sq.smaller_than )
                    return false;
                if( !sq.match( f.getName() ) )
                    return false;
                if( sq.content != null && !dir && !searchInsideFile( f, sq.content ) )
                    return false;
                return true;
            }
            catch( Exception e ) {
                Log.e( TAG, f.getName(), e );
            }
            return false;
        }

        private final boolean searchInsideFile( File f, String search_for ) {
            FileInputStream fis = null;
            try {
                return searchInContent( new FileInputStream( f ), f.length(), f.getName(), search_for );
            }
            catch( Exception e ) {
                Log.e( TAG, "File: " + f.getName() + ", str=" + search_for, e );
            }
            return false;
        }
    }

	public static class AskEngine extends Engine {
	    private FSAdapter fsa;
        private String msg;
        private File   from, to;
        
	    AskEngine( FSAdapter fsa, Handler h_, String msg_, File from_, File to_ ) {
	        super.setHandler( h_ );
	        this.fsa = fsa;
	        msg = msg_;
	        from = from_;
	        to = to_;
	    }
	    @Override
        public void run() {
            try {
                int resolution = askOnFileExist( msg, fsa.commander );
                if( ( resolution & Commander.REPLACE ) != 0 ) {
                    if( to.delete() && from.renameTo( to ) ) {
                        sendResult( "ok" );
                        String[] to_scan = new String[] { from.getAbsolutePath(), to.getAbsolutePath() };
                        MediaScanEngine.scanMedia( fsa.ctx, to_scan );
                    }
                }
            } catch( InterruptedException e ) {
                e.printStackTrace();
            }
        }
    }

	public static class DeleteEngine extends Engine {
	    private CommanderAdapterBase cab;
		private File[]    mList;
		ArrayList<String> toRemove = null;
        TrashCan[] trashcans;

        private static class TrashCan {
            public File file;
            String path;
            int    root_pos;
            public TrashCan( File f ) {
                file = f;
                path = f.getAbsolutePath();
                root_pos = path.indexOf( "/Android/data" );
            }
            public boolean isOnSaveDev( String file_path ) {
                if( root_pos < 0 ) return false;
                return path.regionMatches( 0, file_path, 0, root_pos );
            }
        }

        DeleteEngine( CommanderAdapterBase cab, FileItem[] list ) {
            this( cab, new File[list.length] );
            for( int i = 0; i < list.length; i++ )
                mList[i] = list[i].f();
        }
        DeleteEngine( CommanderAdapterBase cab, File[] list ) {
            setName( ".DeleteEngine" );
            this.cab = cab;
            mList = list;
        }
        @Override
        public void run() {
            try {
                cab.Init( null );
                int trash_mode = cab.getMode( CommanderAdapter.MODE_TRASH );
                if( trash_mode == CommanderAdapter.TOTRASH_MODE ) {
                    File[] tcs = ctx.getExternalFilesDirs( "trashcan" );
                    if( tcs != null ) {
                        trashcans = new TrashCan[tcs.length];
                        for( int i = 0; i < tcs.length; i++ ) {
                            trashcans[i] = new TrashCan( tcs[i] );
                        }
                    }
                }
                int cnt = deleteFiles( mList );
                if( toRemove != null ) {
                    String[] to_scan_a = new String[toRemove.size()];
                    MediaScanEngine.scanMedia( cab.ctx, toRemove.toArray( to_scan_a ) );
                }
                if( mList.length == cnt && cnt == 1 ) {
                    String msg = mList[0].getName() + " " + cab.ctx.getString( R.string.was ) + " " + cab.ctx.getString( R.string.deleted );
                            sendResult( msg );
                    return;
                }
                sendResult( Utils.getOpReport( cab.ctx, cnt, R.string.deleted ) );
            }
            catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
            }
        }
        private final int deleteFiles( File[] l ) throws Exception {
    	    if( l == null ) return 0;

            int cnt = 0;
            int num = l.length;
            double conv = 100./num; 
            for( int i = 0; i < num; i++ ) {
                sleep( 1 );
                if( isStopReq() )
                    throw new Exception( cab.s( R.string.canceled ) );
                File f = l[i];
                sendProgress( cab.ctx.getString( R.string.deleting, f.getName() ), (int)(i * conv) );
                boolean dir = f.isDirectory();
                if( dir )
                    cnt += deleteFiles( f.listFiles() );
                boolean ok = false;
                if( trashcans != null ) {
                    String file_path = f.getAbsolutePath();
                    for( TrashCan tc : trashcans ) {
                        if( tc.isOnSaveDev( file_path ) ) {
                            File trashed = new File( tc.file, DateFormat.format( "yyyyMMdd HHmmss ", new Date() ) + f.getName() );
                            ok = f.renameTo( trashed );
                            break;
                        }
                    }
                } else
                    ok = f.delete();
                if( ok )
                    cnt++;
                else {
                    error( cab.ctx.getString( R.string.cant_del, f.getName() ) );
                    break;
                }
                String ext = Utils.getFileExt( f.getName() );
                boolean to_remove = ".jpg".equals( ext ) || ".mp3".equals( ext ) || ".flac".equals( ext ) || ".mp4".equals( ext );
                if( !to_remove ) {
                    String mime = Utils.getMimeByExt( ext );
                    to_remove = mime != null && (
                        mime.startsWith( "image/" ) ||
                        mime.startsWith( "audio/" ) ||
                        mime.startsWith( "video/" ) );
                }
                if( to_remove ) {
                    if( toRemove == null )
                        toRemove = new ArrayList<String>();
                    toRemove.add( f.getAbsolutePath() );
                }
            }
            return cnt;
        }
    }

    public static class CopyEngine extends CalcSizesEngine {
        private String  mDest;
        private int     counter = 0;
        private long    totalBytes = 0;
        private double  conv;
        private File[]  fList = null;
        private boolean move, delSrcDir, destIsFullName;
        private byte[]  buf;
        private static final int BUFSZ = 524288;
        private PowerManager.WakeLock wakeLock;
        private ArrayList<String> toScan;

        CopyEngine( CommanderAdapterBase cab, File[] list, String dest, int move_mode, boolean dest_is_full_name ) {
        	super( cab, null );
       	    setName( ".CopyEngine" );
        	fList = list;
            mDest = dest;
            move = ( move_mode & CommanderAdapter.MODE_MOVE ) != 0;
            delSrcDir = ( move_mode & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
            destIsFullName = dest_is_full_name;
            buf = new byte[BUFSZ];
                        
            PowerManager pm = (PowerManager)cab.ctx.getSystemService( Context.POWER_SERVICE );
            wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, TAG );
            toScan = new ArrayList<String>();
        }
        @Override
        public void run() {
        	sendProgress( cab.ctx.getString( R.string.preparing ), 0, 0 );
        	try {
                int l = fList.length;
                FileItem[] x_list = new FileItem[l];
                
                File src_dir_f = null;
                boolean in_same_src = true;
                for( int j = 0; j < l; j++ ) {
                    x_list[j] = new FileItem( fList[j] );
                    if( in_same_src ) {
                        File parent_f = fList[j].getParentFile();
                        if( src_dir_f == null )
                            src_dir_f = parent_f;
                        else
                            in_same_src = src_dir_f.equals( parent_f );
                    }
                }
                wakeLock.acquire();
				long sum = getSizes( x_list );
				conv = 100 / (double)sum;
				int num = copyFiles( fList, mDest, destIsFullName );
				if( delSrcDir && in_same_src && src_dir_f != null ) {
				    File[] to_delete = new File[1];
				    to_delete[0] = src_dir_f; 
				    DeleteEngine de = new DeleteEngine( cab, to_delete );
				    de.start();
				}
				wakeLock.release();
				// XXX: assume (move && !del_src_dir)==true when copy from app: to the FS 
	            String report = Utils.getOpReport( cab.ctx, num, move && !delSrcDir ? R.string.moved : R.string.copied );
	            sendResult( report );
	            
                if( toScan != null && toScan.size() > 0 ) {
                    String[] to_scan_a = new String[toScan.size()];
                    toScan.toArray( to_scan_a );
                    MediaScanEngine.scanMedia( cab.ctx, to_scan_a );
                }

			} catch( Exception e ) {
				sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
				return;
			}
        }
        private final int copyFiles( File[] list, String dest, boolean dest_is_full_name ) throws InterruptedException {
            File file = null;
            for( int i = 0; i < list.length; i++ ) {
                boolean existed = false;
                InputStream  is = null;
                OutputStream os = null;
                File outFile = null;
                file = list[i];
                if( file == null ) {
                    error( cab.ctx.getString( R.string.unkn_err ) );
                    break;
                }
                String uri = file.getAbsolutePath();
                try {
                    if( isStopReq() ) {
                        error( cab.ctx.getString( R.string.canceled ) );
                        break;
                    }
                    long last_modified = file.lastModified();
                    String fn = file.getName();
                    outFile = dest_is_full_name ? new File( dest ) : new File( dest, fn );
                    String out_dir_path = new File( dest ).getCanonicalPath();
                    boolean ok = false;
                    if( file.isDirectory() ) {
                        if( out_dir_path.startsWith( file.getCanonicalPath() ) ) {
                            error( cab.ctx.getString( R.string.cant_copy_to_itself, file.getName() ) );
                            break;
                        }
                        if( depth++ > 40 ) {
                            error( cab.ctx.getString( R.string.too_deep_hierarchy ) );
                            break;
                        }
                        else
                        if( outFile.exists() || outFile.mkdir() ) {
                            copyFiles( file.listFiles(), outFile.getAbsolutePath(), false );
                            if( errMsg != null )
                            	break;
                        }
                        else
                            error( cab.ctx.getString( R.string.cant_md, outFile.getAbsolutePath() ) );
                        depth--;
                        counter++;
                        ok = true;
                    }
                    else {
                        if( existed = outFile.exists() ) {
                            int res = askOnFileExist( cab.commander, cab.ctx.getString( R.string.file_exist, "<small>" + outFile.getAbsolutePath().replace( "&", "&amp;" ) + "</small>" ),
                                    file.lastModified(), outFile.lastModified(), file.length(), outFile.length(), NEW_OPTIONS );
                            if( res == Commander.SKIP )  continue;
                            if( res == Commander.REPLACE_OLD ) {
                                if( file.lastModified() > outFile.lastModified() )
                                    res = Commander.REPLACE;
                                else
                                    continue;
                            }
                            if( res == Commander.REPLACE ) {
                                if( outFile.equals( file ) )
                                    continue;
                                else
                                    outFile.delete();
                            }
                            if( res == Commander.ABORT ) break;
                        }
                        if( move ) {    // first try to move by renaming
                            long len = file.length();
                            if( toScan != null ) {
                                toScan.add(    file.getAbsolutePath() );
                                toScan.add( outFile.getAbsolutePath() );
                            }
                            if( file.renameTo( outFile ) ) {
                                counter++;
                                totalBytes += len;
                                int  so_far = (int)(totalBytes * conv);
                                sendProgress( outFile.getName() + " " + cab.ctx.getString( R.string.moved ), so_far, 0 );
                                outFile.setLastModified( last_modified );
                                continue;
                            }
                        }
                        
                        is = new FileInputStream( file );
                        os = new FileOutputStream( outFile );

                        super.progress = (int)(totalBytes * conv);
                        long size = file.length();
                        ok = super.copy( cab.ctx, is, os, file.getName(), size );
                        is.close();
                        os.close();
                        is = null;
                        os = null;
                        if( ok ) {
                            totalBytes += size;
                            if( toScan != null ) {
                                String ext = Utils.getFileExt( outFile.getName() );
                                String mime = Utils.getMimeByExt( ext );
                                    if( mime != null && ( mime.startsWith( "image/" ) || mime.startsWith( "audio/" ) || mime.startsWith( "video/" ) ) )
                                    toScan.add( outFile.getAbsolutePath() );
                            }
                            counter++;
                        }
                    }
                    if( ok ) {
                        if( move )
                            file.delete();
                        outFile.setLastModified( last_modified );
                        outFile.setWritable( true, false );
                        outFile.setReadable( true, false );
                    } else
                        outFile.delete();
                }
                catch( SecurityException e ) {
                    Log.e( TAG, "", e );
                    error( cab.ctx.getString( R.string.sec_err, e.getMessage() ) );
                }
                catch( FileNotFoundException e ) {
                    Log.e( TAG, "", e );
                    error( cab.ctx.getString( R.string.not_accs, e.getMessage() ) );
                }
                catch( ClosedByInterruptException e ) {
                    Log.e( TAG, "", e );
                    error( cab.ctx.getString( R.string.canceled ) );
                }
                catch( IOException e ) {
                    Log.e( TAG, "", e );
                    String msg = e.getMessage();
                    error( cab.ctx.getString( R.string.acc_err, uri, msg != null ? msg : "" ) );
                }
                catch( RuntimeException e ) {
                    Log.e( TAG, "", e );
                    error( cab.ctx.getString( R.string.rtexcept, uri, e.getMessage() ) );
                }
                finally {
                    try {
                        if( is != null )
                            is.close();
                        if( os != null )
                            os.close();
                        if( !move && errMsg != null && outFile != null && !existed ) {
                            Log.i( TAG, "Deleting failed output file" );
                            outFile.delete();
                        }
                    }
                    catch( IOException e ) {
                        error( cab.ctx.getString( R.string.acc_err, uri, e.getMessage() ) );
                    }
                }
            }
            return counter;
        }
    }

    public static class Receiver implements IReceiver, IDestination {
        private Uri     mDest;
        private File    dirFile;

        Receiver( Uri dir ) {
            mDest = dir;
            dirFile = new File( dir.getPath() );
        }

        private final Uri buildItemUri( String name ) {
            return mDest.buildUpon().appendPath( name ).build();
        }

        @Override
        public OutputStream receive( String fn ) {
            try {
                if( fn == null ) return null;
                File new_file = new File( dirFile, fn );
                return new FileOutputStream( new_file );
            } catch( FileNotFoundException e ) {
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
            File file = new File( dirFile, name );
            if( !file.exists() ) return null;
            return mDest.buildUpon().appendPath( name ).build();
        }

        @Override
        public boolean isDirectory( Uri item_uri ) {
            if( item_uri == null ) return false;
            File file = new File( item_uri.getPath() );
            return file.isDirectory();
        }

        @Override
        public Uri makeDirectory( String new_dir_name ) {
            try {
                File new_dir_file = new File( dirFile, new_dir_name );
                new_dir_file.mkdir();
                return getItemURI( new_dir_name, true );
            } catch( Exception e ) {
                Log.e( TAG, mDest + " / " + new_dir_name, e );
            }
            return null;
        }

        @Override
        public boolean delete( Uri item_uri ) {
            try {
                if( item_uri == null ) return false;
                File file = new File( item_uri.getPath() );
                if( !file.exists() ) return false;
                return file.delete();
            } catch( Exception e ) {
                Log.e( TAG, item_uri.getPath(), e );
            }
            return false;
        }

        @Override
        public boolean setDate( Uri item_uri, Date timestamp ) {
            try {
                String item_path = item_uri.getPath();
                File item_file = new File( item_path );
                if( !item_file.exists() ) return false;
                Utils.setFullPermissions( item_file );
                item_file.setLastModified( timestamp.getTime() );
                return true;
            } catch( Exception e ) {
                Log.e( TAG, item_uri.getPath(), e );
            }
            return false;
        }

        @Override
        public boolean done() {
            return true;
        }

        // --- IDestination ---

        @Override
        public CommanderAdapter.Item getItem( Uri item_uri ) {
            return FSAdapter.getItemByUri( item_uri );
        }
    }
}
