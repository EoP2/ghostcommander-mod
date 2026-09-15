package com.ghostsq.commander.adapters;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.widget.AdapterView;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Replacer;
import com.ghostsq.commander.utils.Utils;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class SAFAdapter extends CommanderAdapterBase implements Engines.IReciever {
    private final static String TAG = "SAFAdapter";
    public final static String ORG_SCHEME = "saf", EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    public final static Uri ENT_URI = Uri.parse( "saf:" );
    private static boolean isPickingGeneric = false;
    private boolean primary = false;
    private String currentPath = null;
    private int failCounter = 0;

    static class SAFItem extends CommanderAdapter.Item implements FSEngines.IFileItem {
        public Context ctx;

        @Override
        public File f() {
            if( ctx == null )
                return null;
            String path = SAFAdapter.getPath( ctx, uri, this.dir );
            return new File( path );
        }
    }

    private Uri uri;
    protected SAFItem[] items;

    ThumbnailsThread tht = null;

    public SAFAdapter( Context ctx_ ) {
        super( ctx_ );
    }

    @Override
    public String getScheme() {
        return ContentResolver.SCHEME_CONTENT;
    }

    public boolean showingPUPs() {
        return ENT_URI.equals( uri );
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        if( showingPUPs() ) {
            switch( feature ) {
            case  LOCAL:
            case  SORTING:
            case  REFRESH:
            case  SEL_UNS:
            case CHECKABLE:
            case  F7:
            case  F8:
                return true;
            case  REAL:
            case  BY_EXT:
            case  BY_DATE:
            case  BY_SIZE:
            case  FAVS:
                return false;
            default:
                return super.hasFeature( feature );
            }
        }
        switch( feature ) {
            case LOCAL:
            case REAL:
            case SF4:
            case SEND:
            case MULT_RENAME:
            case FILTER:
            case RECEIVER:
            case SEARCH:
            case DIRSIZES:
                return true;
            default:
                return super.hasFeature( feature );
        }
    }

    @Override
    public String toString() {
        if( showingPUPs() )
            return ctx.getString( R.string.saf );
        if( currentPath == null )
            currentPath = getPath( ctx, uri, true );
        return currentPath != null ? getUserFriendlyURI( currentPath ) : null;
    }

    public static String getUserFriendlyURI( Context ctx, Uri content_uri ) {
        String real_path = getPath( ctx, content_uri, true );
        return real_path != null ? getUserFriendlyURI( real_path ) : null;
    }

    public static String getUserFriendlyURI( String real_path ) {
        return ORG_SCHEME + ":" + real_path;
    }

    private static final String PATH_TREE = "tree";

    public static Intent getDocTreeIntent() {
        return getDocTreeIntent( null );
    }

    public static Intent getDocTreeIntent( Uri initial ) {
        Intent in = new Intent( Intent.ACTION_OPEN_DOCUMENT_TREE );
        in.setFlags( Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION );
        if( initial != null && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
            in.putExtra( DocumentsContract.EXTRA_INITIAL_URI, initial );
        in.putExtra( "android.provider.extra.SHOW_ADVANCED", true );
        in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION
                   | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                   | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                   | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return in;
    }

    public static boolean isTreeUri( Uri uri ) {
        if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N )
            return DocumentsContract.isTreeUri( uri );
        final List<String> paths = uri.getPathSegments();
        return paths.size() >= 2 && PATH_TREE.equals( paths.get( 0 ) );
    }

    private static boolean isRootDoc( Uri uri ) {
        final List<String> paths = uri.getPathSegments();
        if( paths.size() < 4 ) return true;
        String last = paths.get( paths.size() - 1 );
        int cp = last.lastIndexOf( ':' );
        if( cp >= 0 )
            return cp == last.length() - 1;
        return last.equals( paths.get( 1 ) );
    }

    private static boolean isPrimary( Uri u ) {
        final List<String> paths = u.getPathSegments();
        if( paths.size() < 4 ) return false;
        String volume = paths.get( 1 );
        return volume != null && volume.startsWith( "primary" );
    }

    public static String getDocId( Context ctx, Uri u ) {
        return getProperty( ctx, u, Document.COLUMN_DOCUMENT_ID );
    }

    public static String getMime( Context ctx, Uri u ) {
        return getProperty( ctx, u, Document.COLUMN_MIME_TYPE );
    }

    public static String getProperty( Context ctx, Uri u, String column ) {
        Cursor c = null;
        try {
            final String[] projection = {column};
            c = ctx.getContentResolver().query( u, projection, null, null, null );
            if( c.getCount() > 0 ) {
                c.moveToFirst();
                return c.getString( 0 );
            }
        } catch( Exception e ) {
        } finally {
            if( c != null ) c.close();
        }
        return null;
    }

    public static long getSize( Context ctx, Uri u ) {
        Cursor c = null;
        try {
            final String[] projection = {Document.COLUMN_SIZE};
            c = ctx.getContentResolver().query( u, projection, null, null, null );
            if( c.getCount() > 0 ) {
                c.moveToFirst();
                return c.getLong( 0 );
            }
        } catch( Exception e ) {
        } finally {
            if( c != null ) c.close();
        }
        return -1;
    }

    public static SAFItem getItem( ContentResolver cr, Uri u ) {
        Cursor c = null;
        try {
            final String[] projection = colIds();
            c = cr.query( u, projection, null, null, null );
            if( c.getCount() == 0 ) {
                Log.e( TAG, "Can't query uri " + u );
                return null;
            }
            c.moveToFirst();
            int[] ii = colInds( c );
            SAFItem item = rowToItem( c, u, ii );
            return item;
        } catch( Exception e ) {
            Log.e( TAG, u != null ? u.toString() : "null", e );
        } finally {
            if( c != null ) c.close();
        }
        return null;
    }

    @Override
    public String getDescription( int flags ) {
        if( uri == null ) return "";
        String vi = getVolumeInfo( false );
        return vi != null ? vi : "";
    }

    public static String getPathRelativeToRoot( Uri uri ) {
        if( uri == null ) return null;
        String lps = uri.getLastPathSegment();
        if( lps == null ) return null;
        int cp = lps.indexOf( ':' );
        if( cp < 0 ) return null;
        return lps.substring( cp + 1 );
    }

    public final String getPath( Uri u, boolean dir ) {
        return getPath( ctx, u, dir );
    }

    public static String getPath( Context ctx, Uri u, boolean dir ) {
        try {
            String fd_path = null;
            ContentResolver cr = ctx.getContentResolver();
            ParcelFileDescriptor pfd = null;
            try {
                pfd = cr.openFileDescriptor( u, "r" );
            } catch( Exception e ) {
                if( BuildConfig.DEBUG )
                    Log.e( TAG, "Can't get the file descriptor of " + u );
            }
            if( pfd != null ) {
                fd_path = getFdPath( pfd );
                pfd.close();
                if( BuildConfig.DEBUG ) Log.d( TAG, "Got path: " + fd_path );
                if( Utils.str( fd_path ) && !fd_path.contains( "media_rw" ) )
                    return fd_path;
            }

            final List<String> paths = u.getPathSegments();
            if( paths.size() < 4 ) return null;
            String path_part = paths.get( 3 );
            int col_pos = path_part.lastIndexOf( ':' );
            if( col_pos < 0 ) {
                String doc_id = getDocId( ctx, u );
                return path_part;   // what better can we get ?
            }
            String volume, path_root = null, sub_path, full_path;
            volume = path_part.substring( 0, col_pos );
            sub_path = path_part.substring( col_pos + 1 );
//            volume = paths.get( 1 );
            if( BuildConfig.DEBUG )
                Log.d( TAG, "volume: " + volume + ", path: " + sub_path );

            if( volume.startsWith( "primary" ) )
                return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + sub_path;
            else {
                try {
                    File probe;
                    //                  volume = volume.substring( 0, volume.length()-1 );
                    if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                        String base_path = "/storage/" + volume;
                        probe = new File( base_path );
                        if( probe.exists() )
                            return base_path + "/" + sub_path;
                        base_path = "/mnt/media_rw/" + volume;
                        probe = new File( base_path );
                        if( probe.exists() )
                            return base_path + "/" + sub_path;
                    } else {
                        path_root = Utils.getSecondaryStorage();
                        if( path_root != null ) {
                            full_path = Utils.mbAddSl( path_root ) + sub_path;
                            probe = new File( full_path );
                            if( probe.exists() && dir ? probe.isDirectory() : probe.isFile() )
                                return full_path;
                        }
                    }
                } catch( Exception e ) {
                    Log.w( TAG, "Can't resolve uri to a path: " + u );
                }
                if( Utils.str( fd_path ) )
                    return fd_path;

                if( path_root == null )
                    path_root = volume; // better than nothing
            }
            return path_root + "/" + sub_path;
        } catch( Exception e ) {
            Log.e( TAG, "Can't get the real location of " + u, e );
        }
        return null;
    }

    // see https://stackoverflow.com/questions/30546441/android-open-file-with-intent-chooser-from-uri-obtained-by-storage-access-frame/31283751#31283751
    private static String getFdPath( ParcelFileDescriptor fd ) {
        final String resolved;

        try {
            final File procfsFdFile = new File( "/proc/self/fd/" + fd.getFd() );

            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
                // Returned name may be empty or "pipe:", "socket:", "(deleted)"
                // etc.
                resolved = Os.readlink( procfsFdFile.getAbsolutePath() );
            } else {
                // Returned name is usually valid or empty, but may start from
                // funny prefix if the file does not have a name
                resolved = procfsFdFile.getCanonicalPath();
            }

            if( !Utils.str( resolved ) || resolved.charAt( 0 ) != '/' ||
                    resolved.startsWith( "/proc/" ) || resolved.startsWith( "/fd/" ) )
                return null;
        } catch( IOException ioe ) {
            // This exception means, that given file DID have some name, but it is
            // too long, some of symlinks in the path were broken or, most
            // likely, one of it's directories is inaccessible for reading.
            // Either way, it is almost certainly not a pipe.
            return "";
        } catch( Exception errnoe ) {
            // Actually ErrnoException, but base type avoids VerifyError on old
            // versions
            // This exception should be VERY rare and means, that the descriptor
            // was made unavailable by some Unix magic.
            return null;
        }

        return resolved;
    }

    public final Uri createDocument( Uri dir_uri, String mime, String name ) {
        try {
            return DocumentsContract.createDocument( ctx.getContentResolver(), dir_uri, mime, name );
        } catch( Throwable e ) {
            return getFileUri( dir_uri, name );
        }
    }

    public static Uri getParent( Uri u ) {
        if( u == null ) return null;
        final List<String> paths = u.getPathSegments();
        final int n = paths.size();
        if( n != 4 ) return null;
        String doc_segm = paths.get( 3 );
        int doc_col_pos = doc_segm.lastIndexOf( ':' );
        if( doc_col_pos < 0 ) { // not a system storage
            int last_sl_pos = doc_segm.lastIndexOf( '/' );
            if( last_sl_pos < 0 )
                Log.w( TAG, "No slash in doc segment: " + doc_segm );
            else
                doc_segm = doc_segm.substring( 0, last_sl_pos );
            return u.buildUpon().path( null ).appendPath( paths.get( 0 ) ).appendPath( paths.get( 1 ) ).appendPath( paths.get( 2 ) ).appendPath( doc_segm ).build();
        }
        if( doc_col_pos == doc_segm.length() - 1 )
            return null;    // already the top
        String doc_subpath = doc_segm.substring( doc_col_pos + 1 );

        String tree_segm = paths.get( 1 );
        int tree_col_pos = tree_segm.lastIndexOf( ':' );
        if( tree_col_pos > 0 ) {
            String tree_subpath = tree_segm.substring( tree_col_pos + 1 );
            if( tree_subpath.equals( doc_subpath ) ) {  // will that work? modifying the tree path...
                int sl_pos = tree_subpath.lastIndexOf( SLC );
                tree_subpath = sl_pos > 0 ? tree_subpath.substring( 0, sl_pos ) : "";
                tree_segm = tree_segm.substring( 0, tree_col_pos + 1 ) + tree_subpath;
            }
        }
        int sl_pos = doc_subpath.lastIndexOf( SLC );
        doc_subpath = sl_pos > 0 ? doc_subpath.substring( 0, sl_pos ) : "";
        doc_segm = doc_segm.substring( 0, doc_col_pos + 1 ) + doc_subpath;
        Uri.Builder ub = u.buildUpon().path( null ).appendPath( paths.get( 0 ) );
        if( true || android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.R )
            ub.appendPath( tree_segm ).appendPath( paths.get( 2 ) );
        return ub.appendPath( doc_segm ).build();
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void setUri( Uri uri_ ) {
        if( ENT_URI.equals( uri_ ) ) {
            this.uri = uri_;
            return;
        }
        currentPath = null;
        if( DocumentsContract.isDocumentUri( ctx, uri_ ) ) {
            this.uri = uri_;
            this.primary = isPrimary( uri_ );
            return;
        }
        if( isTreeUri( uri_ ) ) {   // initial
            if( isPermitted( ctx, uri_ ) ) {
                this.uri = DocumentsContract.buildDocumentUriUsingTree( uri_,
                        DocumentsContract.getTreeDocumentId( uri_ ) );
                return;
            }
        }
        Log.e( TAG, "URI location is invalid or not permitted: " + uri_ );
    }

    public final Uri getFileUri( Uri dir_uri, String filename ) {
        if( filename == null )
            return null;
        Cursor cursor = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String document_id = DocumentsContract.getDocumentId( dir_uri );
            Uri children_uri = DocumentsContract.buildChildDocumentsUriUsingTree( dir_uri, document_id );
            String[] selectionArgs = new String[1];
            selectionArgs[0] = filename;
            cursor = cr.query( children_uri, new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME + " = '?'", selectionArgs, null );
            if( cursor.moveToFirst() ) {
                do {
                    String child_name = cursor.getString( 1 );
                    if( filename.equals( child_name ) )
                        return DocumentsContract.buildDocumentUriUsingTree( dir_uri, cursor.getString( 0 ) );
                } while( cursor.moveToNext() );
            }
        } catch( Throwable e ) {
            Log.e( TAG, filename, e );
        } finally {
            if( cursor != null )
                cursor.close();
        }
        return null;
    }

    public final ArrayList<SAFItem> getChildren( Uri dir_uri, boolean apply_filter ) {
        Cursor c = null;
        try {
            try {
                ContentResolver cr = ctx.getContentResolver();
                String document_id = DocumentsContract.getDocumentId( dir_uri );
                Uri children_uri = DocumentsContract.buildChildDocumentsUriUsingTree( dir_uri, document_id );
                //Log.d( TAG, "Children URI:" + children_uri );
                String[] projection = colIds();
                c = cr.query( children_uri, projection, null, null, null );
            } catch( SecurityException e ) {
                Log.w( TAG, "Security error on " + dir_uri.toString(), e );
                return null;
            } catch( Exception e ) {
                Log.e( TAG, dir_uri.toString(), e );
            }
            if( c != null ) {
                boolean hide = ( mode & MODE_HIDDEN ) == HIDE_MODE;
                ArrayList<SAFItem> tmp_list = new ArrayList<SAFItem>();
                if( c.getCount() == 0 ) return tmp_list;
                int[] ii = colInds( c );
                c.moveToFirst();
                do {
                    SAFItem item = rowToItem( c, dir_uri, ii );
                    item.ctx = ctx;
                    if( hide ) {
                        String fc = item.name.substring( 0, 1 );
                        if( ".".equals( fc ) ) continue;
                        if( "/".equals( fc ) && ".".equals( item.name.substring( 1, 2 ) ) )
                            continue;
                    }
                    if( apply_filter && filter != null && !filter.isMatched( item ) )
                        continue;
                    tmp_list.add( item );
                } while( c.moveToNext() );
                return tmp_list;
            }
        } catch( Exception e ) {
            Log.e( TAG, "Failed cursor processing for " + dir_uri.toString(), e );
        } finally {
            if( c != null ) c.close();
        }
        return null;
    }

    //* DOES NOT WORK!
    public final ArrayList<SAFItem> searchChildren( Uri dir_uri, SearchProps sq ) {
        if( sq == null )
            return getChildren( dir_uri, true );
        Cursor c = null;
        try {
            try {
                if( !sq.dirs && !sq.files )
                    return new ArrayList<SAFItem>();
                StringBuilder sb = new StringBuilder();
                ArrayList<String> sa = new ArrayList<String>();
                if( !( sq.dirs && sq.files ) ) {
                    if( sq.dirs ) {
                        sb.append( Document.COLUMN_MIME_TYPE ).append( "=?" );
                        sa.add( Document.MIME_TYPE_DIR );
                    } else if( sq.files ) {
                        sb.append( Document.COLUMN_MIME_TYPE ).append( "<>?" );
                        sa.add( Document.MIME_TYPE_DIR );
                    }
                }
                if( Utils.str( sq.file_mask ) && !"*".equals( sq.file_mask ) ) {
                    if( sb.length() > 0 )
                        sb.append( " and " );
                    if( sq.file_mask.indexOf( '*' ) >= 0 ) {
                        sb.append( Document.COLUMN_DISPLAY_NAME ).append( " like ?" );
                        sa.add( sq.file_mask.replace( '*', '%' ) );
                    } else {
                        sb.append( Document.COLUMN_DISPLAY_NAME ).append( "=?" );
                        sa.add( sq.file_mask );
                    }
                }
                ContentResolver cr = ctx.getContentResolver();
                String document_id = DocumentsContract.getDocumentId( dir_uri );
                Uri search_uri = DocumentsContract.buildSearchDocumentsUri( dir_uri.getAuthority(), DocumentsContract.getRootId( dir_uri ), "" );
                String[] projection = colIds();
                String[] selectionArgs = new String[sa.size()];
                sa.toArray( selectionArgs );
                c = cr.query( search_uri, projection, sb.toString(), selectionArgs, null );
            } catch( SecurityException e ) {
                Log.w( TAG, "Security error on " + dir_uri.toString(), e );
                return null;
            } catch( Exception e ) {
                Log.e( TAG, dir_uri.toString(), e );
            }
            if( c != null ) {
                boolean hide = ( mode & MODE_HIDDEN ) == HIDE_MODE;
                ArrayList<SAFItem> tmp_list = new ArrayList<SAFItem>();
                if( c.getCount() == 0 ) return tmp_list;
                int[] ii = colInds( c );
                c.moveToFirst();
                do {
                    SAFItem item = rowToItem( c, dir_uri, ii );
                    item.ctx = ctx;
                    if( hide ) {
                        String fc = item.name.substring( 0, 1 );
                        if( ".".equals( fc ) ) continue;
                        if( "/".equals( fc ) && ".".equals( item.name.substring( 1, 2 ) ) )
                            continue;
                    }
                    if( filter != null && !filter.isMatched( item ) ) continue;
                    tmp_list.add( item );
                } while( c.moveToNext() );
                return tmp_list;
            }
        } catch( Exception e ) {
            Log.e( TAG, "Failed cursor processing for " + dir_uri.toString(), e );
        } finally {
            if( c != null ) c.close();
        }
        return null;
    }

    //*/

    private static String[] colIds() {
        final String[] projection = {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE
        };
        return projection;
    }

    private static int[] colInds( Cursor c ) {
        int[] ii = new int[5];
        ii[0] = c.getColumnIndex( Document.COLUMN_DOCUMENT_ID );
        ii[1] = c.getColumnIndex( Document.COLUMN_DISPLAY_NAME );
        ii[2] = c.getColumnIndex( Document.COLUMN_SIZE );
        ii[3] = c.getColumnIndex( Document.COLUMN_MIME_TYPE );
        ii[4] = c.getColumnIndex( Document.COLUMN_LAST_MODIFIED );
        return ii;
    }

    private static SAFItem rowToItem( Cursor c, Uri u, int[] ii ) {
        final int ici = ii[0];
        final int nci = ii[1];
        final int sci = ii[2];
        final int mci = ii[3];
        final int dci = ii[4];

        SAFItem item = new SAFItem();
        String id = c.getString( ici );
        item.origin = id;
        item.uri = DocumentsContract.buildDocumentUriUsingTree( u, id );
        item.mime = c.getString( mci );
        item.dir = Document.MIME_TYPE_DIR.equals( item.mime );
        item.attr = item.dir ? "" : item.mime;
        item.name = c.getString( nci );
        item.size = c.getLong( sci );
        item.date = new Date( c.getLong( dci ) );
        if( item.dir ) item.size = -1;
        return item;
    }

    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        try {
            //if( worker != null ) worker.reqStop();
            if( tmp_uri != null ) {
                Log.d( TAG, "New URI: " + tmp_uri.toString() );
                setUri( tmp_uri );
            }
            if( uri == null ) {
                Log.e( TAG, "No URI" );
                return false;
            }
            setMode( LIST_STATE, STATE_BUSY );
            parentLink = isRootDoc( uri ) ? SLS : PLS;
            search = SearchProps.parseSearchQueryParams( ctx, uri );
            reader = new SAFEngines.ListEngine( this, uri, search, readerHandler, pass_back_on_done );
            return commander.startEngine( reader );
        } catch( Exception e ) {
            Log.e( TAG, "readSource() exception", e );
        } catch( OutOfMemoryError err ) {
            Log.e( TAG, "Out Of Memory", err );
            notify( s( R.string.oom_err ), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    protected void onReadComplete() {
        if( !( reader instanceof SAFEngines.ListEngine ) ) return;
        setMode( LIST_STATE, STATE_IDLE );
        SAFEngines.ListEngine le = (SAFEngines.ListEngine)reader;
        items = le.getItems();
        if( items == null && failCounter++ < 1 ) {
            Intent in = null;
            if( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ) {
                List<String> segms = uri.getPathSegments();
                in = ForwardCompat.getDocTreeIntent( ctx, segms.get(3) );
            }
            if( in == null )
                in = SAFAdapter.getDocTreeIntent( uri );
            commander.issue( in, Commander.REQUEST_OPEN_DOCUMENT_TREE );
            Log.e( TAG, uri.toString() );
            return;
        }
        failCounter = 0;
        reSort( items );
        super.setCount( items.length );
        notifyDataSetChanged();
        startThumbnailCreation();
    }

    protected void startThumbnailCreation() {
        if( thumbnail_size_perc <= 0 ) return;
        if( currentPath == null )
            currentPath = getPath( ctx, uri, true );
        if( currentPath == null || currentPath.charAt( 0 ) != '/' ) return;
        if( tht != null )
            tht.interrupt();
        Handler h = new Handler( Looper.getMainLooper() ) {
            public void handleMessage( Message msg ) {
                notifyDataSetChanged();
            }
        };
        tht = new ThumbnailsThread( this, h, Utils.mbAddSl( currentPath ), items );
        tht.start();
    }

    @Override
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        try {
            Item item = (Item)getItem( acmi.position );
            if( acmi.position != 0 ) {
                if( !item.dir && ".zip".equals( Utils.getFileExt( item.name ) ) ) {
                    menu.add( CM_OPERATION, R.id.open, CM_OPERATION, R.string.open );
// FIXME                    menu.add( 0, R.id.extract, 0, R.string.extract_zip );
                }
                if( item.dir && num == 1 ) {
                    menu.add( CM_SPECIAL, R.id.rescan_dir, CM_SPECIAL, R.string.rescan );
                    if( item instanceof FileItem && ((FileItem)item).f() != null )
                        menu.add( CM_NAVIGATION, R.id.open_native, CM_NAVIGATION, R.string.open_native );
                }
            }
            super.populateContextMenu( menu, acmi, num );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public void doIt( int command_id, SparseBooleanArray cis ) {
        boolean item0 = cis.get( 0 );
        SAFItem[] items = bitsToSAFItems( cis );
        if( items == null || items.length == 0 )
            return;
        if( R.id.open_native == command_id ) {
            Uri.Builder ub = new Uri.Builder();
            ub.encodedPath( items[0].f().getPath() );
            commander.Navigate( ub.build(), null, null );
        }
    }

    @Override
    public void openItem( int position ) {
        if( position == 0 ) {
            if( search != null ) {
                Uri u = uri.buildUpon().clearQuery().build();
                commander.Navigate( u, null, null );
                return;
            }
            Uri uri_to_go = null;
            if( parentLink == SLS )
                uri_to_go = Uri.parse( HomeAdapter.DEFAULT_LOC );
            else {
                //0000-0000:folder%2Fsubfolder
                uri_to_go = getParent( uri );
                if( !isPermitted( ctx, uri_to_go ) ) {
                    if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                        String parent_path = getPath( uri_to_go, true );
                        if( parent_path != null )
                            uri_to_go = Uri.parse( parent_path );
                    } else {
                        if( uri_to_go != null )
                            Log.w( TAG, "Not permitted: " + uri_to_go.toString() );
                        commander.issue( SAFAdapter.getDocTreeIntent(), Commander.REQUEST_OPEN_DOCUMENT_TREE );
                        return;
                    }
                }
                if( uri_to_go == null )
                    uri_to_go = Uri.parse( HomeAdapter.DEFAULT_LOC );
            }
            String pos_to = null;
            String cur_path = getPath( uri, true );
            if( Utils.str( cur_path ) ) {
                int lsp = cur_path.lastIndexOf( '/' );
                if( lsp >= 0 )
                    pos_to = cur_path.substring( lsp );
            }
            commander.Navigate( uri_to_go, null, pos_to );
        } else {
            Item item = items[position - 1];
            if( item.dir )
                commander.Navigate( item.uri, null, null );
            else {
                commander.Open( item.uri, null );

/*
                SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( ctx );
                boolean try_as_file = !shared_pref.getBoolean( "open_content", android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.M ); 
                for( int att = 0; att < 2; att++ ) {
                    Uri to_open = getItemOpenableUri( position, try_as_file );
                    if( to_open == null ) {
                        Log.w( TAG, "No URI to open item " + item );
                        return;
                    }
                    if( ContentResolver.SCHEME_CONTENT.equals( to_open.getScheme() ) ) {
                        try {
                            Intent in = new Intent( Intent.ACTION_VIEW );
                            in.setDataAndType( to_open, item.mime );
                            in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION
                                       | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
                            ctx.startActivity( in );
                            return;
                        } catch( Exception e ) {
                            Log.w( TAG, "Failed to open " + to_open, e );
                        }
                    } else {
                        Log.v( TAG, "Uri:" + to_open.toString() );
                        commander.Open( to_open, null );
                        return;
                    }
                    try_as_file = !try_as_file;
                }
                commander.showError( s( R.string.cant_open ) );
 */
            }
        }
    }

    public final Uri getItemOpenableUri( int position, boolean try_as_file ) {
        try {
            Item item = items[position - 1];
            if( try_as_file ) {
                String full_name = getItemName( position, true );
                if( full_name != null && full_name.charAt( 0 ) == '/' && !full_name.contains( "media_rw" ) ) {
                    Uri.Builder ub = new Uri.Builder();
                    ub.scheme( "file" ).encodedPath( full_name );
                    return ub.build();
                }
            }
            return item.uri;
        } catch( Exception e ) {
            Log.e( TAG, "pos:" + position, e );
        }
        return null;
    }

    @Override
    public Uri getItemUri( int position ) {
        try {
            return items[position - 1].uri;
        } catch( Exception e ) {
            Log.e( TAG, "No item in the position " + position );
        }
        return null;
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( position == 0 ) return parentLink;
        if( position < 0 || items == null || position > items.length )
            return null;
        SAFItem item = items[position - 1];
        if( full ) {
            return getPath( item.uri, item.dir );
        } else
            return item.name != null ? showingPUPs() ? item.name : item.name.replace( "/", "" ) : null;
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            SAFItem[] list = bitsToSAFItems( cis );
            if( list != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new CalcSizesEngine( this, list ) );
            }
        } catch( Exception e ) {
        }
    }

    public final String getVolumeInfo( boolean as_html ) {
        return getVolumeInfo( ctx, uri, as_html );
    }

    public static String getVolumeInfo( Context ctx, Uri u, boolean as_html ) {
        if( !DocumentsContract.isDocumentUri( ctx, u ) && isTreeUri( u ) ) {
            u = DocumentsContract.buildDocumentUriUsingTree( u,
                    DocumentsContract.getTreeDocumentId( u ) );
        }
        try( ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor( u, "r" ) ) {
            if( fd == null ) {
                Log.e( TAG, "Can't get the file descriptor for " + u );
                return null;
            }
            StructStatVfs stat = android.system.Os.fstatvfs( fd.getFileDescriptor() );
            if( stat == null ) {
                Log.e( TAG, "Can't get the stats for " + u );
                return null;
            }
            String res = ctx.getString( R.string.sz_total,
                    Formatter.formatFileSize( ctx, stat.f_bsize * stat.f_blocks ),
                    Formatter.formatFileSize( ctx, stat.f_bsize * stat.f_bfree ) );
            if( !as_html ) {
                res = res.replaceAll( "(</?\\w+?>)", "" ).replace( "\n", ", " );
            }
            return res;
        } catch( IOException | ErrnoException e ) {
            if( BuildConfig.DEBUG )
                Log.e( TAG, "URI: " + u, e );
        }
        return null;
    }

    private final boolean renameItem( Item item, ContentResolver cr, String new_name ) {
        try {
            Uri new_uri = DocumentsContract.renameDocument( cr, item.uri, new_name );
            if( new_uri == null ) return false;
            item.uri = new_uri;
            return true;
        } catch( Exception e ) {
            Log.e( TAG, "Item: " + item.name, e );
        }
        return false;
    }

    @Override
    public boolean renameItem( int position, String new_name, boolean copy ) {
        //FIXME: in what cases the copy==true?
        if( !renameItem( items[position - 1], ctx.getContentResolver(), new_name ) )
            return false;
        notifyRefr( new_name );
        return true;
    }

    class SAFReplacer extends Replacer {
        public String last_file_name = null;
        private Item[] list;
        private ContentResolver cr;

        SAFReplacer( Item[] list, ContentResolver cr ) {
            this.list = list;
            this.cr = cr;
        }

        protected int getNumberOfOriginalStrings() {
            return list.length;
        }

        protected String getOriginalString( int i ) {
            return list[i].name;
        }

        protected void setReplacedString( int i, String replaced ) {
            Item item = list[i];
            renameItem( item, cr, replaced );
            last_file_name = replaced;
        }
    }

    @Override
    public boolean renameItems( SparseBooleanArray cis, String pattern_str, String replace_to ) {
        SAFReplacer r = new SAFReplacer( bitsToItems( cis ), ctx.getContentResolver() );
        r.replace( pattern_str, replace_to );
        notifyRefr( r.last_file_name );
        return false;
    }

    @Override
    public Item getItem( Uri u ) {
        Cursor c = null;
        try {
            SAFItem item = SAFAdapter.getItem( ctx.getContentResolver(), u );
            if( item != null ) item.ctx = ctx;
            return item;
        } catch( Exception e ) {
            return null;
        }
    }

    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            InputStream is = cr.openInputStream( u );
            if( is == null ) return null;
            if( skip > 0 )
                is.skip( skip );
            return is;
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public OutputStream saveContent( Uri u ) {
        if( u != null ) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                return cr.openOutputStream( u, "rwt" );
            } catch( FileNotFoundException e ) {
                Log.e( TAG, u.getPath(), e );
            }
        }
        return null;
    }

    @Override
    public void createFolder( String new_name ) {
        try {
            if( showingPUPs() ) {
                commander.issue( SAFAdapter.getDocTreeIntent(), Commander.REQUEST_OPEN_DOCUMENT_TREE );
                return;
            }
            Uri new_uri = createDocument( uri, Document.MIME_TYPE_DIR, new_name );
            if( new_uri != null ) {
                notifyRefr( new_name );
                return;
            }
        } catch( Exception e ) {
            Log.e( TAG, new_name, e );
        }
        notify( ctx.getString( R.string.cant_md, new_name ), Commander.OPERATION_FAILED );
    }

    @Override
    public boolean createFile( String new_name ) {
        try {
            Uri new_doc_uri = createDocument( uri, "text/plain", new_name );
            return new_doc_uri != null;
        } catch( Exception e ) {
            commander.showError( ctx.getString( R.string.cant_create, new_name, e.getMessage() ) );
        }
        return false;
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        try {
            Item[] list = bitsToItems( cis );
            if( list != null ) {
                if( showingPUPs() ) {
                    ContentResolver cr = ctx.getContentResolver();
                    for( Item item : list ) {
                        cr.releasePersistableUriPermission( item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
                    }
                    notify( ctx.getString( R.string.deleted ), Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
                    return true;
                }
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new SAFEngines.DeleteEngine( this, list ) );
                return true;
            }
        } catch( Exception e ) {
            notify( e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        String err_msg = null;
        try {
            SAFItem[] to_copy = bitsToSAFItems( cis );
            if( to_copy == null ) return false;
            notify( Commander.OPERATION_STARTED );
            SAFEngines.CopyFromEngine mfe = new SAFEngines.CopyFromEngine( this, to_copy, move, to );
            commander.startEngine( mfe );
            return true;
        } catch( Exception e ) {
            err_msg = "Exception: " + e.getMessage();
        }
        notify( err_msg, Commander.OPERATION_FAILED );
        return false;
    }

    // --- Engines.IReciever ---

    @Override
    public boolean receiveItems( String[] uris, int move_mode ) {
        try {
            if( uris == null || uris.length == 0 )
                return false;
            File[] list = Utils.getListOfFiles( uris );
            if( list != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new SAFEngines.CopyToEngine( this, list, move_mode ) );
                return true;
            }
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return false;
    }


    @Override
    public IReceiver getReceiver( Uri dir ) {
        return new Receiver( dir );
    }

    class Receiver implements IReceiver, IDestination {
        private Uri mDest, mLastReceived;
        private ContentResolver cr;

        Receiver( Uri dir ) {
            mDest = dir;
            cr = SAFAdapter.this.ctx.getContentResolver();
        }

        private final Uri buildItemUri( String name ) {
            if( mDest == null || mDest.getLastPathSegment().indexOf( ':' ) < 0 )
                return null;    // not all SAF URIs contain actual path inside!
            String escaped_name = Utils.escapePath( name );
            return mDest.buildUpon().encodedPath( mDest.getEncodedPath() + "%2f" + escaped_name ).build();
        }

        public Uri getLastReceivedUri() {
            return mLastReceived;
        }

        @Override
        public OutputStream receive( String fn ) {
            try {
                String escaped_name = Utils.escapePath( fn );
                String mime = Utils.getMimeByExt( Utils.getFileExt( fn ) );
                mLastReceived = createDocument( mDest, mime, escaped_name );
                if( mLastReceived == null ) {
                    Log.e( TAG, "Can't get URI for " + mDest + " / " + fn );
                    return null;
                }
                return cr.openOutputStream( mLastReceived );
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
            Uri item_uri = buildItemUri( name );
            if( item_uri == null ) // not all SAF URIs contain actual path inside!
                return null;
            return SAFAdapter.getMime( SAFAdapter.this.ctx, item_uri ) != null ? item_uri : null;
        }

        @Override
        public boolean isDirectory( Uri item_uri ) {
            return Document.MIME_TYPE_DIR.equals( SAFAdapter.getMime( SAFAdapter.this.ctx, item_uri ) );
        }

        @Override
        public Uri makeDirectory( String new_dir_name ) {
            try {
                return createDocument( mDest, Document.MIME_TYPE_DIR, new_dir_name );
            } catch( Exception e ) {
                Log.e( TAG, mDest + " / " + new_dir_name, e );
            }
            return null;
        }

        @Override
        public boolean delete( Uri item_uri ) {
            try {
                return DocumentsContract.deleteDocument( cr, item_uri );
            } catch( Exception e ) {
                Log.e( TAG, item_uri.toString(), e );
            }
            return false;
        }

        @Override
        public boolean setDate( Uri item_uri, Date timestamp ) {
            try {
                ContentValues cv = new ContentValues();
                cv.put( Document.COLUMN_LAST_MODIFIED, timestamp.getTime() );
                return 0 < cr.update( item_uri, cv, null, null ); //throws..
            } catch( Exception e ) {
                Log.e( TAG, "Item URI: " + item_uri, e );
            }
            return false;
        }

        @Override
        public boolean done() {
            cr = null;
            return true;
        }

        // --- IDestination ---

        @Override
        public Item getItem( Uri item_uri ) {
            return SAFAdapter.getItem( cr, item_uri );
        }
    }

    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        if( tht != null )
            tht.interrupt();
    }

    @Override
    protected int getPredictedAttributesLength() {
        return 24;   // "application/octet-stream"
    }

    /*
     *  ListAdapter implementation
     */

    @Override
    public int getCount() {
        if( items == null )
            return 1;
        return items.length + 1;
    }

    @Override
    public Object getItem( int position ) {
        Item item = null;
        if( position == 0 ) {
            item = new Item();
            item.name = parentLink;
            item.dir = true;
        } else {
            if( items != null && position <= items.length ) {
                return items[position - 1];
            } else {
                item = new Item();
                item.name = "???";
            }
        }
        return item;
    }

    public final SAFItem[] bitsToSAFItems( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) && cis.keyAt( i ) > 0 )
                    counter++;
            SAFItem[] res = new SAFItem[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) ) {
                    int k = cis.keyAt( i );
                    if( k > 0 )
                        res[j++] = items[k - 1];
                }
            return res;
        } catch( Exception e ) {
            Log.e( TAG, "bitsToItems()", e );
        }
        return null;
    }

    @Override
    protected void reSort() {
        if( items == null ) return;
        synchronized( items ) {
            reSort( items );
        }
    }

    public void reSort( Item[] items_ ) {
        if( items_ == null ) return;
        try {
            ItemComparator comp = new ItemComparator( mode & MODE_SORTING, ( mode & MODE_CASE ) != 0, ascending );
            Arrays.sort( items_, comp );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    @Deprecated
    public IReciever getReceiver() {
        return this;
    }

    public static boolean hasPersisted( Context ctx ) {
        return ctx.getContentResolver().getPersistedUriPermissions().size() > 0;
    }

    public static boolean wasPermitted( Context ctx, Uri uri_ ) {
        if( uri_ == null )
            return false;
        if( DocumentsContract.isDocumentUri( ctx, uri_ ) ) {
            List<String> segms = uri_.getPathSegments();
            uri_ = uri_.buildUpon().path( null ).appendPath( segms.get(0) ).appendPath( segms.get(3) ).build();
        }
        String s_uri = uri_.toString();
        List<UriPermission> uriPermissions = ctx.getContentResolver().getPersistedUriPermissions();
        if( uriPermissions.size() == 0 )
            return false;
        for( UriPermission p : uriPermissions ) {
            if( !p.isReadPermission() )
                continue;
            if( s_uri.contains( p.getUri().toString() ) )
                return true;
        }
        return false;
    }

    public static boolean isPermitted( Context ctx, Uri uri_ ) {
        if( uri_ == null ) return false;
        if( wasPermitted( ctx, uri_ ) )
            return true;
        try {
            if( DocumentsContract.isDocumentUri( ctx, uri_ ) ) {
                List<String> segms = uri_.getPathSegments();
                uri_ = uri_.buildUpon().path( null ).appendPath( segms.get(0) ).appendPath( segms.get(1) ).build();
            }
            ctx.getContentResolver().takePersistableUriPermission( uri_,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
            return true;
        } catch( Exception e ) {
            Log.e( TAG, uri_.toString() );
        }
        return false;
    }

    public static void forget( Context ctx, Uri uri ) {
        ContentResolver cr = ctx.getContentResolver();
        cr.releasePersistableUriPermission( uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
    }

    public static void setPickingGenericModeOn() {
        isPickingGeneric = true;
    }

    public final static Uri makeUriFromUuid( Context ctx, String vol_id ) {
        Uri tree_uri = DocumentsContract.buildTreeDocumentUri( EXTERNAL_STORAGE_AUTHORITY, vol_id + ":" );
        if( tree_uri == null )
            return null;
        return isPermitted( ctx, tree_uri ) ? tree_uri : null;
    }

    /**
     * If there a permitted SAF tree branch which is a parent for the given path (first found, not necessary the closest or widest),
     * a document URI would be constructed from it. Otherwise returns an Uri which is suitable for pass as EXTRA_INITIAL_URI
     * @return  content: URI, or null for a path not suitable to be opened via SAF
     */
    public static Uri getBestUri( Context ctx, String path ) {
        if( path == null )  return null;
        String[]  ps = path.split( "/" );
        if( ps.length < 3 || !"storage".equals( ps[1] ) )
            return null;
        List<UriPermission> uriPermissions = ctx.getContentResolver().getPersistedUriPermissions();
        String vol_doc_id = null, doc_id = null;
        if( "emulated".equals( ps[2] ) ) {
            vol_doc_id = "primary:";
            doc_id = vol_doc_id + Utils.joinEx( ps, "/", 4 );
        } else {
            vol_doc_id = ps[2] + ":";
            doc_id = vol_doc_id + Utils.joinEx( ps, "/", 3 );
        }
        Uri tree_uri = DocumentsContract.buildTreeDocumentUri( EXTERNAL_STORAGE_AUTHORITY, doc_id );
        String s_tree_uri = tree_uri.toString();
        for( UriPermission p : uriPermissions ) {
            if( !p.isReadPermission() )
                continue;
            if( s_tree_uri.contains( p.getUri().toString() ) ) {
                return DocumentsContract.buildDocumentUriUsingTree( p.getUri(), doc_id );
            }
        }
        tree_uri = DocumentsContract.buildTreeDocumentUri( EXTERNAL_STORAGE_AUTHORITY, vol_doc_id );
        return DocumentsContract.buildDocumentUriUsingTree( tree_uri, doc_id );
    }
}
