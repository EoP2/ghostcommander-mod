package com.ghostsq.commander.adapters;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.utils.Utils;

import java.io.InputStream;
import java.security.MessageDigest;

public class ChecksumEngine extends Engine {
    private Context ctx;
    private CommanderAdapter ca;
    private Uri uri;
    private CommanderAdapter.Item item;
    private int what;

    public ChecksumEngine( Context ctx, CommanderAdapter ca, Uri uri, CommanderAdapter.Item item, int what ) {
        this.ctx = ctx;
        this.ca = ca;
        this.uri = uri;
        this.item = item;
        this.what = what;
        setEngineName( null );
    }

    @Override
    public void run() {
        InputStream in = null;
        try {
            String algorithm = null;
            if( what == R.id.md5 )
                    algorithm = "MD5";
            else
            if( what == R.id.sha1 )
                algorithm = "SHA-1";
            else
            if( what ==  R.id.sha256 )
                algorithm = "SHA-256";
            sendProgress( null, Commander.OPERATION_STARTED );
            long progress_last_sent = System.currentTimeMillis();
            if( algorithm == null ) {
                sendProgress( "Unknown sum type", Commander.OPERATION_FAILED );
                return;
            }
            in = ca.getContent( uri );
            if( in == null ) {
                sendProgress( "Can't get the content bytes", Commander.OPERATION_FAILED );
                return;
            }
            MessageDigest digester = MessageDigest.getInstance( algorithm );
            int buf_size = Math.max( (int)Math.min( item.size / 10, (long)524288 ), 8192 );
            byte[] bytes = new byte[buf_size];
            double conv = 100. / ( item.size == 0 ? 100 : item.size );
            long start_time = System.currentTimeMillis();
            for( long loaded = 0; loaded < item.size; ) {
                int n = in.read( bytes );
                if( n <= 0 ) break;
                if( isStopReq() ) {
                    sendProgress( ctx.getString( R.string.canceled ), Commander.OPERATION_FAILED );
                    return;
                }
                digester.update( bytes, 0, n );
                loaded += n;
                if( item.size == 0 && loaded > 0 )
                    conv = 100. / loaded;
                long cur_time = System.currentTimeMillis();
                long time_delta = cur_time - start_time;
                int speed = time_delta > 0 ? (int)(n * MILLI / time_delta ) : 0;
                if( cur_time - progress_last_sent > DELAY ) {
                    sendProgress( algorithm, (int)( loaded * conv ), -1, speed );
                    progress_last_sent = cur_time;
                }
                start_time = cur_time;
                if( isStopReq() ) {
                    sendProgress( ctx.getString( R.string.canceled ), Commander.OPERATION_FAILED );
                    return;
                }
            }
            byte[] digest = digester.digest();
            String report = ctx.getString( R.string.sz_file, item.name ) +
                    "<br/><br/>" + algorithm + ": " + Utils.toHexString( digest, null );
            sendReport( report );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        } finally {
            if( in != null )
                ca.closeStream( in );
        }
    }
}
