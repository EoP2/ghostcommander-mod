package com.ghostsq.commander.https;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.ServerForm;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.adapters.ItemComparator;
import com.ghostsq.commander.utils.PrefStealer;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.HttpEntity;
import shaded.org.apache.http.HttpHost;
import shaded.org.apache.http.HttpStatus;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.auth.AuthScope;
import shaded.org.apache.http.auth.UsernamePasswordCredentials;
import shaded.org.apache.http.client.AuthCache;
import shaded.org.apache.http.client.CredentialsProvider;
import shaded.org.apache.http.client.config.RequestConfig;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpGet;
import shaded.org.apache.http.client.methods.HttpPut;
import shaded.org.apache.http.client.protocol.HttpClientContext;
import shaded.org.apache.http.conn.ssl.NoopHostnameVerifier;
import shaded.org.apache.http.conn.ssl.TrustAllStrategy;
import shaded.org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import shaded.org.apache.http.conn.ssl.TrustStrategy;
import shaded.org.apache.http.entity.BufferedHttpEntity;
import shaded.org.apache.http.entity.InputStreamEntity;
import shaded.org.apache.http.impl.auth.BasicScheme;
import shaded.org.apache.http.impl.client.BasicAuthCache;
import shaded.org.apache.http.impl.client.BasicCredentialsProvider;
import shaded.org.apache.http.impl.client.CloseableHttpClient;
import shaded.org.apache.http.impl.client.HttpClientBuilder;
import shaded.org.apache.http.impl.client.HttpClients;
import shaded.org.apache.http.ssl.SSLContextBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import androidx.annotation.WorkerThread;
//import de.psdev.slf4j.android.logger.AndroidLoggerAdapter;
//import de.psdev.slf4j.android.logger.LogLevel;

// https://javadoc.io/static/org.apache.httpcomponents/httpclient/4.5.2/index.html?org/apache/http/
// https://jackrabbit.apache.org/api/trunk/index.html?org/apache/jackrabbit/webdav/

// javadocs:
// httpclient: https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/index.html?
// httpcore:   https://www.javadoc.io/doc/org.apache.httpcomponents/httpcore/latest/index.html

public class WebDAVAdapter extends CommanderAdapterBase implements Engines.IReciever {
    private final static String TAG = "WebDAV";
    private final static String package_name = "com.ghostsq.commander.https";
    private final static String scheme = "https";
    private String trust_level = null;
    private Uri              uri = null, last_uri = null;
    private Item[]           items;
    private UsernamePasswordCredentials creds = null;
    private CloseableHttpClient client = null;
    private String client_host;
    public  Resources dav_res;

    static {
//        AndroidLoggerAdapter.setLogLevel( BuildConfig.DEBUG ? LogLevel.TRACE : LogLevel.INFO );
        // setprop log.tag.MainClientExec VERBOSE
        // setprop "log.tag.PoolingHttpClientConnectionManager" VERBOSE
    }
    
    public WebDAVAdapter( Context ctx ) {
        super( ctx );
        try {
            PackageManager pm = ctx.getPackageManager();
            dav_res = pm.getResourcesForApplication( package_name );

     // Not sure did we patched the lib source to make statusPhrases public? what should be a workaround??????
     //       DavException.statusPhrases.load( dav_res.openRawResource( R.raw.statuscode ) );
        }
        catch( Throwable e ) {
            Log.e( TAG, "Init error" );
        }
    }
    public WebDAVAdapter() {
        this( null );
    }

    @Override
    public String getScheme() {
        return scheme;
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case REAL:
        case RECEIVER:
        case SEARCH:
            return true;
        default: return super.hasFeature( feature );
        }
    }

    @Override
    public void setUri( Uri uri_ ) {
        if( uri_ == null ) return;
        last_uri = uri; 
        uri = uri_;
/*
        if( !Utils.str( uri.getPath() ) )
             uri = Uri.parse( scheme + ":/" );
 */
    }
    @Override 
    public Uri getUri() { 
        return uri;
    }

    public Uri getUriNoQuery() {
        return uri.buildUpon().clearQuery().build();
    }
    public String getPath() {
        String path = uri != null ? uri.getPath() : null;
        return Utils.str( path ) ? path : "/";
    }
     
    @Override 
    public String toString() { 
        if( uri != null ) {
            return uri.toString();
        }
        return "";
    }    

    @Override
    public void setCredentials( com.ghostsq.commander.utils.Credentials gc_crd ) {

        if( gc_crd == null )
            creds = null;
        else
            creds = new UsernamePasswordCredentials( gc_crd.getUserName(), gc_crd.getPassword() );

        client = null;
    }
    @Override
    public com.ghostsq.commander.utils.Credentials getCredentials() {
        if( creds == null ) return null;
        return new com.ghostsq.commander.utils.Credentials( creds.getUserName(), creds.getPassword() );
    }

    synchronized public CloseableHttpClient getClient( String host ) {
        if( host == null )
            return null;
        if( client == null || !host.equals( client_host ) ) {
            client = createClient( host );
            client_host = host;
        }
        return client;
    }

    @WorkerThread
    public CloseableHttpClient createClient( String host ) {
        try {
            Log.d( TAG, "Creating a new client for host " + host );
            try {
                PrefStealer ps = new PrefStealer( ctx );
                SharedPreferences psp = ps.StealFrom( package_name );
                if( psp != null )
                    trust_level = psp.getString( "trust_level", "normal" );
                ps.close();
            }
            catch( Throwable e ) {
                Log.w( TAG, "Could not refresh trust_level, keeping previous value: " + trust_level, e );
            }
            // tutorial: https://howtodoinjava.com/java/java-security/bypass-ssl-certificate-checking-java/
            TrustStrategy ts = null;
            TrustManager[] tms = null;
            HostnameVerifier hnv = null;
            if( "open".equals( trust_level ) ) {
                ts = new TrustAllStrategy();
                hnv = NoopHostnameVerifier.INSTANCE;
            } else
            if( "loose".equals( trust_level ) ) {
                ts = new TrustSelfSignedStrategy();
                hnv = new AllowSelfSignedHostnameVerifier();

                TrustManagerFactory tmf = TrustManagerFactory.getInstance( TrustManagerFactory.getDefaultAlgorithm() );
                tmf.init((KeyStore)null);

                TrustManager[] trustManagers = tmf.getTrustManagers();
                final X509TrustManager origTrustmanager = (X509TrustManager)trustManagers[0];

                tms = new TrustManager[]{
                   new X509TrustManager() {
                       @Override
                       public void checkClientTrusted( java.security.cert.X509Certificate[] chain, String authType ) throws CertificateException {
                           origTrustmanager.checkClientTrusted(chain, authType);
                       }

                       @Override
                       public void checkServerTrusted( java.security.cert.X509Certificate[] chain, String authType ) throws CertificateException {
                           try {
                               if( chain != null && chain.length == 1 )
                                   return;  // self-signed
                               origTrustmanager.checkServerTrusted( chain, authType );
                           } catch( CertificateException e) {
                               Log.w( TAG, "", e );
                               if( !IsCausedByExpiration( e ) )
                                   throw e;
                           }
                       }

                       boolean IsCausedByExpiration( Throwable e ) {
                           if( e instanceof CertificateExpiredException )
                               return true;
                           if( e == null )
                               return false;
                           return IsCausedByExpiration( e.getCause() );
                       }

                       public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                          return origTrustmanager.getAcceptedIssuers();
                       }
                   }
                };
            }

            // tutorial: https://www.tutorialspoint.com/apache_httpclient/apache_httpclient_user_authentication.htm
            // examples: https://github.com/apache/httpcomponents-client/blob/4.5.x/httpclient/src/examples/org/apache/http/examples/client/
            //           https://www.programcreek.com/java-api-examples/?class=org.apache.http.ssl.SSLContextBuilder&method=loadTrustMaterial
            // This connection manager must be used if more than one thread will be using the HttpClient.
            HttpClientBuilder hcb = HttpClients.custom();
            if( creds != null  ) {
                CredentialsProvider cp = new BasicCredentialsProvider();
                cp.setCredentials( new AuthScope( host, 443 ), creds );
                hcb.setDefaultCredentialsProvider( cp );
            }
            if( ts != null ) {
                SSLContext c = new SSLContextBuilder().loadTrustMaterial( null, ts ).build();
                if( tms != null )
                    c.init( null, tms, null );
                hcb.setSSLContext( c );
            }
            if( hnv != null )
                hcb.setSSLHostnameVerifier( hnv );
            hcb.setUserAgent( this.getClass().getName() );
            RequestConfig rc = RequestConfig.custom().setExpectContinueEnabled(true).build();
            
            hcb.setDefaultRequestConfig( rc );
            return hcb.build();
        } catch( Exception e ) {
            Log.e( TAG, host, e );
        }
        return null;
    }

    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        if( tmp_uri != null ) {
            if( !Utils.str( tmp_uri.getHost() ) ) {
                notify( pass_back_on_done );
                Intent i = new Intent( commander.getContext(), ServerForm.class );
                i.putExtra( "schema", "https" );
                i.putExtra( "title",  "WebDAV" );
                commander.issue( i, Commander.REQUEST_CODE_SRV_FORM );
                return true;
            }
            setUri( tmp_uri );
        }
        if( uri == null )
            return false;

        if( reader != null )
            reader.interrupt();

        search = SearchProps.parseSearchQueryParams( ctx, uri );

        reader = new ListEngine( getUriNoQuery(), this, search, pass_back_on_done );
        reader.setHandler( readerHandler );
        reader.setName( TAG + ".ListEngine" );
        reader.start();
        return true;
    }

    @Override
    protected void onReadComplete() {
        if( reader instanceof ListEngine ) {
            ListEngine list_engine = (ListEngine)reader;
            Item[] le_items = list_engine.getItems();
            if( le_items != null ) {
                items = le_items;
                int n = items.length;
                reSort();
                setCount( ++n );
            }
            else {
                if( last_uri != null ) {
                    //commander.Navigate( last_uri, null, uri.getLastPathSegment() );
                    setUri( last_uri );
                    return;
                }
                setCount( 1 );
            }
            parentLink = uri.getPathSegments().size() > 0 ? PLS : SLS;
            notifyDataSetChanged();
        }
    }

    @Override
    protected void reSort() {
        if( items == null ) return; 
        ItemComparator cmpr = new ItemComparator( 
                     mode & CommanderAdapter.MODE_SORTING, 
                    (mode & CommanderAdapter.MODE_CASE) != 0, 
                    (mode & CommanderAdapter.MODE_SORT_DIR) == 0 );
        Arrays.sort( items, cmpr );
    }
    
    @Override
    public boolean createFile( String fileURI ) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void createFolder( String name ) {
        notify( Commander.OPERATION_STARTED );
        String new_dir_url = uri.buildUpon().appendPath( name ).build().toString();
        commander.startEngine( new MkDirEngine( this, new_dir_url ) );
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        try {
            Item[] to_delete = bitsToItems( cis );
            if( to_delete != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new DelEngine( this, to_delete ) );
                return true;
            }
        }
        catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( items == null || position > items.length ) return null;
        if( position == 0 ) return parentLink;  
        Item item = items[ position - 1 ];
        return item.name;
    }

    @Override
    public Uri getItemUri( int position ) {
        if( items == null ) return null;
        if( position > items.length ) return null;
        Item item = items[ position - 1 ];
        DavItem di = (DavItem)item;
        return di.getUri( getUriNoQuery().toString() );
    }

    @WorkerThread
    @Override
    public Item getItem( Uri u ) {
        if( u == null ) return null;
        PropFinder pf = new PropFinder( this );
        pf.setClient( this.client );    // !!!!!!! could there be conflicts????????
        return pf.getDavItem( URI.create( u.toString() ) );
    }
    
    @Override
    public void openItem( int position ) {
        if( position == 0 ) { // ..
            if( search != null ) {
                commander.Navigate( getUriNoQuery(), null, null );
                return;
            }
            if( uri != null && parentLink != SLS ) {
                String path = uri.getPath();
                int len_ = path.length()-1;
                if( len_ > 0 ) {
                    Uri.Builder ub = uri.buildUpon();
                    ub.path( "/" );
                    List<String> ps = uri.getPathSegments();
                    int n = ps.size();
                    if( n > 0 ) n--;
                    for( int i = 0; i < n; i++ ) ub.appendPath( ps.get( i ) );
                    // passing null instead of credentials keeps the current authentication session
                    commander.Navigate( ub.build(), null, uri.getLastPathSegment() );
                }
            }
            else
                commander.Navigate( Uri.parse( "home:" ), null, null );
            return;
        }
        if( items == null || position < 0 || position > items.length )
            return;
        Item item = items[position - 1];
        Uri item_uri = getItemUri( position );
        if( item.dir )
            commander.Navigate( item_uri, null, null );
        else {
            if( item instanceof DavItem ) {
/*                
                final String mime = ((DavItem)item).content_type;
                if( mime != null && ( mime.startsWith( "audio/" ) ||
                                      mime.startsWith( "video/" ) ) ) {
*/
                    commander.Open( item_uri, getCredentials() );
                    return;
//                }
            }
        }
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            Item[] list = bitsToItems( cis );
            if( list == null || list.length == 0 ) return;
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new CalcSizesEngine( this, list ) );
        }
        catch(Exception e) {
        }
    }

    private Item parent_item = new Item();
    
    @Override
    public Object getItem( int position ) {
        try {
            if( position == 0 ) {
                parent_item.name = parentLink;
                return parent_item;
            }
            else {
                return items[position-1];
            }        
        } catch( Exception e ) {
            Log.e( TAG,  "", e  );
        }
        return null;
    }

    public final Item[] getItems() {
        return items;
    }
    
    @Override
    public boolean renameItem( int position, String new_name, boolean copy ) {
        if( position <= 0 || position > items.length )
            return false;
        if( copy ) {
            notify( s( Utils.RR.not_supported.r() ), Commander.OPERATION_FAILED );
            return false;
        }
        Object io = getItem( position );
        if( io == null ) {
            notify( s( Utils.RR.rename_err.r() ), Commander.OPERATION_FAILED );
            return false;
        }
        notify( Commander.OPERATION_STARTED );
        commander.startEngine( new RenEngine( this, (DavItem)io, new_name ) );
        return true;
    }
    
    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        String err_msg = null;
        try {
            Item[] to_copy = bitsToItems( cis );
            if( to_copy == null ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            } 
            java.io.File dest = null;
            Engines.IReciever recipient = null;
            if( to instanceof FSAdapter ) {
                String dest_fn = to.toString();
                dest = new java.io.File( dest_fn );
                if( !dest.exists() ) dest.mkdirs();
                if( !dest.isDirectory() )
                    throw new RuntimeException( ctx.getString( Utils.RR.file_exist.r(), dest_fn ) );
            } else {
                dest = new java.io.File( createTempDir() );
                recipient = to.getReceiver();
            }
            notify( Commander.OPERATION_STARTED );
            CopyFromEngine cfe = new CopyFromEngine( commander, this, to_copy, move, to );
            commander.startEngine( cfe );
            return true;
        }
        catch( Exception e ) {
            err_msg = "Exception: " + e.getMessage();
        }
        notify( err_msg, Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    public IReceiver getReceiver( Uri dir )  {
        CloseableHttpClient client = WebDAVAdapter.this.createClient( dir.getHost() );
        return new Receiver( this, dir, client );
    }

    @Override
    public IReciever getReceiver() {
        return this;
    }
    
    @Override
    public boolean receiveItems( String[] fileURIs, int move_mode ) {
        try {
            if( fileURIs == null || fileURIs.length == 0 ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            java.io.File[] list = Utils.getListOfFiles( fileURIs );
            if( list == null || list.length == 0 ) {
                notify( "Something wrong with the files", Commander.OPERATION_FAILED );
                return false;
            }
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new CopyToEngine( this, list, move_mode ) );
            return true;
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }
    
    public Item[] bitsToItems( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    counter++;
            Item[] subItems = new Item[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    subItems[j++] = items[ cis.keyAt( i ) - 1 ];
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    private int content_requests_counter = 0;

    class InputStreamWrap extends InputStream {
        private CloseableHttpClient client;
        private final HttpGet gm;
        private InputStream is;
        
        public InputStreamWrap( String host, String s_url ) {
            client = WebDAVAdapter.this.createClient( host );
            gm = new HttpGet( s_url );
            try {
                CloseableHttpResponse chr = client.execute( gm );
                StatusLine sl = chr.getStatusLine();
                if( sl.getStatusCode() == HttpStatus.SC_OK ) {
                    HttpEntity he = chr.getEntity();
                    if( he != null ) {
                        this.is = he.getContent();
                        if( is != null )
                            return;
                    }
                } else
                    Log.e( TAG, "Response status: " + sl.getReasonPhrase() );
                Log.e( TAG, "Cannot create input stream for " + s_url );
            } catch( Exception e ) {
                Log.e( TAG, s_url, e );
            }
            gm.releaseConnection();
        }

        public boolean isValid() {
            return is != null;
        }
        
        @Override
        public void close() {
            try {
                is.close();
                gm.releaseConnection();
                client.close();
                client = null;
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
        @Override public int read()                             throws IOException { return is.read();  }
        @Override public int read(byte b[])                     throws IOException { return is.read(b); }
        @Override public int read(byte b[], int off, int len)   throws IOException { return is.read(b,off,len); }
        @Override public long skip(long n)                      throws IOException { return is.skip(n); }
        @Override public int available()                        throws IOException { return 0; /*is.available();*/ }    // returns only first time. then, even when more bytes to read returns 0!
        @Override public synchronized void reset()              throws IOException { is.reset(); }
        @Override public synchronized void mark(int readlimit) { is.mark(readlimit); }
        @Override public boolean markSupported()               { return is.markSupported(); }
    }
/*
    @WorkerThread
    @Override
    public InputStream getContent( Uri u, long skip ) {
        InputStreamWrap isw = new InputStreamWrap( u.getHost(), u.toString() );
        return isw.isValid() ? isw : null;
    }
*/
    @WorkerThread
    @Override
    public InputStream getContent( Uri u, long skip ) {
        if( BuildConfig.DEBUG ) Log.v( TAG, "Get content " + u.toString() + ", skip " + skip );
        CloseableHttpClient client = WebDAVAdapter.this.createClient( u.getHost() );
        String s_url = u.toString();
        HttpGet gm = new HttpGet( u.toString() );
        if( skip > 0 )
            gm.addHeader( "Range", "bytes=" + skip + "-" );
        try {
            CloseableHttpResponse chr = client.execute( gm );
            StatusLine sl = chr.getStatusLine();
            int code = sl.getStatusCode();
            if( code == HttpStatus.SC_OK || code == HttpStatus.SC_PARTIAL_CONTENT ) {
                HttpEntity he = chr.getEntity();
                if( he != null ) {
                    InputStream is = he.getContent();
                    if( is != null )
                        return is;
                }
            } else
                Log.e( TAG, "Response status: " + sl.getReasonPhrase() );
            Log.e( TAG, "Cannot create input stream for " + s_url );
        } catch( Exception e ) {
            Log.e( TAG, s_url, e );
        }
        gm.releaseConnection();
        return null;
    }

/* TODO: adapt to new API
    class PutMethodBuffer extends ByteArrayOutputStream {
        private CloseableHttpClient  client;
        private PutMethod pm;
        
        public PutMethodBuffer( String host, String s_url ) {
            this.client = WebDAVAdapter.this.createClient( host );
            this.pm = new PutMethod( s_url );
        }
        
        @Override
        public void close() {
           try {
               ByteArrayRequestEntity bare = new ByteArrayRequestEntity( toByteArray() );
               pm.setRequestEntity( bare );
                int status_code = client.executeMethod( pm );
                if( status_code != HttpStatus.SC_OK ) {
                    Log.e( TAG, "Can't save the content to " + pm.getURI().toString() );
                }
            } catch( Exception e ) {
                try {
                    Log.e( TAG, pm.getURI().toString(), e );
                } catch( URIException e1 ) {
                    e1.printStackTrace();
                }
            }
            finally {
                pm.releaseConnection();
            }
        }
    }

*/
/* TODO: adapt to new API
        return new PutMethodBuffer( u.getHost(), u.toString() );

 */

    @WorkerThread
    @Override
    public OutputStream saveContent( Uri u ) {
        CloseableHttpClient client = WebDAVAdapter.this.createClient( u.getHost() );
        String s_url = u.toString();
        HttpPut pm = new HttpPut( u.toString() );
        try {
            AuthCache authCache = new BasicAuthCache();
            authCache.put( new HttpHost( u.getHost()), new BasicScheme() );
            final HttpClientContext http_context = HttpClientContext.create();
            http_context.setAuthCache(authCache);

            final PipedInputStream  pis = new PipedInputStream();
            final PipedOutputStream pos = new PipedOutputStream( pis );
            new Thread(
               new Runnable(){
                  public void run(){
                    try {
                        InputStreamEntity  ise = new InputStreamEntity( pis );
                        BufferedHttpEntity bhe = new BufferedHttpEntity( ise );
                        pm.setEntity( bhe );
                        CloseableHttpResponse chr = client.execute( pm, http_context );
                        StatusLine sl = chr.getStatusLine();
                        int st_code = sl.getStatusCode();
                        Log.d( TAG, "HTTP status=" + st_code + " " + sl.getReasonPhrase() );
                        if( st_code >= 400 ) {
                            Log.e( TAG, "Fail " + sl.getReasonPhrase() );
                        }
                    } catch( Exception e ) {
                        Log.e( TAG, "", e );
                    } finally {
                        pm.releaseConnection();
                    }
                  }
                }
              ).start();
            return pos;
        } catch( Exception e ) {
            Log.e( TAG, s_url, e );
        }
        return null;
    }

    @Override
    public void closeStream( Closeable s ) {
        try {
            s.close();
            Log.d( TAG, "Closing the stream!.." );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }
}
