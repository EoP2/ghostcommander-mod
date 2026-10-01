package com.ghostsq.commander.https;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import shaded.org.apache.http.Header;
import shaded.org.apache.http.HttpEntity;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpGet;
import shaded.org.apache.http.impl.client.CloseableHttpClient;

class DavSeekableStream extends InputStream {
    private final static String TAG = "WebDAV.Seek";
    private final static long LOCAL_SKIP_LIMIT = 256 * 1024;
    private final static Pattern CONTENT_RANGE = Pattern.compile( "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)" );

    private final CloseableHttpClient client;
    private final String url;

    private HttpGet get;
    private CloseableHttpResponse resp;
    private InputStream in;
    private long pos;
    private long total = -1;
    private boolean closed;
    private int  failures;

    DavSeekableStream( CloseableHttpClient client, String url, long startPos ) throws IOException {
        this.client = client;
        this.url = url;
        try {
            openAt( startPos );
        } catch( IOException e ) {
            close();
            throw e;
        } catch( RuntimeException e ) {
            close();
            throw e;
        }
    }

    private void openAt( long offset ) throws IOException {
        HttpGet g = new HttpGet( url );
        if( offset > 0 ) {
            g.setHeader( "Range", "bytes=" + offset + "-" );
            g.setHeader( "Accept-Encoding", "identity" );
        }

        long t0 = System.currentTimeMillis();
        CloseableHttpResponse r = client.execute( g );
        boolean ok = false;
        try {
            int code = r.getStatusLine().getStatusCode();
            InputStream s;
            long localSkip = 0;
            if( code == 416 && offset > 0 ) {
                s = new ByteArrayInputStream( new byte[0] );
            } else if( code == 200 || code == 206 ) {
                HttpEntity he = r.getEntity();
                if( he == null )
                    throw new IOException( "HTTP " + code + " without a body" );
                s = he.getContent();
                if( s == null )
                    throw new IOException( "HTTP " + code + " without a content stream" );
                if( code == 206 ) {
                    Header h = r.getFirstHeader( "Content-Range" );
                    Matcher m = h != null ? CONTENT_RANGE.matcher( h.getValue() ) : null;
                    if( m != null && m.find() ) {
                        long start = Long.parseLong( m.group( 1 ) );
                        if( start != offset )
                            throw new IOException( "Asked for offset " + offset + " but got " + h.getValue() );
                        if( !"*".equals( m.group( 3 ) ) )
                            total = Long.parseLong( m.group( 3 ) );
                    } else
                        Log.w( TAG, "206 without a usable Content-Range, assuming it starts at " + offset );
                } else {
                    long cl = he.getContentLength();
                    if( cl >= 0 )
                        total = cl;
                    localSkip = offset;
                    if( offset > 0 )
                        Log.w( TAG, "Server ignored Range at offset " + offset + ", skipping locally" );
                }
            } else
                throw new IOException( "HTTP " + code + " " + r.getStatusLine().getReasonPhrase() );

            if( localSkip > 0 )
                skipByReading( s, localSkip );

            if( offset > 0 || code != 200 )
                Log.i( TAG, "offset=" + offset + " -> HTTP " + code + " (" + ( System.currentTimeMillis() - t0 ) + "ms)" );

            dropCurrent();
            this.get = g;
            this.resp = r;
            this.in = s;
            this.pos = offset;
            ok = true;
        } finally {
            if( !ok ) {
                try { g.abort(); } catch( Exception ignored ) {}
                try { r.close(); } catch( IOException ignored ) {}
            }
        }
    }

    private void dropCurrent() {
        HttpGet g = this.get;
        CloseableHttpResponse r = this.resp;
        this.get = null;
        this.resp = null;
        this.in = null;
        if( g != null )
            try { g.abort(); } catch( Exception ignored ) {}
        if( r != null )
            try { r.close(); } catch( IOException ignored ) {}
    }

    private static void skipByReading( InputStream s, long n ) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long remaining = n;
        while( remaining > 0 ) {
            int r = s.read( buf, 0, (int)Math.min( buf.length, remaining ) );
            if( r < 0 ) break;
            remaining -= r;
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read( one, 0, 1 );
        return n <= 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read( byte[] b, int off, int len ) throws IOException {
        if( closed )
            throw new IOException( "Stream is closed" );
        if( len == 0 )
            return 0;
        int n;
        try {
            n = in.read( b, off, len );
        } catch( IOException e ) {
            if( ++failures > 2 || ( total >= 0 && pos >= total ) )
                throw e;
            Log.w( TAG, "Read failed at " + pos + " (" + e + "), re-opening", e );
            openAt( pos );
            n = in.read( b, off, len );
        }
        if( n > 0 ) {
            pos += n;
            failures = 0;
        }
        return n;
    }

    @Override
    public long skip( long n ) throws IOException {
        if( closed )
            throw new IOException( "Stream is closed" );
        if( n <= 0 )
            return 0;
        if( total >= 0 ) {
            long room = total - pos;
            if( room <= 0 )
                return 0;
            if( n > room )
                n = room;
        }
        if( n <= LOCAL_SKIP_LIMIT ) {
            byte[] buf = new byte[(int)Math.min( n, 32 * 1024 )];
            long done = 0;
            while( done < n ) {
                int r = read( buf, 0, (int)Math.min( buf.length, n - done ) );
                if( r < 0 ) break;
                done += r;
            }
            return done;
        }
        Log.i( TAG, "skip(" + n + ") at " + pos + " -> re-seeking to " + ( pos + n ) );
        openAt( pos + n );
        return n;
    }

    @Override
    public int available() throws IOException {
        return in != null ? in.available() : 0;
    }

    @Override
    public void close() {
        if( closed )
            return;
        closed = true;
        dropCurrent();
        try {
            client.close();
        } catch( IOException ignored ) {
        }
    }
}