package com.ghostsq.commander.adapters;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;

public class ReceiveEngine extends Engine {
     Context ctx;
     Uri from;
     ArrayList<Uri> from_multi;
     String sbj, text;
     CommanderAdapter rcp;
     IReceiver receiver;

     public ReceiveEngine( Context ctx, CommanderAdapter rcp ) {
         this.ctx = ctx;
         this.rcp = rcp;
     }
     public void setSourceUri( Uri from ) {
         this.from = from;
     }
     public void setSourceUris( ArrayList<Uri> from ) {
         from_multi = from;
     }
     public void setSourceText( String sbj, String text ) {
         this.sbj = sbj;
         this.text = text;
     }
     @Override
     public void run() {
         Cursor cursor = null;
         try {
             receiver = rcp.getReceiver( rcp.getUri() );
             if( receiver == null ) {
                 sendProgress( ctx.getString( R.string.not_supported ), Commander.OPERATION_FAILED );
                 return;
             }
             sendProgress( null, Commander.OPERATION_STARTED );
             int ok_cnt = 0;
             if( from_multi != null ) {
                 for( Uri u : from_multi ) {
                     if( fromUri( u ) )
                         ok_cnt++;
                 }
             } else if( from != null ) {
                 if( fromUri( from ) )
                     ok_cnt++;
             } else if( text != null ) {
                 if( asText() )
                     ok_cnt++;
             }
             sendResult( Utils.getOpReport( ctx, ok_cnt, R.string.copied ) );
         } catch( Exception e ) {
             Log.e( TAG, "" + from, e );
             sendProgress( ctx.getString( R.string.failed ) + e, Commander.OPERATION_FAILED_REFRESH_REQUIRED );
         }
     }

     private boolean asText() {
         String name = null;
         try {
             int size = -1;
             InputStream is = null;
             byte[] bb = text.getBytes();
             is = new ByteArrayInputStream( bb );
             size = bb.length;
             name = sbj;
             if( !Utils.str( name ) ) name = text.substring( 0, 10 ) + ".txt";
             name = name.replaceAll( "[*|<>/]", "" );
             Log.d( TAG, "Saving shared content: " + name + " of size " + size );
             return super.copyStreamToReceiver( ctx, receiver, is, name, size, new Date() );
         }
         catch( Exception e ) {
             sendProgress( ctx.getString( R.string.failed ) + e, Commander.OPERATION_FAILED_REFRESH_REQUIRED );
             Log.e( TAG, "" + sbj, e );
             return false;
         }
     }

     private boolean fromUri( Uri u ) {
         if( u == null ) return false;
         String s = u.getScheme();
         if( ContentResolver.SCHEME_CONTENT.equals( s ) )
             return fromContent( u );
         if( "file".equals( s ) )
             return fromFile( u );
         return false;
     }

     private boolean fromFile( Uri u ) {
         try {
             File f = new File( u.getPath() );
             if( !f.exists() ) {
                 error( ctx.getString( R.string.not_accs, f.toString() ) );
                 return false;
             }
             InputStream is = new FileInputStream( f );
             return super.copyStreamToReceiver( ctx, receiver, is, f.getName(), f.length(), new Date( f.lastModified() ) );
         }
         catch( Exception e ) {
             Log.e( TAG, "" + u, e );
             return false;
         }
     }

     private boolean fromContent( Uri u ) {
         if( u == null ) return false;
         Cursor cursor = null;
         try {
             String name = null;
             int size = -1;
             Date lm = null;
             InputStream is = null;
             ContentResolver cr = ctx.getContentResolver();
             String[] projection = getProjection( u );
             cursor = cr.query( u, projection, null, null, null );
             if( cursor == null ) {
                 Log.e( TAG, "No content record for " + u );
                 throw new Exception();
             }
             cursor.moveToFirst();
             int dni = cursor.getColumnIndex( MediaStore.MediaColumns.DISPLAY_NAME );
             name = cursor.getString( dni );
             if( !Utils.str( name ) ) name = sbj;
             if( !Utils.str( name ) ) name = text;
             if( !Utils.str( name ) ) name = u.getLastPathSegment();
             if( !Utils.str( name ) )
                 return false;
             name = name.replaceAll( "[*|<>/]", "" );
             int szi = cursor.getColumnIndex( MediaStore.MediaColumns.SIZE );
             if( szi < 0 ) throw new Exception( "Unknown size!" );
             size = cursor.getInt( szi );
             int dti = cursor.getColumnIndex( MediaStore.MediaColumns.DATE_MODIFIED );
             if( dti >= 0 ) {
                 int dt = cursor.getInt( dti );
                 lm = dt != 0 ? new Date( dt * 1000L ) : new Date();
             } else {
                 dti = cursor.getColumnIndex( "last_modified" );
                 if( dti >= 0 ) {
                     long dt = cursor.getLong( dti );
                     if( dt > 0 ) {
                         if( dt < 1000000000000L )
                             dt *= 1000;
                         lm = new Date( dt );
                     } else
                        lm = new Date();
                 }
             }
             is = cr.openInputStream( u );
             if( lm == null ) lm = new Date();
             Log.d( TAG, "Saving shared content: " + name + " of size " + size );
             return super.copyStreamToReceiver( ctx, receiver, is, name, size, lm );
         }
         catch( Exception e ) {
             Log.e( TAG, "" + u, e );
             return false;
         }
         finally {
             if( cursor != null )
                 cursor.close();
         }
     }

    private static String[] getProjection( Uri u ) {
        if( !"com.android.externalstorage.documents".equals( u.getAuthority() ) )
            return null; // third party senders may not provide some fields.
        return new String[] {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        };
    }
}

