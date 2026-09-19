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
                // RFC 3986 §5 reference resolution correctly handles all three shapes
                // a server may send back: a full "http(s)://..." URL, a root-relative
                // absolute path ("/dav/x/y/"), or a path relative to the CURRENT
                // collection ("y/"). The previous encodedPath(href) call REPLACED the
                // whole path with href verbatim, so a relative href silently dropped
                // the shared prefix (e.g. "/dav/movies/") and pointed at the wrong
                // location on the same host/port - which is what produced 401s only
                // for items reached by clicking, never for a manually typed full path.
                uri = Uri.parse( URI.create( s_base_uri ).resolve( href ).toString() );
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
                try {
                    uri_c = URI.create( s_base_uri ).resolve( href );
                } catch( Exception e ) {
                    Log.e( TAG, this.toString(), e );
                }
            }
        }
        return uri_c;
    }

}
