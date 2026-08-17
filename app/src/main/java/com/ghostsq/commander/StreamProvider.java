package com.ghostsq.commander;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.os.storage.StorageManager;
import android.util.Base64;
import android.util.Log;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LooperThread;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.List;

import static android.provider.MediaStore.MediaColumns;

public class StreamProvider extends ContentProvider {
    private static final String TAG = "StreamProvider";
    public  static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".StreamProvider";
    private static final String CA_MODE_SIG = "CA", QP_MODE_SIG = "QP";
    private static Hashtable<Integer, CommanderAdapter.Item> storage;
    private StorageManager      sm;

    public static boolean canProvide( Uri uri ) {
        if( uri == null ) return false;
        if( !AUTHORITY.equals( uri.getAuthority() ) ) return false;
        return true;
    }

    public static Uri getUri( Uri ca_uri, Item item ) {
        return getUri( ca_uri, item.name, item.mime, item.size );
    }

    public static Uri getUri( Uri ca_uri, String name, String mime, long size ) {
        Uri.Builder ub = new Uri.Builder();
        ub.scheme( ContentResolver.SCHEME_CONTENT ).authority( AUTHORITY )
            .appendPath( QP_MODE_SIG )
            .appendQueryParameter( "URI", Base64.encodeToString( ca_uri.toString().getBytes(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP ) )
            .appendQueryParameter( "name", name )
            .appendQueryParameter( "mime", mime )
            .appendQueryParameter( "size", String.valueOf( size ) );
        return ub.build();
    }

    private static Integer getKey( Uri ca_uri ) {
        return Integer.valueOf( Math.abs( ca_uri.hashCode() ) );
    }

    private static void useStorage() {
        if( storage == null )
            storage = new Hashtable<>( 1 );
    }

    public static Uri put( Uri ca_uri, Item item ) {
        Integer key = getKey( ca_uri );
        item.uri = ca_uri;
        useStorage();
        storage.remove( key );
        storage.put( key, item );
        Uri.Builder ub = new Uri.Builder();
        ub.scheme( ContentResolver.SCHEME_CONTENT ).authority( AUTHORITY )
            .appendPath( CA_MODE_SIG ).appendPath( key.toString() );
        return ub.build();
    }

    public static Uri put( Uri ca_uri, String name, String mime, long size ) {
        Item item = new Item();
        item.name = name;
        item.uri = ca_uri;
        item.mime = mime;
        item.size = size;
        return put( ca_uri, item );
    }
    
    public static Uri getUri( Uri content_uri ) {
        List<String> ps = content_uri.getPathSegments();
        if( ps == null || ps.size() < 1 ) return null;
        if( CA_MODE_SIG.equals( ps.get(0) ) ) {
            useStorage();
            if( ps.size() < 2 ) return null;
            Integer key = Integer.parseInt( ps.get(1) );
            Item item = storage.get( key );
            if( item != null )
                return item.uri;
            return null;
        }
        if( QP_MODE_SIG.equals( ps.get(0) ) ) {
            String b64uri = content_uri.getQueryParameter( "URI" );
            if( b64uri == null ) return null;
            return Uri.parse( new String( Base64.decode( b64uri, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP ) ) );
        }
        return null;
    }

    public static Item getItem( Uri content_uri ) {
        try {
            List<String> ps = content_uri.getPathSegments();
            if( ps == null || ps.size() < 1 ) return null;
            if( CA_MODE_SIG.equals( ps.get(0) ) ) {
                useStorage();
                if( ps.size() < 2 ) return null;
                Integer key = Integer.parseInt( ps.get(1) );
                return storage.get( key );
            }
            if( QP_MODE_SIG.equals( ps.get(0) ) ) {
                Item item = new Item();
                String b64uri = content_uri.getQueryParameter( "URI" );
                item.uri = Uri.parse( new String( Base64.decode( b64uri, Base64.URL_SAFE ) ) );
                item.name = content_uri.getQueryParameter( "name" );
                item.mime = content_uri.getQueryParameter( "mime" );
                item.size = Integer.parseInt( content_uri.getQueryParameter( "size" ) );
                return item;
            }
        } catch( NumberFormatException e ) {
            Log.e( TAG, "URI: " + content_uri, e );
        }
        return null;
    }

    public static void storeCredentials( Context ctx, Credentials crd, Uri uri ) {
        crd.storeCredentials( ctx, StreamProvider.class.getSimpleName(), uri );
    }

    public static Credentials restoreCredentials( Context ctx, Uri uri ) {
        return Credentials.restoreCredentials( ctx, StreamProvider.class.getSimpleName(), uri );
    }
    
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType( Uri uri ) {
        if( BuildConfig.DEBUG ) Log.d( TAG, "getType( " + uri + " ) " );
        Item item = getItem( uri );
        if( item == null ) {
            Log.e( TAG, "No item for " + uri );
            return null;
        }
        if( BuildConfig.DEBUG ) Log.d( TAG, "Requested item type: " + item.mime );
        if( !Utils.str(item.mime) || Utils.MIME_ALL.equals( item.mime ) )
            return  "application/octet-stream";
        return item.mime;

    }

    @Override
    public Cursor query( Uri uri, String[] fields, String sel, String[] sel_args, String sort ) {
        return query( uri, fields, sel, sel_args, sort, null );
    }

    @Override
    public Cursor query( Uri uri, String[] fields, String sel, String[] sel_args, String sort, CancellationSignal cancellation ) {
        try {
            if( BuildConfig.DEBUG ) Log.d( TAG, "query( " + uri + " ) " + Utils.join( fields, "," ) );
            if( !AUTHORITY.equals( uri.getAuthority() ) )
                throw new RuntimeException( "Unsupported URI" );
            CommanderAdapter ca = null;
            Item item = getItem( uri );
            if( item == null )
                return null;
            if( fields == null )
                fields = new String[] { MediaColumns.DISPLAY_NAME, MediaColumns.TITLE, MediaColumns.SIZE };
            MatrixCursor c = new MatrixCursor( fields );
            MatrixCursor.RowBuilder row = c.newRow();
            for( String col : fields ) {
                if( MediaColumns.MIME_TYPE.equals( col ) ) {
                    row.add( item.mime );
                } else if( MediaColumns.DISPLAY_NAME.equals( col ) ||
                           MediaColumns.TITLE.equals( col )) {
                    row.add( item.name );
                } else if( MediaColumns.SIZE.equals( col ) ) {
                    if( item.size < 0 && item.uri != null ) {
                        if( ca == null )
                            ca = CreateCA( item.uri );
                        if( ca != null ) {
                            Item real_item = ca.getItem( item.uri );
                            if( real_item != null )
                                item.size = real_item.size;
                        }
                    }
                    row.add( item.size );
                } else if( MediaColumns.WIDTH.equals( col ) ||
                           MediaColumns.HEIGHT.equals( col ) ) {
                    if( item.height < 0 || item.width < 0 ) {
                        if( ca == null )
                            ca = CreateCA( item.uri );
                        if( ca != null ) {
                            InputStream is = ca.getContent( item.uri );
                            if( is != null ) {
                                BitmapFactory.Options bfo = new BitmapFactory.Options();
                                bfo.inJustDecodeBounds = true;
                                bfo.outWidth  = 0;
                                bfo.outHeight = 0;
                                Bitmap bitmap = BitmapFactory.decodeStream( is, null, bfo );
                                item.height = bfo.outHeight;
                                item.width  = bfo.outWidth;
                                ca.closeStream( is );
                            }
                        }
                    }
                    row.add( MediaColumns.WIDTH.equals( col ) ? item.width : item.height );
                    if( BuildConfig.DEBUG ) Log.d( TAG, "img w:" + item.width + ", img h:" + item.height );
                } else {
                    // Unsupported or unknown columns are filled up with null
                    row.add(null);
                }
            }
            return c;
        } catch( Exception e ) {
            Log.e( TAG, "Can't provide for query " + uri, e );
        }
        return null;
    }

    class TransferThread extends Thread {
        private static final String TAG = "TransferThread";
        ParcelFileDescriptor  in;
        ParcelFileDescriptor out;
        Uri uri;
        CancellationSignal cancellation;

        TransferThread( ParcelFileDescriptor in, Uri uri, CancellationSignal cancellation ) {
            this.in  = in;
            this.uri = uri;
            this.cancellation = cancellation;
        }

        TransferThread( Uri uri, ParcelFileDescriptor out, CancellationSignal cancellation ) {
            this.out = out;
            this.uri = uri;
            this.cancellation = cancellation;
        }

        @Override
        public void run() {
            if( cancellation != null && cancellation.isCanceled() ) {
                Log.w( TAG, "Cancelled!" );
                try {
                    if(  in != null )  in.close();
                    if( out != null ) out.close();
                } catch( IOException e ) {
                    Log.e( TAG, "", e );
                }
                return;
            }
            CommanderAdapter ca = StreamProvider.this.CreateCA( uri );
            boolean ca_is_in = in == null && out != null;
            InputStream  is = null;
            OutputStream os = null;
            if( ca_is_in ) {
                os = new AutoCloseOutputStream( out );
                is = ca.getContent( uri );
                if( is == null ) {
                    try {
                        out.closeWithError( "Can't read from " + uri );
                    } catch( IOException e ) {
                        Log.e( TAG, uri.toString(), e );
                    }
                    Log.e( TAG, "Can't get the input stream of " + uri );
                    return;
                }
            } else {
                is = new AutoCloseInputStream( in );
                os = ca.saveContent( uri );
                if( os == null ) {
                    try {
                        in.closeWithError( "Can't write to " + uri );
                    } catch( IOException e ) {
                        Log.e( TAG, uri.toString(), e );
                    }
                    Log.e( TAG, "Can't get the output stream of " + uri );
                    return;
                }
            }
            final int MAX_CHUNK = 131072;
            byte[] buf = new byte[MAX_CHUNK];
            int len = 0, has_read;
            if( BuildConfig.DEBUG ) Log.d( TAG, "Transfer Thread is running" );
            try {
                int chunk = 10240;
                while( (has_read = is.read( buf, 0, chunk )) > 0 ) {
                    //if( BuildConfig.DEBUG ) Log.d( TAG, "Transfer Thread has read " + has_read + " bytes" );
                    if( out != null )
                        out.checkError();
                    os.write( buf, 0, has_read );
                    len += has_read;
                    if( cancellation != null && cancellation.isCanceled() ) {
                        Log.w( TAG, "Cancelled in the middle!" );
                        return;
                    }
                    if( chunk < MAX_CHUNK ) chunk *= 2;
                    if( chunk > MAX_CHUNK ) chunk = MAX_CHUNK;
                }
                if( BuildConfig.DEBUG ) Log.d( TAG, "Bytes read: " + len );
                os.flush();
            } catch( IOException e ) {
                Log.e( TAG, "Exception transferring file. Were able to read bytes " + len, e );
            } finally {
                try {
                    if( ca_is_in ) {
                        ca.closeStream( is );
                        os.close();
                    } else {
                        is.close();
                        ca.closeStream( os );
                    }
                } catch( IOException e ) {
                    Log.e( TAG, uri.toString(), e );
                }
            }
        }
    }    

    private final CommanderAdapter CreateCA( Uri u ) {
        CommanderAdapter ca = CA.CreateAdapterInstance( u, getContext().getApplicationContext(), this.getClass().getClassLoader() );
        if( ca == null )
            return null;
        ca.Init( null );
        String ui = u.getUserInfo();
        if( ui != null ) {
            Credentials credentials = restoreCredentials( getContext(), u );
            if( credentials != null ) {
                ca.setCredentials( credentials );
                u = Utils.updateUserInfo( u, null );
            }
        }
        ca.setUri( u );
        return ca;
    }
    
    @Override
    public ParcelFileDescriptor openFile( Uri uri, String access_mode ) throws FileNotFoundException {
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
            return openProxy( uri, access_mode, null );
        else
            return openPipe( uri, access_mode, null );
    }

    @Override
    public ParcelFileDescriptor openFile( Uri uri, String access_mode, CancellationSignal cancellation ) throws FileNotFoundException {
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
            return openProxy( uri, access_mode, cancellation );
        else
            return openPipe( uri, access_mode, cancellation );
    }

    @TargetApi(Build.VERSION_CODES.O)
    private final ParcelFileDescriptor openProxy( Uri uri, String access_mode, CancellationSignal cancellation ) throws FileNotFoundException {
        if( BuildConfig.DEBUG ) Log.v( TAG, "openProxy( " + uri + " ) " + access_mode );
        try {
            LooperThread lt = new LooperThread();
            lt.start();
            Item item = getItem( uri );
            if( item == null )
                return null;
            CommanderAdapter ca = CreateCA( item.uri );
            if( ca == null )
                return null;
            if( this.sm == null )
                sm = this.getContext().getApplicationContext().getSystemService( StorageManager.class );
            int mode = ParcelFileDescriptor.parseMode( access_mode );
            Handler h = lt.getHandler();
            if( h == null ) {
                Log.e( TAG, "No handler" );
                return null;
            }
            return sm.openProxyFileDescriptor( mode, new DataProxy( ca, item, mode, cancellation ), h );
        } catch( Exception e ) {
            Log.e( TAG, " " + uri.toString(), e );
        }
        return null;
    }

    private final ParcelFileDescriptor openPipe( Uri uri, String access_mode, CancellationSignal cancellation ) throws FileNotFoundException {
        if( BuildConfig.DEBUG ) Log.v( TAG, "openFile( " + uri + " ) " + access_mode + (cancellation != null ? " with" : " without") + " cancellation" );
        Uri ca_uri = getUri( uri );
        String scheme = ca_uri.getScheme();
        if( !Utils.str(scheme) || "file".equals( scheme ) )
            return openLocalFile( ca_uri, access_mode );
        try {
            if( BuildConfig.DEBUG ) Log.d( TAG, "Creating a pipe" );
//            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliableSocketPair();
            if( "r".equals( access_mode ) ) {
                new TransferThread( ca_uri, pipe[1], cancellation ).start();
                if( BuildConfig.DEBUG ) Log.d( TAG, "Transfer Thread has been started" );
                return pipe[0];
            }
            if( "w".equals( access_mode ) ) {
                if( BuildConfig.DEBUG ) Log.d( TAG, "Transfer Thread has been started" );
                new TransferThread( pipe[0], ca_uri, cancellation ).start();
                return pipe[1];
            }
        }
        catch( IOException e ) {
            Log.e( TAG, "Exception opening pipe to " + ca_uri.toString(), e );
        }
        return null;
    }

    private final ParcelFileDescriptor openLocalFile( Uri uri, String access_mode ) throws FileNotFoundException {
        try {
            File file = new File( uri.getPath() );
            if( !file.exists() ) {
                if( "w".equals( access_mode ) )
                    file.createNewFile();
                else
                    throw new FileNotFoundException();
            }
            int pfd_mode = ParcelFileDescriptor.parseMode( access_mode );
            ParcelFileDescriptor parcel = ParcelFileDescriptor.open( file, pfd_mode );
            return parcel;
        } catch( IOException e ) {
            Log.e( TAG, access_mode + ": " + uri, e );
        }
        return null;
    }

    @Override
    public int update( Uri uri, ContentValues contentvalues, String s, String[] as ) {
        return 0;
    }

    @Override
    public int delete( Uri uri, String s, String[] as ) {
        return 0;
    }

    @Override
    public Uri insert( Uri uri, ContentValues contentvalues ) {
        return null;
    }
}
