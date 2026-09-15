package com.ghostsq.commander.https;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;

import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.MultiStatusResponse;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;

class ListEngine extends PropFinder {
    private final URI baseURI;
    private final String pass_back_on_done;
    private SearchProps sq = null;
    private ArrayList<DavItem> foundItems;

    ListEngine( Uri uri, WebDAVAdapter owner, SearchProps sq, String pass_back_on_done ) {
        super( owner );
        baseURI = URI.create( Utils.mbAddSl( uri.toString() ) );
        if( sq != null ) {
            this.sq = sq;
            this.sBaseUri = uri.toString(); //!!!!!!!!!!!!!!!!!!!!!!!!!!!! to settle!!!!!!!!!!111
        }
        this.pass_back_on_done = pass_back_on_done;
    }

    @Override
    public void run() {
        int ret;
        if( sq != null ) {
            try {
                createClient();
                foundItems = new ArrayList<DavItem>();
                searchInDirectory( baseURI );
                ret = Commander.OPERATION_COMPLETED;
                client.close();
            } catch( Exception e ) {
                ret = Commander.OPERATION_FAILED;
                Log.e( TAG, sBaseUri, e );
            }
        } else {
            foundItems = getDavItemsList( baseURI );
            if( foundItems != null )
                ret = Commander.OPERATION_COMPLETED;
            else if( needRelogin ) {
                sendLoginReq( errMsg, owner.getCredentials(), pass_back_on_done );
                return;
            } else
                ret = Commander.OPERATION_FAILED;
        }
        sendProgress( errMsg, ret, pass_back_on_done );
    }

    public CommanderAdapter.Item[] getItems() {
        if( foundItems == null ) return null;
        return getItems( foundItems );
    }

    private void searchInDirectory( URI dir_uri ) throws Exception {
        String base_path = Utils.mbAddSl( baseURI.getPath() );
        String cur_path = Utils.mbAddSl( dir_uri.getPath() );
        String rel_pfx = cur_path.substring( base_path.length() );
        long cur_time = System.currentTimeMillis();
        if( cur_time - progress_last_sent > DELAY ) {
            progress_last_sent = cur_time;
            sendProgress( cur_path, progress = 0 );
        }

        // TODO: may be try DEPTH_INFINITY first and if that fails do the recurse
        // what WebDAV server/daemon actually supports that?????????? lighttpd does not!
        MultiStatusResponse[] msr = super.propFind( dir_uri, DavConstants.DEPTH_1 );
        if( msr == null ) return;
        Log.d( TAG, "Responses: " + msr.length );

        for( MultiStatusResponse r : msr ) {
            if( r == null ) continue;
            String href = r.getHref();
            if( cur_path != null ) {
                Uri href_uri = Uri.parse( href );
                String href_path = Utils.mbAddSl( href_uri.getPath() );
                if( cur_path.equals( href_path ) ) continue;    // this dir's item
            }
            DavItem di = new DavItem( href, r.getProperties( 200 ) );
            if( !sq.olo && di.dir ) {
                URI subdir_uri = di.getURI( sBaseUri );
                searchInDirectory( subdir_uri );
            }
            if( !Utils.str( di.name ) ) continue;
            if( !sq.match( di ) ) continue;
            if( sq.content != null && !di.dir && !searchInContent( di ) )
                continue;
            di.setPrefix( rel_pfx );
            foundItems.add( di );
        }
        sendProgress( cur_path, 100 );
    }

    private boolean searchInContent( DavItem di ) {
        Uri u = di.getUri( sBaseUri );
        InputStream is = owner.getContent( u, 0 );
        return super.searchInContent( is, di.size, di.name, sq.content );
    }
}
