package com.ghostsq.commander.adapters;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.FilterProps;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_DIRSZ;
import static com.ghostsq.commander.adapters.CommanderAdapter.SHOW_DIRSZ;
import static com.ghostsq.commander.adapters.SAFAdapter.getDocTreeIntent;

@SuppressLint("NewApi")
public final class SAFEngines {

    public static class ListEngine extends Engine {
        private SAFAdapter  owner;
        private ContentResolver cr;
        private String      pass_back_on_done;
        private Uri uri;
        private SearchProps sq;
        private SAFAdapter.SAFItem[] items;
        private int         depth = 0;
        private String      path = null;

        ListEngine( SAFAdapter owner, Uri uri, SearchProps sq, Handler h, String pass_back_on_done_ ) {
            setName( ".ListEngine" );
            setHandler( h );
            this.owner = owner;
            this.uri = uri;
            this.sq = sq;
            pass_back_on_done = pass_back_on_done_;
        }

        public final SAFAdapter.SAFItem[] getItems() {
            return items;
        }

        @Override
        public void run() {
            try {
                cr = owner.ctx.getContentResolver();
                ArrayList<SAFAdapter.SAFItem> items_list = new ArrayList<>();
                if( sq != null ) {
                    try {
                        path = owner.getPath( uri, true );
                        searchInFolder( uri, items_list );
                    } catch( Exception e ) {
                        Log.e( TAG, owner.getUri().toString() + " " + sq.getString( owner.ctx ), e );
                    }
                } else {
                    if( SAFAdapter.ENT_URI.equals( uri ) ) {
                        List<UriPermission> uriPermissions = owner.ctx.getContentResolver().getPersistedUriPermissions();
                        if( uriPermissions.size() == 0 ) {
                            owner.commander.issue( getDocTreeIntent(), Commander.REQUEST_OPEN_DOCUMENT_TREE );
                            return;
                        }
                        for( UriPermission p : uriPermissions ) {
                            Uri u = p.getUri();
                            Uri doc_uri = DocumentsContract.buildDocumentUriUsingTree( u, DocumentsContract.getTreeDocumentId( u ) );
                            String path = owner.getPath( doc_uri, true );
                            SAFAdapter.SAFItem item = new SAFAdapter.SAFItem();
                            item.uri = u;
                            item.dir = true;
                            item.name = path == null ? u.toString() : path;
                            item.icon_id = R.drawable.saf;
                            items_list.add( item );
                        }
                    } else {
                        items_list = owner.getChildren( uri, true );
                        if( items_list == null ) {
                            Log.e( TAG, "Can't get list of items of " + uri );
                            sendProgress( null, Commander.OPERATION_FAILED );
                            return;
                        }
                        if( ( owner.getMode() & MODE_DIRSZ ) == SHOW_DIRSZ ) {
                            for( SAFAdapter.SAFItem item : items_list ) {
                                if( item.dir && item.origin instanceof String ) {
                                    try {
                                        item.size = getSizes( uri, (String)item.origin );
                                    } catch( Exception e ) {
                                        Log.e( TAG, uri.toString(), e );
                                    }
                                }
                            }
                        }
                    }
                }
                items = new SAFAdapter.SAFItem[items_list.size()];
                items_list.toArray( items );
                sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                return;
            }
            catch( Exception e ) {
                Log.e( TAG, uri.toString(), e );
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
            }
        }

        final String[] projection = {
            Document.COLUMN_DOCUMENT_ID,   // 0
            Document.COLUMN_MIME_TYPE,     // 1
            Document.COLUMN_SIZE           // 2
        };

        protected final long getSizes( Uri tree_uri, String dir_doc_id ) throws Exception {
            if( tree_uri == null || dir_doc_id == null )
                return 0;
            long count = 0;
            Uri   children_uri = DocumentsContract.buildChildDocumentsUriUsingTree( tree_uri, dir_doc_id );
            Cursor c = cr.query( children_uri, projection, null, null, null);
            if( c == null || c.getCount() <= 0 )
                return 0;
            c.moveToFirst();
            do {
                if( Document.MIME_TYPE_DIR.equals( c.getString( 1 ) ) ) {
                    if( depth++ > 30 )
                        throw new Exception( owner.s( R.string.too_deep_hierarchy ) );
                    count += getSizes( tree_uri, c.getString( 0 ) );
                    depth--;
                }
                else {
                    count += c.getLong( 2 );
                }
            } while( c.moveToNext() );
            c.close();
            return count;
        }

        private final int searchInFolder( Uri uri, ArrayList<SAFAdapter.SAFItem> result ) throws Exception {
            long cur_time = System.currentTimeMillis();
            if( cur_time - progress_last_sent > 500 ) {
                progress_last_sent = cur_time;
                sendProgress( uri.getPath(), progress = 0 );
            }

            String in_path = owner.getPath( uri, true );
            String subpath = path == null || path.equals( in_path ) ? "" : Utils.mbAddSl( in_path.substring( path.length() + 1 ) );

            ArrayList<SAFAdapter.SAFItem> children = owner.getChildren( uri, false );
            if( children == null ) return 0;
            FilterProps filter = owner.getFilter();
            final double conv = 100. / children.size();
            int i = 0;
            for( SAFAdapter.SAFItem item : children ) {
                i++;
                if( isStopReq() )
                    throw new Exception( owner.ctx.getString( R.string.interrupted ) );
                int np = (int)(i * conv);
                if( np - conv + 1 > progress ) {
                    cur_time = System.currentTimeMillis();
                    if( cur_time - progress_last_sent > 1000 ) {
                        progress_last_sent = cur_time;
                        sendProgress( uri.getPath(), progress = np );
                    }
                }
                if( isMatched( item ) && ( filter == null || filter.isMatched( item ) ) ) {
                    item.setPrefix( subpath );
                    item.attr = "./" + subpath;
                    result.add( item );
                }
                if( !sq.olo && item.dir  ) {
                    if( depth++ > 30 )
                        throw new Exception( owner.ctx.getString( R.string.too_deep_hierarchy ) );
                    searchInFolder( item.getUri(), result );
                    depth--;
                }
                if( isStopReq() ) return 0;
            }
            return i;
        }

        private final boolean isMatched( SAFAdapter.SAFItem item ) {
            if( !super.isMatched( item, sq ) )
                return false;
            if( sq.content == null || item.dir )
                return true;
            InputStream is = null;
            try {
                Uri item_uri = item.getUri();
                is = owner.getContent( item_uri, 0 );
                if( searchInContent( is, item.size, item.name,  sq.content ) )
                    return true;
            } catch( Exception e ) {
                Log.e( TAG, item.name, e );
            } finally {
                owner.closeStream( is );
            }
            return false;
        }
    }

    public static int getFlags( ContentResolver cr, Uri uri ) throws Exception {
        Cursor c = cr.query( uri, null, null, null, null );
        if( c == null )
            return 0;
        c.moveToFirst();
        int ci = c.getColumnIndex( DocumentsContract.Document.COLUMN_FLAGS );
        if( ci < 0 ) return 0;
        return c.getInt( ci );
    }

    public static class CopyFromEngine extends Engine {
        private SAFAdapter.SAFItem[] mList;
        private SAFAdapter owner;
        private ContentResolver cr;
        private CommanderAdapter rcp;
        private boolean move, dest_is_SAF;

        CopyFromEngine( SAFAdapter owner, SAFAdapter.SAFItem[] list, boolean move, CommanderAdapter rcp ) {
            setName( ".CopyFromEngine" );
            this.owner = owner;
            mList = list;
            this.move = move;
            this.rcp = rcp;
            dest_is_SAF = DocumentsContract.isDocumentUri( owner.ctx, rcp.getUri() );
        }
        @Override
        public void run() {
            try {
                //Init( null );
                cr = owner.ctx.getContentResolver();
                rcp = super.substituteReceiver( owner.ctx, rcp ); // sets the recipient data member
                int cnt = copyFiles( mList, rcp.getUri() );
                if( recipient != null ) {
                      sendReceiveReq( new File( rcp.getUri().getPath() ), move );
                      return;
                }
                sendResult( Utils.getOpReport( owner.ctx, cnt, move ? R.string.moved : R.string.copied ) );
            }
            catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
            }
        }

        private final int copyFiles( CommanderAdapter.Item[] l, Uri dest_dir_uri ) throws Exception {
            if( l == null ) return 0;
            IReceiver receiver = rcp.getReceiver( dest_dir_uri );
            int cnt = 0;
            int num = l.length;
            int total_progress = 0;
            long total_copied = 0, total_size = 0;
            for( int i = 0; i < num; i++ ) {
                total_size += l[i].size;
            }
            for( int i = 0; i < num; i++ ) {
                sleep( 1 );
                if( isStopReq() )
                    throw new Exception( owner.s( R.string.canceled ) );
                CommanderAdapter.Item item = l[i];
                String name = item.name;
                sendProgress( owner.ctx.getString( R.string.copying, name ) + "\n", 0 );
                if( name.charAt( 0 ) == '/' )
                    name = name.substring( 1 );
                Uri u = item.uri;
                boolean ok = false;

                if( item.dir ) {
                    Uri dest_subdir_uri = handleDirOnReceiver( owner.commander, receiver, name );
                    if( dest_subdir_uri == null )
                        break;
                    ArrayList<SAFAdapter.SAFItem> tmp_list = owner.getChildren( u, false );
                    SAFAdapter.SAFItem[] sub_items = new SAFAdapter.SAFItem[tmp_list.size()];
                    tmp_list.toArray( sub_items );
                    cnt += copyFiles( sub_items, dest_subdir_uri );
                    if( errMsg != null ) break;
                    ok = true;
                } else {
                    Uri dest_item_uri = receiver.getItemURI( name, false );    // null if does not exist
                    int res = super.handleItemOnReceiver( owner.commander, receiver, dest_item_uri, name, item.date != null ? item.date.getTime() : -1, item.size );
                    if( res == Commander.ABORT ) break;
                    if( res == Commander.SKIP ) continue;
                    if( dest_is_SAF && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            Objects.equals( dest_dir_uri.getAuthority(), u.getAuthority() ) ) {
                        try {
                            int flags = SAFEngines.getFlags( cr, u );
                            Uri new_uri = null;
                            if( move ) {
                                if( ( flags & Document.FLAG_SUPPORTS_MOVE ) != 0 )
                                    new_uri = DocumentsContract.moveDocument( cr, u, SAFAdapter.getParent( u ), dest_dir_uri );
                            } else {
                                if( ( flags & Document.FLAG_SUPPORTS_COPY ) != 0 )
                                    new_uri = DocumentsContract.copyDocument( cr, u, dest_dir_uri );
                            }
                            if( new_uri != null) {
                                cnt++;
                                continue;
                            }
                        } catch( Exception e ) {
                            Log.w( TAG, item.name + " " + u + " " + e.getMessage() );
                        }
                    }
                    super.progress = total_progress;
                    ok = super.copyStreamToReceiver( owner.ctx, receiver, cr.openInputStream( u ), name, item.size, new Date( item.date.getTime() ) );
                    if( dest_is_SAF ) { // the returned ok is not reliable :(
                        SAFAdapter.Receiver sr = (SAFAdapter.Receiver)receiver;
                        Uri last_received = sr.getLastReceivedUri();
                        ok = last_received != null && SAFAdapter.getMime( owner.ctx, last_received ) != null;
                    }
                    if( ok ) {
                        total_copied += item.size;
                        total_progress = (int)(total_copied * 100 / total_size);
                    }
                }
                if( ok ) {
                    if( move )
                        DocumentsContract.deleteDocument( cr, u );
                    cnt++;
                }
            }
            return cnt;
        }
    }

    public static class CopyToEngine extends Engine {
        private SAFAdapter owner;
        private Uri     mDest;
        private ContentResolver cr;
        private int     counter = 0, delerr_counter = 0, depth = 0;
        private long    totalBytes = 0;
        private double  conv;
        private File[]  fList = null;
        private boolean move, del_src_dir, report_copy;
        private byte[]  buf;
        private static final int BUFSZ = 524288;
        private PowerManager.WakeLock wakeLock;

        CopyToEngine( SAFAdapter owner, File[] list, int move_mode ) {
            this.owner = owner;
            setName( ".CopyToEngine" );
            fList = list;
            mDest = owner.getUri();
            cr = owner.ctx.getContentResolver();
            move = ( move_mode & owner.MODE_MOVE ) != 0;
            del_src_dir = ( move_mode & owner.MODE_DEL_SRC_DIR ) != 0;
            report_copy = ( move_mode & owner.MODE_REPORT_AS_MOVE ) == 0;
            buf = new byte[BUFSZ];
            PowerManager pm = (PowerManager)owner.ctx.getSystemService( Context.POWER_SERVICE );
            wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, TAG );
        }
        @Override
        public void run() {
            sendProgress( owner.ctx.getString( R.string.preparing ), 0, 0 );
            try {
                int l = fList.length;
                wakeLock.acquire();
                int num = copyFiles( fList, mDest );
                wakeLock.release();
                // XXX: assume (move && !del_src_dir)==true when copy from app: to the FS
                if( delerr_counter == counter ) move = false;  // report as copy
                String report = Utils.getOpReport( owner.ctx, num, move && !report_copy ? R.string.moved : R.string.copied );
                sendResult( report );
            } catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
                return;
            } finally {
                if( del_src_dir )
                    deleteDir( fList[0].getParentFile() );

            }
        }

        private final int copyFiles( File[] list, Uri dest ) throws InterruptedException {
            File file = null;
            for( int i = 0; i < list.length; i++ ) {
                InputStream is = null;
                OutputStream os = null;
                file = list[i];
                if( file == null ) {
                    error( owner.ctx.getString( R.string.unkn_err ) );
                    break;
                }
                Uri dest_uri = null;
                try {
                    if( isStopReq() ) {
                        error( owner.ctx.getString( R.string.canceled ) );
                        break;
                    }
                    String fn = file.getName();
                    String escaped_name = Utils.escapePath( fn );
                    dest_uri = dest.buildUpon().encodedPath( dest.getEncodedPath() + "%2f" + escaped_name ).build();
                    if( file.isDirectory() ) {
                        if( depth++ > 40 ) {
                            error( owner.ctx.getString( R.string.too_deep_hierarchy ) );
                            break;
                        }
                        String mime = SAFAdapter.getMime( owner.ctx, dest_uri );
                        if( mime != null ) {
                          if( !DocumentsContract.Document.MIME_TYPE_DIR.equals( mime ) ) {
                            error( owner.ctx.getString( R.string.cant_md ) );
                            break;
                          }
                        } else {
                            owner.createDocument( dest, DocumentsContract.Document.MIME_TYPE_DIR, escaped_name );
                        }
                        copyFiles( file.listFiles(), dest_uri );
                        if( errMsg != null )
                            break;
                        depth--;
                        counter++;
                    }
                    else {
                        CommanderAdapter.Item dest_item = owner.getItem( dest_uri );
                        if( dest_item != null ) {
                            long size_of_existed = dest_item.size;
                            if( size_of_existed > 0 ) {
                                long dest_time = dest_item.date != null ? dest_item.date.getTime() : -1;
                                int res = askOnFileExist( owner.commander, owner.ctx.getString( R.string.file_exist, "<small>" + fn.replace( "&", "&amp;" ) + "</small>" ),
                                        file.lastModified(), dest_time, file.length(), size_of_existed, NEW_OPTIONS );
                                if( res == Commander.SKIP ) continue;
                                if( res == Commander.ABORT ) break;
                                if( res == Commander.REPLACE_OLD ) {
                                    if( file.lastModified() > dest_time )
                                        res = Commander.REPLACE;
                                    else
                                        continue;
                                }
                                if( res == Commander.REPLACE ) {
                                    File dest_file = new File( owner.getPath( dest_uri, false ) );
                                    if( dest_file.equals( file ) ) {
                                        Log.w( TAG, "Not going to copy file to itself" );
                                        continue;
                                    }
                                    Log.v( TAG, "Overwritting file " + fn );
                                    DocumentsContract.deleteDocument( cr, dest_uri );
                                }
                            }
                        }
                        String ext = Utils.getFileExt( fn );
                        String mime = Utils.getMimeByExt( ext );
                        // sometimes SAF adds additional extension to the file name based on the mime!
                        if( !".txt".equals( ext ) && "text/plain".equals( mime ) )
                            mime = mime + ext;
                        dest_uri = owner.createDocument( dest, mime, escaped_name );
                        if( dest_uri == null ) {
                            error( owner.ctx.getString( R.string.cant_create, fn, "" ) );
                            break;
                        }
                        is = new FileInputStream( file );
                        os = cr.openOutputStream( dest_uri );
                        long copied = 0, size  = file.length();

                        long start_time = 0;
                        int  speed = 0;
                        int  so_far = (int)(totalBytes * conv);

                        String sz_s = Utils.getHumanSize( size );
                        int fnl = fn.length();
                        String rep_s = owner.ctx.getString( R.string.copying,
                               fnl > CUT_LEN ? "\u2026" + fn.substring( fnl - CUT_LEN ) : fn );
                        int  n  = 0;
                        long nn = 0;

                        while( true ) {
                            if( nn == 0 ) {
                                start_time = System.currentTimeMillis();
                                sendProgress( rep_s + sizeOfsize( copied, sz_s ), so_far, (int)(totalBytes * conv), speed );
                            }
                            n = is.read( buf );
                            if( n < 0 ) {
                                long time_delta = System.currentTimeMillis() - start_time;
                                if( time_delta > 0 ) {
                                    speed = (int)(MILLI * nn / time_delta );
                                    sendProgress( rep_s + sizeOfsize( copied, sz_s ), so_far, (int)(totalBytes * conv), speed );
                                }
                                break;
                            }
                            os.write( buf, 0, n );
                            nn += n;
                            copied += n;
                            totalBytes += n;
                            if( isStopReq() ) {
                                Log.d( TAG, "Interrupted!" );
                                error( owner.ctx.getString( R.string.canceled ) );
                                DocumentsContract.deleteDocument( cr, dest_uri );
                                return counter;
                            }
                            long time_delta = System.currentTimeMillis() - start_time;
                            if( time_delta > DELAY ) {
                                speed = (int)(MILLI * nn / time_delta);
                                //Log.v( TAG, "bytes: " + nn + " time: " + time_delta + " speed: " + speed );
                                nn = 0;
                            }
                        }
                        is.close();
                        os.close();
                        is = null;
                        os = null;
                        try {
/* SAF fields are read-only!
                            ContentValues cv = new ContentValues();
                            cv.put( Document.COLUMN_LAST_MODIFIED, file.lastModified() );
                            cr.update( dest_uri, cv, null, null ); //throws..
 */
                            String dest_path = owner.getPath( dest_uri, false );
                            if( dest_path != null )
                                new File( dest_path ).setLastModified( file.lastModified() );

                        } catch( Exception e ) {
                             if( BuildConfig.DEBUG )
                                 Log.e( TAG, dest_uri.toString(), e );
                        }

                        if( i >= list.length-1 )
                            sendProgress( owner.ctx.getString( R.string.copied_f, fn ) + sizeOfsize( copied, sz_s ), (int)(totalBytes * conv) );
                        counter++;
                    }
                    if( move ) {
                        if( !file.delete() ) {
                            sendProgress( owner.ctx.getString( R.string.cant_del, fn ), -1 );
                            delerr_counter++;
                        }
                    }
                }
                catch( Exception e ) {
                    Log.e( TAG, "", e );
                    error( owner.ctx.getString( R.string.rtexcept, file.getAbsolutePath(), e.getMessage() ) );
                }
                finally {
                    try {
                        if( is != null )
                            is.close();
                        if( os != null )
                            os.close();
                    }
                    catch( IOException e ) {
                        error( owner.ctx.getString( R.string.acc_err, file.getAbsolutePath(), e.getMessage() ) );
                    }
                }
            }
            return counter;
        }
    }

    public static class DeleteEngine extends Engine {
        private SAFAdapter owner;
        private CommanderAdapter.Item[] mList;
        private Uri dirUri;
        private ContentResolver cr;

        DeleteEngine( SAFAdapter owner, CommanderAdapter.Item[] list ) {
            setName( ".DeleteEngine" );
            this.owner = owner;
            mList = list;
            dirUri = owner.getUri();
        }
        @Override
        public void run() {
            try {
                cr = owner.ctx.getContentResolver();
                int cnt = deleteFiles( dirUri, mList );
                sendResult( Utils.getOpReport( owner.ctx, cnt, R.string.deleted ) );
            }
            catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
            }
        }

        private final int deleteFiles( Uri dir_uri, CommanderAdapter.Item[] l ) throws Exception {
            if( l == null ) return 0;
            int cnt = 0;
            int num = l.length;
            double conv = 100./num;
            for( int i = 0; i < num; i++ ) {
                sleep( 1 );
                if( isStopReq() )
                    throw new Exception( owner.s( R.string.canceled ) );
                CommanderAdapter.Item item = l[i];
                sendProgress( owner.ctx.getString( R.string.deleting, item.name ), (int)(cnt * conv) );
                DocumentsContract.deleteDocument( cr, item.uri );
                cnt++;
            }
            return cnt;
        }
    }
}
