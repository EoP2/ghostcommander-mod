package com.ghostsq.commander.https;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.HttpHost;
import shaded.org.apache.http.HttpStatus;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.AuthCache;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpPut;
import shaded.org.apache.http.client.protocol.HttpClientContext;
import shaded.org.apache.http.entity.BufferedHttpEntity;
import shaded.org.apache.http.entity.InputStreamEntity;
import shaded.org.apache.http.impl.auth.BasicScheme;
import shaded.org.apache.http.impl.client.BasicAuthCache;
import shaded.org.apache.http.impl.client.CloseableHttpClient;
import org.apache.jackrabbit.webdav.client.methods.HttpDelete;
import org.apache.jackrabbit.webdav.client.methods.HttpMkcol;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;

public class Receiver implements IReceiver {
    private final static String TAG = "DAVReceiver";
    private WebDAVAdapter owner;
    private String sDestUri, destHost;
    private CloseableHttpClient client;

    Receiver( WebDAVAdapter a, Uri dest, CloseableHttpClient client ) {
        this.owner = a;
        destHost = dest.getHost();
        sDestUri = Utils.mbAddSl( dest.toString() );
        this.client = client;
    }

    private final URI getURI( String name ) {
        return URI.create( sDestUri + name );
    }

    @Override
    public OutputStream receive( String fn ) {
        HttpPut pm = new HttpPut( getURI( fn ) );
        try {
            AuthCache authCache = new BasicAuthCache();
            authCache.put( new HttpHost( destHost ), new BasicScheme() );
            final HttpClientContext http_context = HttpClientContext.create();
            http_context.setAuthCache(authCache);

            final PipedInputStream pis = new PipedInputStream();
            final PipedOutputStream pos = new PipedOutputStream( pis );
            new Thread(
               new Runnable(){
                  public void run(){
                    try {
                        InputStreamEntity ise = new InputStreamEntity( pis );
                        BufferedHttpEntity bhe = new BufferedHttpEntity( ise );
                        pm.setEntity( bhe );
                        CloseableHttpResponse chr = client.execute( pm, http_context );
                        StatusLine sl = chr.getStatusLine();
                        int st_code = sl.getStatusCode();
                        Log.d( TAG, "HTTP status=" + st_code + " " + sl.getReasonPhrase() );
                        if( st_code >= 400 ) {
                            Log.e( TAG, "Fail " + sl.getReasonPhrase() );
                        }
                    } catch( Exception e ) {
                        Log.e( TAG, "", e );
                    } finally {
                        pm.releaseConnection();
                    }
                  }
                }
              ).start();
            return pos;
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
        } catch( IOException e ) {
            Log.e( TAG, "", e );
        }
    }

    private DavItem getDavItem( URI uri ) {
        PropFinder pf = new PropFinder( owner );
        pf.setClient( this.client );
        return pf.getDavItem( uri );
    }

    @Override
    public Uri getItemURI( String name, boolean dir ) {
        try {
            DavItem item = getDavItem( getURI( name ) );
            if( item == null ) return null;
            return item.getUri( owner.getUri().toString() );
        } catch( Exception e ) {
            Log.e( TAG, name, e );
        }
        return null;
    }

    @Override
    public boolean isDirectory( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            DavItem item = getDavItem( URI.create( item_uri.toString() ) );
            if( item == null ) return false;
            return item.dir;
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public Uri makeDirectory( String new_dir_name ) {
        try {
            HttpMkcol mcm = new HttpMkcol( getURI( new_dir_name ) );
            CloseableHttpResponse chr = client.execute( mcm );
            StatusLine sl = chr.getStatusLine();
            int st_code = sl.getStatusCode();
            if( st_code != HttpStatus.SC_OK && st_code != HttpStatus.SC_CREATED )
                return null;
            return getItemURI( new_dir_name, true );
        } catch( Exception e ) {
            Log.e( TAG, new_dir_name, e );
        }
        return null;
    }

    @Override
    public boolean delete( Uri item_uri ) {
        try {
            if( item_uri == null ) return false;
            HttpDelete dm = new HttpDelete( URI.create( item_uri.toString() ) );
            CloseableHttpResponse chr = client.execute( dm );
            StatusLine sl = chr.getStatusLine();
            int st_code = sl.getStatusCode();
            if( st_code == HttpStatus.SC_OK )
                return true;
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public boolean setDate( Uri item_uri, java.util.Date timestamp ) {
        try {
            if( item_uri == null ) return false;
            // TODO how?????
        } catch( Exception e ) {
            Log.e( TAG, item_uri.toString(), e );
        }
        return false;
    }

    @Override
    public boolean done() {
        try {
            client.close();
            return true;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }
}
