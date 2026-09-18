package com.ghostsq.commander.https;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Utils;

import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertySet;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class DavItem extends Item {
    private static final String TAG = "DavItem";
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat( "EEE, d MMM yyyy HH:mm:ss Z",  Locale.ENGLISH );
    public  String content_type;
    private URI    uri_c;
    
    DavItem( String href, DavPropertySet ps ) {
        if( href == null ) return;
        try {
            this.origin = href;
            if( ps != null ) {
                DavProperty<?> dnm = ps.get( DavPropertyName.DISPLAYNAME );
                DavProperty<?> rtp = ps.get( DavPropertyName.RESOURCETYPE );
                DavProperty<?> lmp = ps.get( DavPropertyName.GETLASTMODIFIED );
                DavProperty<?> clp = ps.get( DavPropertyName.GETCONTENTLENGTH );
                DavProperty<?> ctp = ps.get( DavPropertyName.GETCONTENTTYPE );
                if( dnm != null )
                    this.name = (String)dnm.getValue();
                this.dir = rtp != null && rtp.getValue() != null;
                if( lmp != null )
                    this.date = dateFormat.parse( (String)lmp.getValue() );
                if( !this.dir && clp != null && clp.getValue() != null )
                    this.size = Long.parseLong( (String)clp.getValue() );
                if( ctp != null )
                    content_type = (String)ctp.getValue();
            }
            if( !Utils.str(this.name) ) {
                Uri iu = Uri.parse( href );
                List<String> pss = iu.getPathSegments();
                this.name = pss.size() == 0 ? "/" : pss.get( pss.size() - 1 );
            }
        } catch( Exception e ) {
            Log.e( TAG, href, e );
        }
    }

    Uri getUri( String s_base_uri ) {
        if( super.uri == null ) {
            s_base_uri = Utils.mbAddSl( s_base_uri );
            if( origin == null ) {
                uri = Uri.parse( s_base_uri + getPath() );
            } else {
                String href = (String)origin;
                if( href.startsWith( "http" ) )
                    uri = Uri.parse( href );
                else
                    uri = Uri.parse( s_base_uri ).buildUpon().query( null ).encodedPath( href ).build();
            }
        }
        return uri;
    }

    URI getURI( String s_base_uri ) {
        if( this.uri_c == null ) {
            s_base_uri = Utils.mbAddSl( s_base_uri );
            if( origin == null ) {
                uri_c = URI.create( s_base_uri + getPath() );
            } else {
                String href = (String)origin;
                if( href.startsWith( "http" ) )
                    uri_c = URI.create( href );
                else {
                    try {
                        // don't use URIBuilder, it's ruins escaping and performance
                        int ps = s_base_uri.indexOf( '/', 8 );
                        String s_item_uri = s_base_uri.substring( 0, ps ) + href;
                        uri_c = URI.create( s_item_uri );
                    } catch( Exception e ) {
                        Log.e( TAG, this.toString(), e );
                    }
                }
            }
        }
        return uri_c;
    }

}
