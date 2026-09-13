package com.ghostsq.commander.https;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.client.methods.HttpPropfind;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import javax.net.ssl.SSLHandshakeException;

class PropFinder extends WebDAVEngineBase {
    protected String  sBaseUri;
    protected boolean needRelogin = false;

    PropFinder( WebDAVAdapter owner ) {
        super( owner );
        sBaseUri = owner.getUri().toString();    // FIXME !!!!!!!!!!!!!!! not too graceful!!!!!! to think through!!!!!!!!!!!!!!!!!!!!!!!!!11
    }

    /*
        @param depth - 0 for an individual item, 1 for directory, DEPTH_INFINITY may not be supported, be careful!
     */
    protected final MultiStatusResponse[] propFind( URI operURI, int depth ) {
        HttpPropfind hpf = null;
        CloseableHttpResponse chr = null;
        try {
            boolean close_after = client == null;
            if( close_after )
                client = owner.createClient( operURI.getHost() );
            MultiStatusResponse[] responses = null;
            DavPropertyNameSet props = new DavPropertyNameSet();
            props.add( DavPropertyName.DISPLAYNAME );
            props.add( DavPropertyName.RESOURCETYPE );
            props.add( DavPropertyName.GETLASTMODIFIED );
            props.add( DavPropertyName.GETCONTENTLENGTH );
            props.add( DavPropertyName.GETCONTENTTYPE );
            hpf = new HttpPropfind( operURI, props, depth );
            Log.d( TAG, operURI.toString() );
// an example:  https://github.com/apache/httpcomponents-client/blob/4.5.x/httpclient/src/examples/org/apache/http/examples/client/ClientWithResponseHandler.java

//            AllowAllHostnameVerifier v = AllowAllHostnameVerifier.INSTANCE;   not in the android version!
            chr = client.execute( hpf );
/*  DEBUG!
                 BufferedReader br = new BufferedReader(new InputStreamReader(chr.getEntity().getContent()));
                 String result = br.lines().collect( Collectors.joining("\n"));
                 Log.v( TAG, result );
                 br = null;
*/
            MultiStatus multiStatus = hpf.getResponseBodyAsMultiStatus( chr );
            responses = multiStatus.getResponses();
            chr.close();
            if( close_after ) {
                Log.d( TAG, "Closing the client!" );
                client.close();
                client = null;
            }
            return responses;
        } catch( SSLHandshakeException e ) {
            Log.e( TAG, operURI.toString(), e );
            addError( e );
        } catch( DavException e ) {
            Log.e( TAG, operURI.toString(), e );
            int code = e.getErrorCode();
            String msg = e.getMessage();
            if( !Utils.str( msg ) && chr != null )
                msg = chr.getStatusLine().getReasonPhrase();
            error( code + " " + msg );
            if( code == 401 )
                needRelogin = true;
            return null;
        } catch( IllegalArgumentException e ) {
            error( owner.dav_res.getString( R.string.cant_cd, operURI.getPath() ) );
            if( chr != null ) {
                StatusLine sl = chr.getStatusLine();
                if( sl != null )
                    error( sl.getReasonPhrase() );
            }
        } catch( Throwable e ) {
            Log.e( TAG, operURI.toString(), e );
            addError( e );
        } finally {
            if( chr != null )
                try {
                    chr.close();
                } catch( IOException ioe ) {
                    Log.e( TAG, operURI.toString(), ioe );
                }
        }
        return null;
    }

    void addError( Throwable e ) {
        if( e == null )
            return;
        String e_msg = e.getMessage();
        if( e_msg != null ) {
            if( super.errMsg == null || !super.errMsg.contains( e_msg ) )
                error( e_msg );
        }
        Throwable cause = e.getCause();
        if( cause != null )
            addError( cause );
    }

    public DavItem getDavItem( URI uri ) {
        MultiStatusResponse[] msr = propFind( uri, DavConstants.DEPTH_0 );
        if( msr == null || msr.length == 0 ) return null;
        if( msr.length > 1 )
            Log.w( TAG, "More than one item for " + uri.toString() );
        MultiStatusResponse r = msr[0];
        if( r == null ) return null;
        return new DavItem( r.getHref(), r.getProperties( 200 ) );
    }

    protected ArrayList<DavItem> getDavItemsList( URI uri ) {
        MultiStatusResponse[] responses = propFind( uri, DavConstants.DEPTH_1 );
        if( responses == null ) return null;
        ArrayList<DavItem> al = new ArrayList<DavItem>();
        String uri_path = Utils.mbAddSl( uri.getPath() );
        for( MultiStatusResponse r : responses ) {
            if( r == null ) continue;
            String href = r.getHref();
            if( uri_path != null ) {
                Uri href_uri = Uri.parse( href );
                String href_path = Utils.mbAddSl( href_uri.getPath() );
                if( uri_path.equals( href_path ) ) continue;    // this dir's item
            }
            DavItem di = new DavItem( href, r.getProperties( 200 ) );
            if( !Utils.str( di.name ) ) continue;
            al.add( di );
        }
        return al;
    }

    public CommanderAdapter.Item[] getItems( URI uri ) {
        ArrayList<DavItem> al = getDavItemsList( uri );
        if( al == null ) return null;
        return getItems( al );
    }

    protected static CommanderAdapter.Item[] getItems( ArrayList<DavItem> al ) {
        if( al == null ) return null;
        CommanderAdapter.Item[] items = new CommanderAdapter.Item[al.size()];
        al.toArray( items );
        return items;
    }

/*
TODO delete
    public CommanderAdapter.Item[] getItems() {
        ArrayList<DavItem> al = getDavItemsList();
        CommanderAdapter.Item[] items = new CommanderAdapter.Item[al.size()];
        al.toArray( items );
        return items;
    }
    public DavItem[] getDavItems() {
        ArrayList<DavItem> al = getDavItemsList();
        DavItem[] items = new DavItem[al.size()];
        al.toArray( items );
        return items;
    }

 */
}
