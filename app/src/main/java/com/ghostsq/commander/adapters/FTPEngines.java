package com.ghostsq.commander.adapters;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.FTPAdapter.FTPCredentials;
import com.ghostsq.commander.utils.FTP;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Replacer;
import com.ghostsq.commander.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_DIRSZ;
import static com.ghostsq.commander.adapters.CommanderAdapter.SHOW_DIRSZ;

public final class FTPEngines {

    private static abstract class FTPEngine extends Engine 
    {
        protected Context        ctx;
        protected FTPCredentials crd;
        protected FTP            ftp;
        protected Uri            uri;

        FTPEngine( Context ctx_, FTPCredentials crd_, Uri uri_, FTP ftp_ ) {    // borrows FTP object
            if( crd_ == null )
                crd_ = new FTPCredentials( uri_.getUserInfo() ); 
            this.ctx = ctx_;
            this.crd = crd_;
            this.uri = uri_;
            this.ftp = ftp_;
        }
        FTPEngine( Context ctx_, FTPCredentials crd_, Uri uri_, boolean active, Charset cs ) {
            this( ctx_, crd_, uri_, new FTP() );
            ftp.setActiveMode( active );
            ftp.setCharset( cs );
        }
    }    

    public static class ListEngine extends FTPEngine {
        private FTPAdapter owner;
        private int mode = 0;
        private boolean ascending;
        private boolean needReconnect;
        private LsItem[] items_tmp;
        private String path;
        private SearchProps sq;
        private int depth = 0;
        public  String pass_back_on_done;


        ListEngine( FTPAdapter a, Handler h, FTP ftp_, boolean need_reconnect_, SearchProps sq, String pass_back_on_done_ ) {
            super( a.ctx, (FTPCredentials)a.getCredentials(), a.getUri(), ftp_ );
            owner = a;
            setHandler( h );
            needReconnect = need_reconnect_;
            pass_back_on_done = pass_back_on_done_;
            this.mode = a.getMode();
            this.ascending = ( this.mode & CommanderAdapter.MODE_SORT_DIR ) == CommanderAdapter.SORT_ASC;
            this.sq = sq;
        }

        public LsItem[] getItems() {
            return items_tmp;
        }
        public String getActualPath() {
            return path;
        }

        @Override
        public void run() {
            try {
                if( uri == null ) {
                    sendProgress( "Wrong URI", Commander.OPERATION_FAILED );
                    return;
                }
                Log.i( TAG, "ListEngine started" );
                threadStartedAt = System.currentTimeMillis();
                ftp.clearLog();
                if( needReconnect  && ftp.isLoggedIn() ) {
                    ftp.disconnect( false );
                }
                if( crd == null || crd.isNotSet() )
                    crd = new FTPCredentials( uri.getUserInfo() );
                int cl_res = ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true );
                if( cl_res < 0 ) {
                    if( cl_res == FTP.NO_LOGIN ) 
                        sendLoginReq( uri.toString(), crd, pass_back_on_done );
                    return;
                }
                if( cl_res == FTP.LOGGED_IN )
                    sendProgress( ctx.getString( R.string.ftp_connected,  
                            uri.getHost(), crd.getUserName() ), Commander.OPERATION_STARTED );

                if( ftp.isLoggedIn() ) {
                    //Log.v( TAG, "ftp is logged in" );
                    if( sq != null ) {
                        try {
                            ArrayList<LsItem> result = new ArrayList<>();
                            path = ftp.getCurrentDir();
                            searchInDirectory( path, result );
                            items_tmp = new LsItem[result.size()];
                            result.toArray( items_tmp );
                        } catch( Exception e ) {
                            Log.e( TAG, uri.toString(), e );
                        }
                        sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                        return;
                    }
                    boolean dir_sz = ( owner.getMode() & MODE_DIRSZ ) == SHOW_DIRSZ;
                    items_tmp = ftp.getDirList( null, ( mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.SHOW_MODE );
                    if( items_tmp != null  ) {
                        path = ftp.getCurrentDir();
                        if( path != null ) {
                            String path_sl = Utils.mbAddSl( path );
                            boolean need_restore_wd = false;
                            for( LsItem lsi : items_tmp ) {
                                String name = lsi.getName();
                                if( name == null ) continue;
                                if( dir_sz && lsi.isDirectory() )
                                    lsi.size = getSizes( path_sl + lsi.getName() );
                                String lt = lsi.getLinkTarget();
                                if( !Utils.str( lt ) ) continue;
                                if( lt.charAt( 0 ) != '/' )
                                    lt = path_sl + lt;
                                need_restore_wd = true;
                                if( ftp.setCurrentDir( lt ) ) {
                                    lsi.setDirectory();
                                    if( dir_sz )
                                        lsi.size = getSizes( path_sl + lsi.getName() );
                                }
                            }
                            if( need_restore_wd )
                                ftp.setCurrentDir( path );
                            synchronized( uri ) {
                                uri = uri.buildUpon().encodedPath( path ).build();
                            }
                        }
                        //Log.v( TAG, "Got the entries list" );
                        if( items_tmp.length > 0 ) {
                            ItemComparator comp = new ItemComparator( mode & CommanderAdapter.MODE_SORTING, (mode & CommanderAdapter.MODE_CASE) != 0, ascending );
                            Arrays.sort( items_tmp, comp );
                        }
                        //Log.v( TAG, "entries list sorted" );
                        sendProgress( !dir_sz && tooLong( 8 ) ? ftp.getLog() : null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                        return;
                    }
                    else {
                        Log.e( TAG, "Can't get the entries list" );
                        needReconnect = true;
                    }
                }
                else
                    Log.e( TAG, "Did not log in." );
            }
            catch( UnknownHostException e ) {
                ftp.debugPrint( "Unknown host:\n" + e.getLocalizedMessage() );
            }
            catch( IOException e ) {
                ftp.debugPrint( "IO exception:\n" + e.getLocalizedMessage() );
                Log.e( TAG, "", e );
            }
            catch( Exception e ) {
                ftp.debugPrint( e.getLocalizedMessage() );
                Log.e( TAG, "", e );
            }
            finally {
                super.run();
            }
            ftp.disconnect( true );
            sendProgress( ftp.getLog(), Commander.OPERATION_FAILED, pass_back_on_done );
        }

        protected final long getSizes( String path ) {
            long total = 0;
            try {
                LsItem[] list = ftp.getDirList( path, true );
                for( int i = 0; i < list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.interrupted ) );
                        break;
                    }
                    LsItem f = list[i];
                    if( f != null ) {
                        if( f.isDirectory() ) {
                            if( depth++ > 30 )
                                throw new Exception( ctx.getString( R.string.too_deep_hierarchy ) );
                            --depth;
                            total += getSizes( path + f.getName() );
                        }
                        else {
                            total += f.length();
                        }
                    }
                }
            }
            catch( Exception e ) {
                Log.e( TAG, path, e );
            }
            return total;
        }

        private final int searchInDirectory( String in_path, ArrayList<LsItem> result ) throws Exception {
            long cur_time = System.currentTimeMillis();
            if( cur_time - progress_last_sent > 500 ) {
                progress_last_sent = cur_time;
                sendProgress( uri.getPath(), progress = 0 );
            }

            String active = uri.getQueryParameter( FTPAdapter.QP_ACTIVE );
            String encode = uri.getQueryParameter( FTPAdapter.QP_ENCODE );
            String subpath = in_path == path ? "" : Utils.mbAddSl( in_path.substring( path.length() + 1 ) );

            LsItem[] subitems = ftp.getDirList( in_path, ( mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.SHOW_MODE );
            if( subitems == null ) return 0;
            final double conv = 100. / subitems.length;
            int i = 0;
            for( LsItem item : subitems ) {
                i++;
                if( isStopReq() ) return 0;
                int np = (int)(i * conv);
                if( np - conv + 1 >= progress ) {
                    cur_time = System.currentTimeMillis();
                    if( sq.content != null || cur_time - progress_last_sent > DELAY ) {
                        progress_last_sent = cur_time;
                        sendProgress( in_path, progress = np );
                    }
                }
                if( item.uri == null ) {
                    Uri.Builder ub = uri.buildUpon().path( in_path ).appendPath( item.name ).clearQuery();
                    if( Utils.str( active ) ) ub.appendQueryParameter( FTPAdapter.QP_ACTIVE, active );
                    if( Utils.str( encode ) ) ub.appendQueryParameter( FTPAdapter.QP_ENCODE, encode );
                    item.uri = ub.build();
                }
                if( isMatched( in_path, item ) ) {
                    item.setPrefix( subpath );
                    item.attr = "./" + subpath;
                    result.add( item );
                }
                if( !sq.olo && item.isDirectory() ) {
                    if( depth++ > 30 )
                        error( ctx.getString( R.string.too_deep_hierarchy ) );
                    else
                        searchInDirectory( in_path + File.separator + item.getName(), result );
                    depth--;
                }
            }
            sendProgress( in_path, 100 );
            return i;
        }

        private final boolean isMatched( String in_path, LsItem item ) {
            if( !super.isMatched( item, sq ) )
                return false;
            if( sq.content == null || item.dir )
                return true;
            InputStream is = null;
            try {
                Uri item_uri = item.getUri();
                is = owner.getContent( item_uri, 0 );
                if( is != null && searchInContent( is, item.size, in_path + File.separator + item.getName(), sq.content ) )
                    return true;
            } catch( Exception e ) {
                Log.e( TAG, item.name, e );
            } finally {
                owner.closeStream( is );
            }
            return false;
        }
    }

    private static abstract class CopyEngine extends FTPEngine implements FTP.ProgressSink 
    {
        private   long      startTime;
        private   long      curFileLen = 0, curFileDone = 0, secDone = 0;
        protected WifiLock  wifiLock;
        protected String    progressMessage = null;

        CopyEngine( Context ctx_, FTPCredentials crd_, Uri uri_, boolean active, Charset cs ) {
            super( ctx_, crd_, uri_, active, cs );
            startTime = System.currentTimeMillis();
            WifiManager manager = (WifiManager)ctx.getSystemService( Context.WIFI_SERVICE );
            wifiLock = manager.createWifiLock( android.os.Build.VERSION.SDK_INT >= 12 ? 3 : WifiManager.WIFI_MODE_FULL, TAG );
            wifiLock.setReferenceCounted( false );
        }
        protected void setCurFileLength( long len ) {
            curFileDone = 0;
            curFileLen  = len;
        }
        
        @Override
        public boolean completed( long size, boolean done ) throws InterruptedException {
            if( curFileLen > 0 ) {
                curFileDone += size;
                secDone += size;
                long cur_time = System.currentTimeMillis();
                long time_delta = cur_time - startTime;
                if( done || time_delta > DELAY ) {    // once a sec. only
                    int  speed = (int)( MILLI * secDone / time_delta ); 
                    sendProgress( progressMessage, (int)( curFileDone * 100 / curFileLen ), -1, speed );
                    startTime = cur_time;
                    secDone = 0;
                }
            }
            //Log.v( TAG, progressMessage + " " + size );
            if( isStopReq() ) {
                error( ctx.getString( R.string.canceled ) );
                return false;
            }
            Thread.sleep( 1 );
            return true;
        }
    }

  // --- CopyFromEngine ---
    
  public static class CopyFromEngine extends CopyEngine 
  {
        private LsItem[]  mList;
        private boolean   move;
        private Commander commander;
        private CommanderAdapter rcp;

        CopyFromEngine( Commander c, FTPCredentials crd_, Uri uri_, LsItem[] list, boolean move_, boolean active, Charset cs, CommanderAdapter rcp ) {
            super( c.getContext(), crd_, uri_, active, cs );
            commander = c;
            mList = list;
            move = move_;
            this.rcp = rcp;
        }
        @Override
        public void run() {
            try {
                if( crd == null || crd.isNotSet() )
                    crd = new FTPCredentials( uri.getUserInfo() );
                
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }
                if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
                    FSAdapter fsa = new FSAdapter( commander.getContext() );
                    fsa.setUri( Uri.parse( Utils.createTempDir( ctx ).getAbsolutePath() ) );
                    super.recipient = rcp.getReceiver();
                    rcp = fsa;
                } else
                    recipient = null;

                wifiLock.acquire();
                int total = copyFiles( mList, "", rcp.getUri() );
                wifiLock.release();

                if( recipient != null ) {
                    sendReceiveReq( new File( rcp.getUri().getPath() ), move );
                    return;
                }
                sendResult( Utils.getOpReport( ctx, total, R.string.downloaded ) );
            } catch( InterruptedException e ) {
                sendResult( ctx.getString( R.string.canceled ) );
            } catch( Exception e ) {
                error( ctx.getString( R.string.failed ) + e.getLocalizedMessage() );
                Log.e( TAG, "", e );
            }
            super.run();
        }
    
        private final int copyFiles( LsItem[] list, String path, Uri dest_dir_uri ) throws InterruptedException {
            int counter = 0;
            try {
                IReceiver receiver = rcp.getReceiver( dest_dir_uri );

                for( int i = 0; i < list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.interrupted ) );
                        break;
                    }
                    LsItem f = list[i];
                    if( f == null ) continue;
                    String pathName = path + f.getPath();
                    String name = f.getName();
                    int lsp = name.lastIndexOf( '/' );
                    if( lsp >= 0 )
                        name = name.substring( lsp + 1 );
                    if( f.isDirectory() ) {
                        Uri dest_subdir_uri = super.handleDirOnReceiver( commander, receiver, name );
                        if( dest_subdir_uri == null )
                            break;
                        LsItem[] subItems = ftp.getDirList( pathName, true );
                        if( subItems == null ) {
                            error( "Failed to get the file list of the subfolder '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog(), true );
                            break;
                        }
                        counter += copyFiles( subItems, pathName + File.separator, dest_subdir_uri );
                        if( errMsg != null ) break;
                        if( move && !ftp.removeDir( pathName ) ) {
                            error( "Failed to remove folder '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog(), true );
                            break;
                        }
                    }
                    else {
                        Uri dest_item_uri = receiver.getItemURI( name, false );
                        int res = handleItemOnReceiver( commander, receiver, dest_item_uri, name, f.date != null ? f.date.getTime() : -1, f.size );
                        if( res == Commander.ABORT ) break;
                        if( res == Commander.SKIP )  continue;
                        int pnl = pathName.length();
                        progressMessage = ctx.getString( R.string.retrieving,
                                pnl > CUT_LEN ? "\u2026" + pathName.substring( pnl - CUT_LEN ) : pathName );
                        sendProgress( progressMessage, 0 );
                        setCurFileLength( f.length() );

                        OutputStream out = receiver.receive( name );
                        ftp.clearLog();
                        if( !ftp.retrieve( pathName, out, this ) ) {
                            error( "Can't download file '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog() );
                            receiver.closeStream( out );
                            dest_item_uri = receiver.getItemURI( name, false );
                            receiver.delete( dest_item_uri );
                            break;
                        }
                        receiver.closeStream( out );
                        dest_item_uri = receiver.getItemURI( name, false );
                        receiver.setDate( dest_item_uri, f.getDate() );
                        if( move ) {
                            if( !ftp.delete( pathName ) ) {
                                error( "Can't delete file '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog() );
                                break;
                            }
                        }
                        progressMessage = "";
                    }
                    counter++;
                }
            }
            catch( RuntimeException e ) {
                error( "Runtime Exception: " + e.getMessage() );
                Log.e( TAG, "", e );
            }
            catch( Exception e ) {
                error( "Input-Output Exception: " + e.getMessage() );
                Log.e( TAG, "", e );
            }
            return counter;
        }
    }

      // --- CopyToEngine ---
    public static class CopyToEngine extends CopyEngine {
        private   File[]  mList;
        private   int     basePathLen;
        private   boolean move = false;
        private   boolean del_src_dir = false;
        
        CopyToEngine( Context ctx_, FTPCredentials crd_, Uri uri_, File[] list, boolean move_, boolean del_src_dir_, boolean active, Charset cs ) {
            super( ctx_, crd_, uri_, active, cs );
            mList = list;
            basePathLen = list[0].getParent().length();
            if( basePathLen > 1 ) basePathLen++;
            move = move_; 
            del_src_dir = del_src_dir_;
        }

        @Override
        public void run() {
            try {
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }

                wifiLock.acquire();
                int total = copyFiles( mList );
                wifiLock.release();
                sendResult( Utils.getOpReport( ctx, total, R.string.uploaded ) );
                return;
            } catch( Exception e ) {
                error( e.getLocalizedMessage() );
            }
            finally {            
                if( del_src_dir )
                    deleteDir( mList[0].getParentFile() );
                super.run();
            }
            sendResult( "" );
        }
        private final int copyFiles( File[] list ) throws InterruptedException {
            if( list == null ) return 0;
            int counter = 0;
            try {
                for( int i = 0; i < list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.interrupted ) );
                        break;
                    }
                    File f = list[i];
                    if( f != null && f.exists() ) {
                        if( f.isFile() ) {
                            String pathName = f.getAbsolutePath();
                            int pnl = pathName.length();
                            progressMessage = ctx.getString( R.string.uploading, 
                                    pnl > CUT_LEN ? "\u2026" + pathName.substring( pnl - CUT_LEN ) : pathName );
                            sendProgress( progressMessage, 0 );
                            String fn = f.getAbsolutePath().substring( basePathLen );
                            FileInputStream in = new FileInputStream( f );
                            setCurFileLength( f.length() );
                            ftp.clearLog();
                            if( !ftp.store( fn, in, this ) ) {
                                error( ctx.getString( R.string.ftp_upload_failed, f.getName(), ftp.getLog() ) );
                                break;
                            }
                            progressMessage = "";
                        }
                        else
                        if( f.isDirectory() ) {
                            ftp.clearLog();
                            String toCreate = f.getAbsolutePath().substring( basePathLen );
                            if( !ftp.makeDir( toCreate ) ) {
                                Log.w( TAG, ctx.getString( R.string.ftp_mkdir_failed, toCreate, ftp.getLog() ) );
                            }
                            counter += copyFiles( f.listFiles() );
                            if( errMsg != null ) break;
                        }
                        counter++;
                        if( move && !f.delete() ) {
                            error( ctx.getString( R.string.cant_del, f.getCanonicalPath() ) );
                            break;
                        }
                    }
                }
            }
            catch( IOException e ) {
                error( "IOException: " + e.getMessage() );
                Log.e( TAG, "", e );
            }
            return counter;
        }
    }

    // --- DelEngine ---
    
    static class DelEngine extends FTPEngine {
        LsItem[] mList;
        
        DelEngine( Context ctx_, FTPCredentials crd_, Uri uri_, LsItem[] list, boolean active, Charset cs ) {
            super( ctx_, crd_, uri_, active, cs );
            mList = list;
        }
        @Override
        public void run() {
            try {
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }
                int total = delFiles( mList, "" );
                sendResult( Utils.getOpReport( ctx, total, R.string.deleted ) );
                super.run();
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
        private final int delFiles( LsItem[] list, String path ) {
            int counter = 0;
            try {
                for( int i = 0; i < list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.interrupted ) );
                        break;
                    }
                    LsItem f = list[i];
                    if( f != null ) {
                        String pathName = path + f.getName();
                        if( f.isDirectory() ) {
                            LsItem[] subItems = ftp.getDirList( pathName, true );
                            counter += delFiles( subItems, pathName + File.separator );
                            if( errMsg != null ) break;
                            ftp.clearLog();
                            if( !ftp.removeDir( pathName ) ) {
                                error( "Failed to remove folder '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog() );
                                break;
                            }
                        }
                        else {
                            sendProgress( ctx.getString( R.string.deleting, pathName ), i * 100 / list.length );
                            ftp.clearLog();
                            if( !ftp.delete( pathName ) ) {
                                error( "Failed to delete file '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog() );
                                break;
                            }
                        }
                        counter++;
                    }
                }
            }
            catch( Exception e ) {
                Log.e( TAG, "delFiles()", e );
                error( e.getLocalizedMessage() );
            }
            return counter;
        }
    }
    
    // --- RenEngine  ---
    
    static class RenEngine extends FTPEngine {
        private LsItem[] origList;
        private String   newName;
        private String   pattern_str;

        RenEngine( Context ctx_, FTPCredentials crd_, Uri uri_, LsItem[] list, String new_name_or_path, boolean active, Charset cs ) {
            super( ctx_, crd_, uri_, active, cs );
            origList = list;
            newName  = new_name_or_path; 
        }
        RenEngine( Context ctx_, FTPCredentials crd_, Uri uri_, LsItem[] list, String pattern_str, String replace_to, boolean active, Charset cs ) {
            this( ctx_, crd_, uri_, list, replace_to, active, cs );
            this.pattern_str = pattern_str;
        }
        
        @Override
        public void run() {
            try {
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }
                String base_path = Utils.mbAddSl( uri.getPath() );
                
                if( pattern_str == null ) {
                    boolean dest_is_dir = newName.indexOf( '/' ) >= 0;
                    for( int i = 0; i < origList.length; i++ ) {
                        String old_name = origList[i].getName();
                        String old_path = base_path + origList[i].getPath();
                        if( !ftp.rename( old_path, dest_is_dir ? newName + old_name : newName ) )
                            error( ctx.getString( R.string.failed ) + ftp.getLog() );
                    }
                } else {
                    FTPReplacer r = new FTPReplacer( origList, base_path );
                    r.replace( pattern_str, newName );
                }
                sendResult( "" );
                super.run();
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
        
        class FTPReplacer extends Replacer {
            public  String last_file_name = null;
            private LsItem[] origList;
            private String base_path;
            FTPReplacer( LsItem[] origList, String base_path ) {
                this.origList = origList;
                this.base_path = base_path;
            }
            @Override
            protected int getNumberOfOriginalStrings() {
                return origList.length;
            }
            @Override
            protected String getOriginalString( int i ) {
                return origList[i].getName();
            }
            @Override
            protected void setReplacedString( int i, String replaced ) {
                String name = getOriginalString( i );
                String old_path = base_path + name;
                try {
                    if( !ftp.rename( old_path, replaced ) )
                        error( ctx.getString( R.string.failed ) + ftp.getLog() );
                } catch( InterruptedException e ) {
                    Log.e( TAG, "Can't rename " + old_path + " to " + replaced );
                }
            }
        }   
    }

    static class ChmodEngine extends FTPEngine {
        private String chmod;
        
        ChmodEngine( Context ctx_, Uri uri_, String chmod_, Charset cs ) {
            super( ctx_, null, uri_, false, cs );
            chmod = chmod_;
        }
        @Override
        public void run() {
            try {
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }
                if( !ftp.site( chmod ) )
                    error( ctx.getString( R.string.failed ) + ftp.getLog() );
                sendResult( "" );
                super.run();
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
    }
    
    public static class CalcSizesEngine extends FTPEngine {
        private int num = 0, dirs = 0, depth = 0;
        private LsItem[]  list;
    
        CalcSizesEngine( Commander c, FTPCredentials crd_, Uri uri_, LsItem[] list_, boolean active, Charset cs ) {
            super( c.getContext(), crd_, uri_, active, cs );
            list = list_;
        }

        @Override
        public void run() {
            try {
                if( crd == null || crd.isNotSet() )
                    crd = new FTPCredentials( uri.getUserInfo() );
                
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    sendResult( "" );
                    return;
                }
                sendProgress();
                long total = getSizes( list, "" );
                
                StringBuffer result = new StringBuffer();
                if( list.length == 1 ) {
                    LsItem f = list[0];
                    if( f.isDirectory() ) {
                        result.append( ctx.getString( Utils.RR.sz_folder.r(), f.getName(), num ) );
                        if( dirs > 0 )
                            result.append( ctx.getString( Utils.RR.sz_dirnum.r(), dirs, ( dirs > 1 ? ctx.getString( Utils.RR.sz_dirsfx_p.r() ) : ctx.getString( Utils.RR.sz_dirsfx_s.r() ) ) ) );
                    }
                    else 
                        result.append( ctx.getString( Utils.RR.sz_file.r(), f.getName() ) );
                } else {
                    result.append( ctx.getString( Utils.RR.sz_files.r(), num ) );
                    if( dirs > 0 )
                        result.append( ctx.getString( Utils.RR.sz_dirnum.r(), dirs, ( dirs > 1 ? ctx.getString( Utils.RR.sz_dirsfx_p.r() ) : ctx.getString( Utils.RR.sz_dirsfx_s.r() ) ) ) );
                }
                if( total > 0 )
                    result.append( ctx.getString( Utils.RR.sz_Nbytes.r(), Formatter.formatFileSize( ctx, total ).trim() ) );
                if( total > 1024 )
                    result.append( ctx.getString( Utils.RR.sz_bytes.r(), total ) );
                if( list.length == 1 ) {
                    LsItem f = list[0];
                    result.append( ctx.getString( Utils.RR.sz_lastmod.r() ) );
                    result.append( " " );
                    result.append( "<small>" );
                    result.append( Utils.formatDate( f.getDate(), ctx ) );
                    result.append( "</small>" );
                }
                sendReport( result.toString() );            
                super.run();
            } catch( InterruptedException e ) {
                sendResult( ctx.getString( R.string.canceled ) );
            } catch( Exception e ) {
                error( ctx.getString( R.string.failed ) + e.getLocalizedMessage() );
                e.printStackTrace();
            }
            super.run();
        }

        protected final long getSizes( LsItem[] list, String path ) throws Exception {
            long total = 0;
            try {
                for( int i = 0; i < list.length; i++ ) {
                    if( stop || isInterrupted() ) {
                        error( ctx.getString( R.string.interrupted ) );
                        break;
                    }
                    LsItem f = list[i];
                    if( f != null ) {
                        String pathName = path + f.getName();
                        if( f.isDirectory() ) {
                            dirs++;
                            if( depth++ > 30 )
                                throw new Exception( ctx.getString( R.string.too_deep_hierarchy ) );
                            LsItem[] subItems = ftp.getDirList( pathName, true );
                            --depth;
                            if( subItems == null ) {
                                error( "Failed to get the file list of the subfolder '" + pathName + "'.\n FTP log:\n\n" + ftp.getLog() );
                                break;
                            }
                            total += getSizes( subItems, pathName + File.separator );
                            if( errMsg != null ) break;
                        }
                        else {
                            total += f.length();
                            num++;
                        }
                    }
                }
            }
            catch( RuntimeException e ) {
                error( "Runtime Exception: " + e.getMessage() );
                Log.e( TAG, "", e );
            }
            return total;
        }
    }

    public static class CreateFileEngine extends FTPEngine {
        String newFileName;
        
        CreateFileEngine( String name, Context ctx_, FTPCredentials crd_, Uri uri_, FTP ftp_ ) {
            super( ctx_, crd_, uri_, ftp_ );
            newFileName = name;
        }

        @Override
        public void run() {
            try {
                if( ftp.connectAndLogin( uri, crd.getUserName(), crd.getPassword(), true ) < 0 ) {
                    error( ctx.getString( R.string.ftp_nologin ) );
                    return;
                }
                byte[] bb = new byte[0];
                ByteArrayInputStream in = new ByteArrayInputStream( bb );
                ftp.store( newFileName, in, null );
            } catch( Exception e ) {
                error( e.getLocalizedMessage() );
            }
            if( !noErrors() )
                sendResult( "" );
            else
                sendRefrReq( newFileName );
        }
    }

    public static class MkDirEngine extends FTPEngine {
        private String name;

        MkDirEngine( String name_, Context ctx_, FTP ftp_, FTPCredentials crd_ ) {
            super( ctx_, crd_, null, ftp_ );
            name = name_;
        }
        @Override
        public void run() {
            ftp.clearLog();
            try {
                if( ftp.makeDir( name ) ) {
                    sendRefrReq( name );
                    return;
                }
            } catch( Exception e ) {
            }
            error( ctx.getString( R.string.ftp_mkdir_failed, name, ftp.getLog() ) );
            sendResult( "" );
        }
    }

    public static class Receiver implements IReceiver {
        private final static String TAG = "FTPReceiver";
        private   Uri            mDest;
        protected Context        ctx;
        protected FTPCredentials crd;
        protected FTP            ftp;

        Receiver( FTP ftp, Uri dir ) {
            this.ftp = ftp;
            mDest = dir;
            if( dir == null )
                Log.e( TAG, "dest dir URI is null!" );
        }

        private final Uri buildItemUri( String name ) {
            return mDest.buildUpon().appendPath( name ).build();
        }

        @Override
        public OutputStream receive( String fn ) {
            try {
                Uri u = buildItemUri( fn );
                return ftp.prepStore( u.getPath() );
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
                ftp.doneWithData( s.hashCode() );
            } catch( IOException e ) {
                Log.e( TAG, "", e );
            }
        }

        @Override
        public Uri getItemURI( String name, boolean dir ) {
            Uri u = buildItemUri( name );
            long sz = ftp.getSize( u.getPath() );
            if( sz < 0 )
                return dir && isDirectory( u ) ? u : null;
            return u;
        }

        @Override
        public boolean isDirectory( Uri item_uri ) {
            String wd = null;
            try {
                wd = ftp.getCurrentDir();
                if( !Utils.str( wd ) )
                    return false;
                return ftp.setCurrentDir( item_uri.getPath() );
            } catch( Exception e ) {
                Log.e( TAG, item_uri.toString(), e );
            } finally {
                try {
                    if( Utils.str( wd ) )
                        ftp.setCurrentDir( wd );
                } catch( Exception e ) {
                    Log.e( TAG, wd, e );
                }
            }
            return false;
        }

        @Override
        public Uri makeDirectory( String new_dir_name ) {
            try {
                Uri u = buildItemUri( new_dir_name );
                return ftp.makeDir( u.getPath() ) ? u : null;
            } catch( Exception e ) {
                Log.e( TAG, mDest + " / " + new_dir_name, e );
            }
            return null;
        }

        @Override
        public boolean delete( Uri item_uri ) {
            try {
                return ftp.delete( item_uri.getPath() );
            } catch( Exception e ) {
                Log.e( TAG, item_uri.toString(), e );
            }
            return false;
        }

        @Override
        public boolean setDate( Uri item_uri, Date timestamp ) {
            return false;
        }

        @Override
        public boolean done() {
            return true;
        }
    }


}
