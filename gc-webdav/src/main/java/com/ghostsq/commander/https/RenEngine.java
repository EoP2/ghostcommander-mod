package com.ghostsq.commander.https;


import android.net.Uri;

import com.ghostsq.commander.utils.Utils;

import org.apache.jackrabbit.webdav.client.methods.HttpMove;

import java.net.URI;

class RenEngine extends WebDAVEngineBase {
    private DavItem item;
    private Uri    old_uri, new_uri;
    private String new_name;
    
    public RenEngine( WebDAVAdapter a, DavItem item, String new_name ) {
        super( a );
        this.item = item;
        this.new_name = new_name;
    }
    @Override
    public void run() {
        try {
            super.getClient();
            URI old_uri = item.getURI( owner.getUri().toString() );
            if( old_uri == null ) return;
            String s_uri = old_uri.toString();
            if( s_uri == null ) return;
            int sl_pos = s_uri.lastIndexOf( '/', s_uri.length() - 2 );
            if( sl_pos < 0 ) return;
            String s_new = s_uri.substring( 0, sl_pos + 1 ) + Utils.escapeName( new_name ).replaceAll( "<", "%3C" ).replaceAll( ">", "%3E" ).replaceAll( " ", "%20" );
            URI new_uri = URI.create( s_new );
            HttpMove hm = new HttpMove( old_uri, new_uri, false );
            client.execute( hm );
        } catch( Exception e ) {
            String msg = e.getMessage();
            if( msg != null ) error( msg );
            error( owner.ctx.getString( Utils.RR.rename_err.r() ) );
        } finally {
        }
        sendResult( "" );
    }
}
