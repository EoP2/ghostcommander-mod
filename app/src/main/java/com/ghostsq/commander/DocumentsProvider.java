package com.ghostsq.commander;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;
import android.util.LruCache;
import android.widget.ListAdapter;
import android.widget.Toast;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.Manipulator;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.favorites.Favorites;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LooperThread;
import com.ghostsq.commander.utils.Utils;

import java.io.FileNotFoundException;
import java.util.Calendar;
import java.util.Date;

import static android.provider.DocumentsContract.Document;

// some guidance:
// https://medium.com/androiddevelopers/building-a-documentsprovider-f7f2fb38e86a
// https://developer.android.com/guide/topics/providers/create-document-provider

@TargetApi(Build.VERSION_CODES.O)
public class DocumentsProvider extends android.provider.DocumentsProvider {
    private final static String TAG = "DocumentsProvider";
    private final static String DOCUMENTS_AUTHORITY = BuildConfig.APPLICATION_ID + ".documents";
    public  ContentResolver cr;
    private Favorites favorites;
    private RootCommander rootCom; // TODO: have a collection
    private StorageManager sm;

    @Override
    public boolean onCreate() {
        Context ctx = super.getContext();
        if( ctx == null ) return false;
        cr = ctx.getContentResolver();
        if( cr == null ) return false;
        favorites = new Favorites( getContext() );
        favorites.restore();
        return favorites.size() > 0;
    }

    private final static String[] DEFAULT_ROOT_PROJECTION =
      new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS,
        Root.COLUMN_DOCUMENT_ID
    };

    @Override
    public Cursor queryRoots( String[] projection ) throws FileNotFoundException {
        final MatrixCursor c = new MatrixCursor( projection != null ? projection : DEFAULT_ROOT_PROJECTION );
        for( Favorite f : favorites ) {
            Uri fu = f.getUri();
            String scheme = fu.getScheme();
            if( !Utils.str( scheme ) ) continue;
            int scheme_h = scheme.hashCode();
            if( CA.zip_schema_h != scheme_h &&
                CA.ftp_schema_h != scheme_h &&
               CA.sftp_schema_h != scheme_h &&
                CA.smb_schema_h != scheme_h )
                continue;
            Credentials crd = f.getCredentials();
            if( CA.smb_schema_h != scheme_h &&
                CA.zip_schema_h != scheme_h ) {
                if( crd == null )
                    continue;
                if( crd.getPassword() == null )
                    continue;
            }
            DocID did = new DocID();
            did.setFID( f.getID() ).setPath( "" );
            String s_uri = f.getUriString( true ), comment = f.getComment();
            final MatrixCursor.RowBuilder row = c.newRow();
            row.add( Root.COLUMN_ROOT_ID, f.getID() );
            row.add( Root.COLUMN_TITLE,  comment == null ? s_uri : comment  );
            row.add( Root.COLUMN_SUMMARY,comment == null ?  null : s_uri  );
            row.add( Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE );
            row.add( Root.COLUMN_DOCUMENT_ID, did.compose() );
            row.add( Root.COLUMN_ICON, CA.getDrawableIconId( scheme ) );
        }
        return c;
    }

    private final static String[] DEFAULT_CHILD_PROJECTION =
      new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_ICON,
        Document.COLUMN_FLAGS
    };

    @Override
    public Cursor queryDocument( String doc_id, String[] projection ) throws FileNotFoundException {
        Log.d( TAG, "queryDocument " + doc_id );
        DocID did = new DocID( doc_id );
        RootCommander rc = getRootCom( did );
        if( rc == null ) return null;
        final MatrixCursor cursor = new MatrixCursor( projection != null ? projection : DEFAULT_CHILD_PROJECTION );
        if( !Utils.str( did.getPath() ) ) {
            Log.d( TAG, "Root doc: " + doc_id );
            rc.toRow( doc_id, cursor.newRow() );
            return cursor;
        }
        Item item = rc.getItem( did );
        if( item == null )
            throw new FileNotFoundException( "No item for " + doc_id );
        // FIXME: How to make it to refresh ?????? recommendations from this post do not work.
        // https://stackoverflow.com/questions/24465999/content-resolver-notifychange-not-working/27583807#27583807
        if( item.origin instanceof Uri ) {
            Uri iu = (Uri)(item.origin);
            Log.d( TAG, "Synthetic item, Parent URI: " + iu );
            if( DOCUMENTS_AUTHORITY.equals( iu.getAuthority() ) )
                cursor.setNotificationUri( cr, iu );
        }
        rc.itemToRow( did, item, cursor.newRow(), false );
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments( String ctr_id, String[] projection, String sortOrder ) throws FileNotFoundException {
        Log.d( TAG, "queryChildDocuments " + ctr_id );
        DocID ctr_did = new DocID( ctr_id );
        RootCommander rc = getRootCom( ctr_did );
        if( rc == null ) return null;
        if( rc.status != Commander.OPERATION_COMPLETED  ) {
            Log.d( TAG, "Preparing for queryChildDocuments " + ctr_id );
            Uri ready_uri = DocumentsContract.buildDocumentUri( DOCUMENTS_AUTHORITY, ctr_did.compose() );
            MatrixCursor cursor = new MatrixCursor( projection != null ? projection : DEFAULT_CHILD_PROJECTION ) {
                @Override
                public Bundle getExtras() {
                    Log.v( TAG, "getExtras()" );
                    Bundle bundle = new Bundle();
                    bundle.putBoolean( DocumentsContract.EXTRA_LOADING, true );
                    return bundle;
                }
            };
            if( rc.status == Commander.OPERATION_STARTED ) {
                Log.e( TAG, "Previous request still in progress" );
                cursor.setNotificationUri( cr, ready_uri );
                return cursor;
            }
            Uri ctr_uri = null, fav_uri = rc.f.getUri();
            String id_path = ctr_did.getPath();
            if( Utils.str( id_path ) ) {
                if( id_path.charAt( 0 ) != '/' )
                    id_path = Utils.mbAddSl( fav_uri.getPath() ) + id_path;
                ctr_uri = fav_uri.buildUpon().path( id_path ).build();
            } else
                ctr_uri = fav_uri;
            rc.setPendingListOperation( ready_uri );
            if( sortOrder != null ) {
                int so = 0;
                if( sortOrder.contains( Document.COLUMN_DISPLAY_NAME ) ) so = CommanderAdapter.SORT_NAME; else
                if( sortOrder.contains( Document.COLUMN_SIZE         ) ) so = CommanderAdapter.SORT_SIZE; else
                if( sortOrder.contains( Document.COLUMN_LAST_MODIFIED) ) so = CommanderAdapter.SORT_DATE;
                if( sortOrder.contains( " ASC" ) ) so |= CommanderAdapter.SORT_ASC; else
                if( sortOrder.contains( " DESC") ) so |= CommanderAdapter.SORT_DSC;
                rc.ca.setMode( CommanderAdapter.MODE_SORTING | CommanderAdapter.MODE_SORT_DIR, so );
            }
            rc.ca.readSource( ctr_uri, null );
            Log.d( TAG, "Deffered, URI: " + ready_uri );
            cursor.setNotificationUri( cr, ready_uri );
            return cursor;
        } else {
            Log.d( TAG, "ready? " + rc.status );
            String base_path = Utils.mbAddSl( rc.f.getUri().getPath() );
            Uri ca_uri = rc.ca.getUri();
            int uri_hash = ca_uri.hashCode();
            ListAdapter la = (ListAdapter)rc.ca;
            int n = la.getCount();
            final MatrixCursor c = new MatrixCursor( projection != null ? projection : DEFAULT_CHILD_PROJECTION );
            for( int i = 1; i < n; i++ ) {
                Object io = la.getItem( i );
                if( io == null ) continue;
                Item item = (Item)io;
                Uri item_uri = item.getUri();
                if( item_uri == null )
                    item_uri = rc.ca.getItemUri( i );
                if( item_uri == null ) {
                    Log.w( TAG, "Cannot get URI for item " + i );
                    continue;
                }
                String item_path = item_uri.getPath();
                if( item_path == null ) {
                    Log.w( TAG, "Cannot get path for item " + i );
                    continue;
                }
                String id_path = null;
                if( base_path != null && item_path.startsWith( base_path ) ) {
                    id_path = item_path.substring( base_path.length() );
                } else
                    id_path = item_path;
                DocID item_did = new DocID();
                item_did.setFID( ctr_did.getFID() ).setPath( id_path );//.setParentHash( uri_hash ).setPos( i );
                rc.itemToRow( item_did, item, c.newRow(), true );
            }
            rc.status = 0;
            return c;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument( String doc_id, String s_mode, CancellationSignal signal ) throws FileNotFoundException {
        if( BuildConfig.DEBUG ) Log.v( TAG, "openDocument( " + doc_id + " ) " + s_mode );
        DocID did = new DocID( doc_id );
        RootCommander rc = getRootCom( did );
        if( rc == null )
            throw new FileNotFoundException( "Invalid root" );
        Item item = rc.getItem( did );
        if( item == null )
            throw new FileNotFoundException( "No such item" );
        try {
            if( sm == null ) {
                sm = this.getContext().getApplicationContext().getSystemService( StorageManager.class );
                if( sm == null )
                    return null;
            }
            LooperThread lt = new LooperThread();
            lt.start();
            Handler h = lt.getHandler();
            if( h == null ) {
                Log.e( TAG, "No handler" );
                return null;
            }
            int mode = ParcelFileDescriptor.parseMode( s_mode );
            return sm.openProxyFileDescriptor( mode, new DataProxy( rc.ca, item, mode, signal ), h );
        } catch( Exception e ) {
            Log.e( TAG, doc_id, e );
        }
        return null;
    }

    public String renameDocument( String doc_id, String name ) throws FileNotFoundException {
        DocID did = new DocID( doc_id );
        RootCommander rc = getRootCom( did );
        if( rc == null )
            throw new FileNotFoundException( "Invalid root" );
        if( !( rc.ca instanceof Manipulator ) )
            throw new FileNotFoundException( "Not supported" );
        Item item = rc.getItem( did );
        if( item == null )
            throw new FileNotFoundException( "No such item" );
        try {
            Manipulator m = (Manipulator)rc.ca;
            if( m.renameItem( item, name, false ) ) {
                String p = did.getPath();
                int lsp = p.lastIndexOf( '/' );
                if( lsp >= 0 )
                    p = p.substring( 0, lsp + 1 ) + name;
                else
                    p = name;
                did.setPath( p );
                return did.compose();
            }
        } catch( Exception e ) {
            Log.e( TAG, doc_id, e );
        }
        return null;
    }

    public void deleteDocument( String doc_id ) throws FileNotFoundException {
        DocID did = new DocID( doc_id );
        RootCommander rc = getRootCom( did );
        if( rc == null )
            throw new FileNotFoundException( "Invalid root" );
        if( !( rc.ca instanceof Manipulator ) )
            throw new FileNotFoundException( "Not supported" );
        Item item = rc.getItem( did );
        if( item == null )
            throw new FileNotFoundException( "No such item" );
        try {
            Manipulator m = (Manipulator)rc.ca;
            if( m.deleteItem( item ) ) {
                DocID ctr_did = did.getParent();
                if( ctr_did == null )
                    return;
                Uri ready_uri = DocumentsContract.buildDocumentUri( DOCUMENTS_AUTHORITY, ctr_did.compose() );
                rc.setPendingOperation( ready_uri );
            }
        } catch( Exception e ) {
            Log.e( TAG, doc_id, e );
        }
    }

    public String createDocument( String ctr_id, String mime, String name ) throws FileNotFoundException {
        Log.d( TAG, "createDocument " + ctr_id + " " + name );
        DocID ctr_did = new DocID( ctr_id );
        Uri ready_uri = DocumentsContract.buildDocumentUri( DOCUMENTS_AUTHORITY, ctr_did.compose() );
        RootCommander rc = getRootCom( ctr_did );
        if( rc == null )
            throw new FileNotFoundException( "Invalid root" );
        Item item = new Item();
        item.origin = ready_uri;
        item.name = name;
        rc.setPendingOperation( ready_uri );
        if( DocumentsContract.Document.MIME_TYPE_DIR.equals( mime ) ) {
            rc.ca.createFolder( name );
            item.dir = true;
        } else {
            rc.ca.createFile( name );
            item.size = 0;
        }
        DocumentsProvider.this.cr.notifyChange( ready_uri, null, false );

        // TODO: review and optimize this:
        Uri new_uri = rc.ca.getUri().buildUpon().appendPath( name ).build();
        String new_path = new_uri.getPath();
        String base_path = Utils.mbAddSl( rc.f.getUri().getPath() );
        String id_path;
        if( base_path != null && new_path.startsWith( base_path ) ) {
            id_path = new_path.substring( base_path.length() );
        } else
            id_path = new_path;
        item.uri = new_uri;
        item.date = new Date();
        rc.cacheItem( id_path, item );

        DocID new_did = new DocID();
        new_did.setFID( ctr_did.getFID() ).setPath( id_path );
        return new_did.compose();
    }

    private RootCommander getRootCom( DocID did ) {
        String fid = did.getFID();
        if( fid == null ) {
            Log.e( TAG, "No fid." );
            return null;
        }
        Favorite f = favorites.get( fid );
        if( f == null ) {
            Log.e( TAG, "No such fave with id " + fid );
            return null;
        }
        if( rootCom == null || !fid.equals( rootCom.f.getID() ) )
            rootCom = new RootCommander( f ); // TODO support multiple root Coms at the same time
        if( rootCom.ca == null ) {
            rootCom.ca = CA.CreateAdapterInstance( f.getUri(), getContext() );
            if( rootCom.ca == null ) {
                Log.e( TAG, "Unable to create CA for " + f.getUri() );
                return null;
            }
            rootCom.ca.Init( rootCom );
            rootCom.ca.setCredentials( f.getCredentials() );
        }
        return rootCom;
    }

    private class RootCommander implements Commander {
        private final static String TAG = "RootCommander";
        public Favorite f;
        public CommanderAdapter ca;
        public int status = 0;
        private Uri readyUri;
        private final LruCache<String, Item> cache;

        public RootCommander( Favorite f ) {
            this.f = f;
            cache = new LruCache<>( 1000 );
        }

        @Override
        public Context getContext() {
            return DocumentsProvider.this.getContext();
        }
        @Override
        public void issue( Intent in, int ret ) {
        }
        @Override
        public void showError( String msg ) {
            Toast.makeText( getContext(), msg, Toast.LENGTH_LONG ).show();
        }
        @Override
        public void showInfo( String msg ) {
            Toast.makeText( getContext(), msg, Toast.LENGTH_LONG ).show();
        }
        @Override
        public void showDialog( int dialog_id ) {
        }
        @Override
        public void Navigate( Uri uri, Credentials crd, String positionTo ) {
        }
        @Override
        public void dispatchCommand( int id ) {
        }
        @Override
        public void Open( Uri uri, Credentials crd ) {
        }
        @Override
        public int getResolution() {
            return 0;
        }
        @Override
        public boolean notifyMe( Message m ) {
            if( BuildConfig.DEBUG )
                Log.v( TAG, "Notification: " + m );
            String string = "";
            if( m.obj instanceof Bundle ) {
                string = ( (Bundle)m.obj ).getString( MESSAGE_STRING );
                Log.d( TAG, "Not: " + m.what + ": " + string );
            }
            if( m.what == OPERATION_COMPLETED ) {
                Log.d( TAG, "Operation completed " + readyUri );
                status = OPERATION_COMPLETED;
                if( readyUri != null ) {
                    DocumentsProvider.this.cr.notifyChange( readyUri, null );
                    readyUri = null;
                }
                return false;
            }
            if( m.what == OPERATION_COMPLETED_REFRESH_REQUIRED ) {
                Log.d( TAG, "Operation completed, user need to refresh " + readyUri );
                status = 0;
                if( readyUri != null ) {
                    DocumentsProvider.this.cr.notifyChange( readyUri, null );
                    readyUri = null;
                }
                showInfo( getContext().getString( R.string.refresh ) );
                return false;
            }
            if( m.what == OPERATION_FAILED || m.what == OPERATION_FAILED_LOGIN_REQUIRED ) {
                Log.d( TAG, "Operation failed " + readyUri );
                status = OPERATION_FAILED;
                showError( getContext().getString( R.string.failed ) + string );
                readyUri = null;
                return false;
            }
            return false;
        }
        @Override
        public boolean startEngine( Engine e ) {
            Log.d( TAG, "startEngine() " + e );
            e.setHandler( ((CommanderAdapterBase)ca).simpleHandler );
            e.start();
            return true;
        }
        @Override
        public boolean stopEngine( long task_id ) {
            return false;
        }

        public void setPendingOperation( Uri ready_uri ) {
            readyUri = ready_uri;
        }

        public void setPendingListOperation( Uri ready_uri ) {
            status = Commander.OPERATION_STARTED;
            readyUri = ready_uri;
        }

        public Item getItem( DocID did ) {
            if( did == null )
                return null;
            String id_path = did.getPath();
            Item item = cache.get( id_path );
            if( item != null ) {
                Log.v( TAG, "Item from cache: " + did );
                if( item.uri == null )
                    item.uri = getItemUri( id_path );
            } else {
                if( ca == null ) {
                    Log.e( TAG, "No adapter for " + did );
                    return null;
                }
                Uri item_uri = getItemUri( id_path );
                item = ca.getItem( item_uri );
                if( item == null ) {
                    Log.e( TAG, "No item for " + did );
                    return null;
                }
                cache.put( id_path, item );
                if( item.uri == null )
                    item.uri = item_uri;
            }
            return item;
        }

        public Uri getItemUri( String id_path ) {
            String full_path = id_path;
            if( Utils.str( full_path ) && id_path.charAt( 0 ) != '/' )
                full_path = Utils.mbAddSl( f.getUri().getPath() ) + id_path;
            return f.getUri().buildUpon().path( full_path ).build();
        }

        public void itemToRow( DocID did, Item item, MatrixCursor.RowBuilder row, boolean to_cache ) throws FileNotFoundException {
            if( item == null )
                throw new FileNotFoundException( "No item!" );
            //if( BuildConfig.DEBUG ) Log.v( TAG, did.compose() + " Item: " + item.name );
            Calendar cal = Calendar.getInstance();
            if( item.date != null ) cal.setTime( item.date );
            row.add( Document.COLUMN_DOCUMENT_ID, did.compose() );
            row.add( Document.COLUMN_DISPLAY_NAME, item.name );
            row.add( Document.COLUMN_SIZE, item.size );
            row.add( Document.COLUMN_LAST_MODIFIED, cal.getTimeInMillis() );
            row.add( Document.COLUMN_MIME_TYPE, item.dir ? DocumentsContract.Document.MIME_TYPE_DIR : Utils.getMimeByExt( Utils.getFileExt( item.name ) ) );
            row.add( Document.COLUMN_ICON, item.icon_id );
            int flags = 0;
            if( ca != null ) {
                Uri ca_uri = ca.getUri();
                if( ca.hasFeature( CommanderAdapter.Feature.F7 ) && item.dir ) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                if( ca.hasFeature( CommanderAdapter.Feature.RENAME ) )         flags |= Document.FLAG_SUPPORTS_RENAME;
                if( ca.hasFeature( CommanderAdapter.Feature.DELETE ) )         flags |= Document.FLAG_SUPPORTS_DELETE;
                if( ca.hasFeature( CommanderAdapter.Feature.REAL ) )           flags |= Document.FLAG_SUPPORTS_WRITE;
            }
            row.add( Document.COLUMN_FLAGS, flags );
            if( to_cache )
                cache.put( did.getPath(), item );
        }

        public void toRow( String doc_id, MatrixCursor.RowBuilder row ) {
            row.add( Document.COLUMN_DOCUMENT_ID, doc_id );
            row.add( Document.COLUMN_DISPLAY_NAME, f.getUriString( true ) );
            row.add( Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR );
            row.add( Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE );
        }

        public void cacheItem( String id_path, Item item ) {
            if( id_path == null || item == null ) return;
            cache.put( id_path, item );
        }
    }

    static class DocID {
        private String str, fid, path;

        public DocID() {
        }

        public DocID( String s ) {
            str = s;
        }

        public String toString() {
            return compose();
        }

        public String compose() {
            if( str != null )
                return str;
            return getFID() + ":" + getPath();
        }

        public DocID setFID( String fid ) {
            if( str != null ) {
                parse();
                str = null;
            }
            this.fid = fid;
            return this;
        }

        public DocID setPath( String path ) {
            if( str != null ) {
                parse();
                str = null;
            }
            this.path = path;
            return this;
        }

        public boolean parse() {
            if( str == null )
                return false;
            String[] pp = str.split( ":" );
            if( pp.length < 1 )
                return false;
            fid = pp[0];
            path = pp.length > 1 ? pp[1] : "";
            return true;
        }

        public String getFID() {
            if( fid == null && !parse() )
                return null;
            return fid;
        }

        public String getPath() {
            if( path == null && !parse() )
                return null;
            return path;
        }

        public DocID getParent() {
            if( str != null ) {
                parse();
                str = null;
            }
            if( path == null || path.length() == 0 )
                return null;
            int lsp = path.lastIndexOf( '/', path.length() - 2 );
            String pp = lsp < 0 ? "" : path.substring( 0, lsp );
            DocID pdid = new DocID();
            pdid.setFID( fid ).setPath( pp );
            return pdid;
        }
    }
}
