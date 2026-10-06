package com.ghostsq.commander.https;

import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import org.apache.jackrabbit.webdav.client.methods.HttpCopy;
import org.apache.jackrabbit.webdav.client.methods.HttpDelete;
import org.apache.jackrabbit.webdav.client.methods.HttpMove;

import shaded.org.apache.http.HttpStatus;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpUriRequest;

import java.io.IOException;
import java.net.URI;

/**
 * Copies or moves items to another folder of the SAME WebDAV server using the COPY and MOVE
 * methods. The server does the job, so nothing is downloaded to the device and uploaded back.
 *
 * Safety rules:
 *  - the source is changed only by the server's MOVE (it is atomic for an item). The only thing
 *    this code removes itself is a source folder emptied by merging its content (see mergeDir());
 *  - a folder is never overwritten, because that would delete its current content on the server.
 *    If it exists at the destination, the content is merged, as the other adapters do;
 *  - a file is overwritten only after the user has confirmed that;
 *  - an item is never sent onto itself or into itself.
 *
 * If the server can't COPY at all, the generic way (download and upload) is used instead.
 * That's why this class extends CopyFromEngine: run() of the parent is that generic way.
 */
class SameServerEngine extends CopyFromEngine {
    private final Commander     cmdr;
    private final WebDAVAdapter dest;
    private final Item[]        todo;
    private final boolean       is_move;
    private int     done = 0;               // how many items have been moved or copied
    private int     requests = 0;           // how many COPY or MOVE requests have been sent
    private boolean quit = false;           // the operation can't go on
    private boolean unsupported = false;    // the server answered that it can't COPY

    SameServerEngine( Commander c, WebDAVAdapter src, WebDAVAdapter dest, Item[] list, boolean move ) {
        super( c, src, list, move, dest );
        this.cmdr = c;
        this.dest = dest;
        this.todo = list;
        this.is_move = move;
    }

    /**
     * @return true if both adapters are connected to the same WebDAV server as the same user,
     *         so the server itself is able to copy or move an item from one folder to another
     */
    static boolean isSameServer( WebDAVAdapter a, WebDAVAdapter b ) {
        try {
            Uri ua = a.getUri(), ub = b.getUri();
            if( ua == null || ub == null || !Utils.str( ua.getHost() ) )
                return false;
            if( !ua.getHost().equalsIgnoreCase( ub.getHost() ) ||
                !eqIgnoreCase( ua.getScheme(), ub.getScheme() ) ||
                portOf( ua ) != portOf( ub ) )
                return false;
            Credentials ca = a.getCredentials(), cb = b.getCredentials();
            if( ca == null || cb == null )
                return ca == cb;
            return Utils.equals( ca.getUserName(), cb.getUserName() );
        } catch( Exception e ) {
            Log.e( "SameServerEngine", "isSameServer()", e );
        }
        return false;
    }

    @Override
    public void run() {
        try {
            getClient();
            if( client == null )
                throw new IOException( "No HTTP client" );
            Uri du = Utils.updateUserInfo( dest.getUriNoQuery(), null );
            if( du == null || !Utils.str( du.getHost() ) )
                throw new IOException( "Invalid destination" );
            // the path is encoded again from its decoded form, so it's a valid URL however the Uri was written
            process( todo, Utils.mbAddSl( Uri.encode( du.getPath(), "/" ) ) );
        } catch( InterruptedException e ) {
            error( owner.ctx.getString( Utils.RR.interrupted.r() ) );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
            error( owner.ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
        }
        if( unsupported ) {
            // The server can't COPY, and nothing has been touched yet: do it the generic way
            errMsg = null;
            super.run();
            return;
        }
        sendResult( Utils.getOpReport( owner.ctx, done, is_move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
    }

    /**
     * @param dst_path the encoded path of the destination folder, starts and ends with a slash
     * @return true if all the items have been handled (for a move: have left the source folder)
     */
    private boolean process( Item[] l, String dst_path ) throws InterruptedException {
        boolean all = true;
        int num = l.length;
        for( int i = 0; i < num && !quit; i++ ) {
            if( stop || isInterrupted() ) {
                error( owner.ctx.getString( Utils.RR.interrupted.r() ) );
                quit = true;
                return false;
            }
            DavItem item = (DavItem)l[i];
            sendProgress( item.name, done, i * 100 / num );
            try {
                if( !processItem( item, dst_path ) )
                    all = false;
            } catch( InterruptedException e ) {
                throw e;
            } catch( Exception e ) {
                Log.e( TAG, item.name, e );
                error( err( item.name, e.getLocalizedMessage() ) );
                quit = true;
                all = false;
            }
        }
        return all && !quit;
    }

    private boolean processItem( DavItem item, String dst_path ) throws Exception {
        URI src = item.getURI( sBaseUri );
        if( src == null )
            throw new IOException( "Bad URL" );
        // The name at the destination is the last segment of the source URL as the server wrote it.
        // It is escaped already, so there is nothing to break by decoding and encoding it again.
        String raw = trimSl( src.getRawPath() );
        String enc_name = raw.substring( raw.lastIndexOf( '/' ) + 1 );
        if( enc_name.length() == 0 )
            throw new IOException( "Can't handle the root" );
        // the authority is taken from the source, so it's exactly what the request itself is sent to
        URI dst = URI.create( src.getScheme() + "://" + authority( src ) + dst_path + enc_name + ( item.dir ? "/" : "" ) );

        String why = checkTarget( src, dst );
        if( why != null ) {
            error( err( item.name, why ) );
            return false;
        }
        StatusLine sl = transfer( src, dst, false );
        int code = sl.getStatusCode();
        if( isDone( code ) ) {
            done++;
            return true;
        }
        if( !is_move && requests == 1 && isUnsupported( code ) ) {
            unsupported = true;     // see run()
            quit = true;
            return false;
        }
        if( code != HttpStatus.SC_PRECONDITION_FAILED )
            return failed( item, sl );

        // 412: something with this name exists at the destination already
        if( samePath( src, dst, true ) ) {
            // a case insensitive server may take it for the very same item. Never risk it.
            error( err( item.name, "Source and destination are the same" ) );
            return false;
        }
        DavItem existing = stat( dst );
        if( existing == null )
            return failed( item, sl );
        if( item.dir ) {
            if( !existing.dir ) {
                error( err( item.name, "A file with the same name exists" ) );
                return false;
            }
            return mergeDir( item, src, dst );
        }
        if( existing.dir ) {
            error( err( item.name, "A folder with the same name exists" ) );
            return false;
        }
        int res = askOnFileExist( owner.ctx.getString( Utils.RR.file_exist.r(), item.name ), cmdr );
        if( res == Commander.REPLACE ) {
            sl = transfer( src, dst, true );
            if( isDone( sl.getStatusCode() ) ) {
                done++;
                return true;
            }
            return failed( item, sl );
        }
        if( res == Commander.ABORT )
            quit = true;    // askOnFileExist() has reported that
        return false;       // skipped: it stays where it was
    }

    /**
     * The destination folder exists already, so the content is handled item by item.
     * When moving, the source folder is removed only after everything has been moved out of it
     * and a fresh listing has confirmed that nothing else is left inside.
     */
    private boolean mergeDir( DavItem item, URI src, URI dst ) throws Exception {
        Item[] kids = getItems( src );
        if( kids == null ) {
            quit = true;    // the reason has been reported already
            return false;
        }
        if( !process( kids, dst.getRawPath() ) )
            return false;
        if( !is_move )
            return true;
        Item[] rest = getItems( src );
        if( rest == null ) {
            quit = true;
            return false;
        }
        if( rest.length > 0 ) {
            error( err( item.name, "The folder is not empty, so it was left in place" ) );
            return false;
        }
        int code = exec( new HttpDelete( src ) ).getStatusCode();
        if( code == HttpStatus.SC_OK || code == HttpStatus.SC_NO_CONTENT ) {
            done++;
            return true;
        }
        error( owner.ctx.getString( Utils.RR.cant_del.r(), item.name ) );
        quit = true;
        return false;
    }

    private StatusLine transfer( URI src, URI dst, boolean overwrite ) throws IOException {
        requests++;
        HttpUriRequest req;
        if( is_move )
            req = new HttpMove( src, dst, overwrite );
        else
            req = new HttpCopy( src, dst, overwrite, false );
        // jackrabbit leaves the header out for "T", but a server may take a missing one as "F"
        // (those based on golang's x/net/webdav do for MOVE), and the replacing would never work
        req.setHeader( "Overwrite", overwrite ? "T" : "F" );
        return exec( req );
    }

    private DavItem stat( URI uri ) {
        // a separate instance, because "does not exist" must not become an error of this operation
        PropFinder pf = new PropFinder( owner );
        pf.setClient( client );
        return pf.getDavItem( uri );
    }

    private StatusLine exec( HttpUriRequest req ) throws IOException {
        CloseableHttpResponse chr = client.execute( req );
        try {
            return chr.getStatusLine();
        } finally {
            chr.close();
        }
    }

    private boolean failed( DavItem item, StatusLine sl ) {
        error( err( item.name, sl.getStatusCode() + " " + sl.getReasonPhrase() ) );
        quit = true;
        return false;
    }

    private String err( String name, String why ) {
        return owner.ctx.getString( Utils.RR.rtexcept.r(), name, why );
    }

    private static boolean isDone( int code ) {
        return code == HttpStatus.SC_OK || code == HttpStatus.SC_CREATED || code == HttpStatus.SC_NO_CONTENT;
    }

    private static boolean isUnsupported( int code ) {
        return code == HttpStatus.SC_METHOD_NOT_ALLOWED || code == HttpStatus.SC_NOT_IMPLEMENTED || code == HttpStatus.SC_BAD_GATEWAY;
    }

    private String checkTarget( URI src, URI dst ) {
        if( samePath( src, dst, false ) )
            return "Source and destination are the same";
        if( trimSl( dst.getPath() ).startsWith( trimSl( src.getPath() ) + "/" ) )
            return is_move ? "Cannot move a folder into itself" : "Cannot copy a folder into itself";
        return null;
    }

    private static boolean samePath( URI a, URI b, boolean ignore_case ) {
        String pa = trimSl( a.getPath() ), pb = trimSl( b.getPath() );
        return ignore_case ? pa.equalsIgnoreCase( pb ) : pa.equals( pb );
    }

    private static String trimSl( String p ) {
        if( p == null ) return "";
        int e = p.length();
        while( e > 0 && p.charAt( e - 1 ) == '/' ) e--;
        return p.substring( 0, e );
    }

    private static String authority( URI u ) {
        String a = u.getRawAuthority();
        return a == null ? "" : a.substring( a.lastIndexOf( '@' ) + 1 );    // without user info
    }

    private static boolean eqIgnoreCase( String s1, String s2 ) {
        return s1 == null ? s2 == null : s1.equalsIgnoreCase( s2 );
    }

    private static int portOf( Uri u ) {
        int p = u.getPort();
        if( p >= 0 ) return p;
        return "http".equalsIgnoreCase( u.getScheme() ) ? 80 : 443;
    }
}