package com.ghostsq.commander;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import static android.provider.MediaStore.MediaColumns;

public class FileProvider extends ContentProvider {
    private static final String TAG = "FileProvider";
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".FileProvider";
    private int  imgWidth = -1, imgHeight = -1, uriHash = 0;

    public static Uri makeURI( String path ) {
        if( path == null ) return null;
        int ls = path.lastIndexOf( '/' );
        String dir, name;
        dir = path.substring( 0, ls );
        name= path.substring( ls+1 );
        Uri.Builder ub = new Uri.Builder();
        String encoded = Base64.encodeToString( dir.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING );
        ub.scheme( ContentResolver.SCHEME_CONTENT ).authority( AUTHORITY )
            .appendPath( "FS" ).appendPath( String.valueOf( encoded ) ).appendPath( name );
        return ub.build(); 
    }

    public static boolean canProvide( Uri uri ) {
        if( uri == null ) return false;
        if( !AUTHORITY.equals( uri.getAuthority() ) ) return false;
        return true;
    }

    public static String getPath( Uri uri ) {
        if( !canProvide( uri ) )
            return null;
        List<String> ps = uri.getPathSegments();
        if( ps == null || ps.size() < 3 ) return null;
        if( !"FS".equals( ps.get( 0 ) ) ) return null;
        byte[] bytes = Base64.decode( ps.get( 1 ), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING );
        if( bytes == null ) return null;
        String dir = new String( bytes );
        return Utils.mbAddSl( dir ) + ps.get( 2 );
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType( Uri uri ) {
        Log.d( TAG, "getType() " );
        String path = getPath( uri );
        if( path == null )
            return null;
        String ext  = Utils.getFileExt( path );
        String mime = Utils.getMimeByExt( ext );
        if( !Utils.str(mime) || Utils.MIME_ALL.equals( mime ) )
            mime = "application/octet-stream";
        return mime;
    }

    @Override
    public Cursor query( Uri uri, String[] fields, String sel, String[] sel_args, String sort ) {
        return query( uri, fields, sel, sel_args, sort, null );
    }

    @Override
    public Cursor query( Uri uri, String[] fields, String sel, String[] sel_args, String sort, CancellationSignal cancellation ) {
        try {
            Log.d( TAG, "query() " + Utils.join( fields, "," ) );
            String path = getPath( uri );
            if( path == null )
                throw new RuntimeException( "Unsupported URI" );
            if( fields == null || fields.length == 0) {
                fields = new String [] {
                    MediaColumns.DATA,
                    MediaColumns.MIME_TYPE,
                    MediaColumns.DISPLAY_NAME,
                    MediaColumns.TITLE,
                    MediaColumns.SIZE };
            } 
            MatrixCursor c = new MatrixCursor( fields );
            MatrixCursor.RowBuilder row = c.newRow();
            File f = new File( path );
            if( !f.exists() || !f.isFile() )
                throw new RuntimeException( "No such file: " + path );
            
            for( String col : fields ) {
                if( MediaColumns.DATA.equals( col ) ) {
                    row.add( f.getAbsolutePath() );
                } else if( MediaColumns.MIME_TYPE.equals( col ) ) {
                    row.add( getType( uri ) );
                } else if( MediaColumns.DISPLAY_NAME.equals( col ) ) {
                    Log.v( TAG, "name: " + f.getName() );
                    row.add( f.getName() );
                } else if( MediaColumns.TITLE.equals( col ) ) {
                    Log.v( TAG, "title: " + f.getName() );
                    row.add( f.getName() );
                } else if( MediaColumns.SIZE.equals( col ) ) {
                    row.add( f.length() );
                } else if( MediaColumns.WIDTH.equals( col ) ||
                           MediaColumns.HEIGHT.equals( col ) ) {
                    int uri_hc = uri.hashCode();
                    if( imgHeight < 0 || imgWidth < 0 || uriHash != uri_hc ) {
                        BitmapFactory.Options bfo = new BitmapFactory.Options();
                        bfo.inJustDecodeBounds = true;
                        bfo.outWidth = 0;
                        bfo.outHeight = 0;
                        Bitmap bitmap = BitmapFactory.decodeFile( f.getAbsolutePath(), bfo );
                        imgHeight = bfo.outHeight;
                        imgWidth = bfo.outWidth;
                        uriHash = uri_hc;
                    }
                    Log.v( TAG, "w: " + imgWidth + ", h: " + imgHeight );
                    row.add( MediaColumns.WIDTH.equals( col ) ? imgWidth : imgHeight );
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

    @Override
    public ParcelFileDescriptor openFile( Uri uri, String access_mode ) throws FileNotFoundException {
        try {
            String path = getPath( uri );
            if( path == null )
                throw new RuntimeException( "Unsupported URI" );
            File file = new File( path );
            if( !file.exists() ) {
                if( access_mode != null && access_mode.contains( "w" ) )
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
