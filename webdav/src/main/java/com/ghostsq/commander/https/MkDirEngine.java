package com.ghostsq.commander.https;

import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.HttpStatus;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.jackrabbit.webdav.client.methods.HttpMkcol;

class MkDirEngine extends WebDAVEngineBase {
    private String  url; 
    public MkDirEngine( WebDAVAdapter owner, String url ) {
        super( owner );
        this.url = url;
        Log.d( TAG, "Creating " + url );
    }
    
    @Override
    public void run() {
        try {
            super.getClient();
            if( client == null ) throw new Exception();
            HttpMkcol mcm = new HttpMkcol( url );
            CloseableHttpResponse chr = client.execute( mcm );
            StatusLine sl = chr.getStatusLine();
            int st_code = sl.getStatusCode();
            if( st_code == HttpStatus.SC_OK || st_code == HttpStatus.SC_CREATED ) {
                sendProgress( "", Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
                return;
            }
            error( sl.getReasonPhrase() );
        } catch( Exception e ) {
            error( e.getLocalizedMessage() );
        } finally {
            finalize();
        }
        sendResult( owner.ctx.getString( Utils.RR.cant_md.r(), url ) );
    }
}
