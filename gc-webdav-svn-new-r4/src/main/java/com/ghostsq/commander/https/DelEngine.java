package com.ghostsq.commander.https;

import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.jackrabbit.webdav.client.methods.HttpDelete;

import java.net.URI;

class DelEngine extends PropFinder {
    private int so_far = 0;
    private Item[] list;
    
    public DelEngine( WebDAVAdapter a, Item[] list ) {
        super( a );
        this.list = list;
    }
    @Override
    public void run() {
        try {
            URI uri = URI.create( Utils.mbAddSl( owner.getUri().toString() ) );
            int total;
            total = deleteFiles( uri, list );
            sendResult( total > 0 ? "Deleted" : "Nothing was deleted" );
        } catch( Throwable e ) {
            sendProgress( owner.ctx.getString( Utils.RR.failed.r() ) + e.getMessage(), Commander.OPERATION_FAILED );
            Log.e( TAG, "", e );
        } finally {
            finalize();
        }
    }

// FIXME FAILED TO DELETE FILE WITH SPACES!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11


    private final int deleteFiles( URI base_uri, Item[] l ) throws Exception {
        if( l == null ) return 0;
        
        super.getClient();
        
        int cnt = 0;
        int num = l.length;
        double conv = 100./(double)num;
        for( int i = 0; i < num; i++ ) {
            if( stop || isInterrupted() )
                throw new Exception( owner.ctx.getString( Utils.RR.interrupted.r() ) );
            DavItem item = (DavItem)l[i];
            sendProgress( owner.ctx.getString( Utils.RR.deleting.r(), item.name ), so_far, (int)(i * conv) );
            URI item_uri = item.getURI( super.sBaseUri );
            if( item.dir ) {
                Item[] subItems = super.getItems( item_uri );
                if( subItems == null ) break;
                if( subItems.length > 0 )
                    cnt += deleteFiles( item_uri, subItems );
                HttpDelete dm = new HttpDelete( item_uri );
                client.execute( dm );
            } else {
                HttpDelete dm = new HttpDelete( item_uri );
                try {
                    CloseableHttpResponse chr = client.execute( dm );
                    StatusLine sl = chr.getStatusLine();
                    int scode = sl.getStatusCode();
/*
                    if( scode == 207 ) {  // 207 ::= MultiStatus
                        MultiStatus ms = dm.getResponseBodyAsMultiStatus();
                        MultiStatusResponse[] msr = ms.getResponses();
                        if( msr != null && msr.length > 0 ) {
                            error( msr[0].getResponseDescription() );
                            return cnt;
                        }
                    }

 */
                    if( scode >= 400 ) {
                        error( sl.getReasonPhrase() );
                        return cnt;
                    }
                } catch( Exception e ) {
                    error( owner.ctx.getString( Utils.RR.fail_del.r(), item_uri.toString() ) );
                    break;
                }
            }
            cnt++;
        }
        so_far += cnt; 
        return cnt;
    }
}
