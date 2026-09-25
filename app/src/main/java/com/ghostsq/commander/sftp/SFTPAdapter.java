package com.ghostsq.commander.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.adapters.ItemComparator;
import com.ghostsq.commander.adapters.Manipulator;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.OpenSSHConfig;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.jce.SHA256;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;

// see https://epaul.github.io/jsch-documentation/javadoc/index.html

public class SFTPAdapter extends CommanderAdapterBase implements Engines.IReciever, Manipulator {
    private final static String TAG = "SFTPAdapter";
    private final JSch jsch = new JSch();
    private Session session;
    private Hashtable<Integer, Channel> openedChannels = new Hashtable<Integer, Channel>( 1, 0.75f );;
    private Uri uri;
    private Credentials crd;
    private HostKey lastHostKey;
    private Item[] items = null;
    private static int instance_count = 0;
    private String showDigestAs = "MD5";
    private UserProxy up;

    static {
        JSch.setLogger( new Logger() {
            @Override
            public boolean isEnabled( int level ) {
                return true;
            }
            @Override
            public void log( int level, String message ) {
                int all;
                switch( level ) {
                    case Logger.INFO:  all = Log.INFO;  break;
                    case Logger.WARN:  all = Log.WARN;  break;
                    case Logger.ERROR:
                    case Logger.FATAL: all = Log.ERROR; break;
                    case Logger.DEBUG:
                    default:
                        all = Log.DEBUG;
                }
                if( BuildConfig.DEBUG || all != Log.INFO )
                    Log.println( all, "JSch", message );
            }
        } );
    }

    public SFTPAdapter(Context ctx_) {
        super( ctx_ );
        File f_base_dir = sftp.getDir( ctx );
        if( f_base_dir != null ) {
            try {
                File f_kh = new File( f_base_dir, "known_hosts" );
                if( !f_kh.exists() )
                    f_kh.createNewFile();
                jsch.setKnownHosts( f_kh.getAbsolutePath() );
                File f_sc = new File( Environment.getExternalStorageDirectory(), ".GhostCommander/ssh_config" );
                if( f_sc.exists() ) {
                    OpenSSHConfig sc = OpenSSHConfig.parseFile( f_sc.getAbsolutePath() );
                    jsch.setConfigRepository( sc );
                }
            } catch( Exception e ) {
                Log.e( TAG, f_base_dir.toString(), e );
            }
        }
    }

    @Override
    public void Init( Commander c ) {
        super.Init( c );
// TEST! Kerberos auth requires GSSManagerImpl
//        org.ietf.jgss.GSSManager mgr = org.ietf.jgss.GSSManager.getInstance();


        UserInteractHandler uih = new UserInteractHandler( ctx );
        up = new UserProxy( this, uih );
        Log.d( TAG, "Created instance #" + ++instance_count );

        SharedPreferences psp = PreferenceManager.getDefaultSharedPreferences( ctx );
        if( psp != null ) {
            showDigestAs = psp.getString( "show_digest_as", "MD5" );
            JSch.setConfig( "FingerprintHash", showDigestAs );
        }

        KeysKeeper.checkMigrateKeys( ctx );
    }

    @Override
    public String toString() {
        if( uri == null )
            return "";
        String ui = uri.getUserInfo();
        if( ui != null && crd == null )
            return Utils.mbAddSl( Favorite.screenPwd( uri ) );
        if( crd == null )
            return Utils.mbAddSl( uri.toString() );
        return Utils.mbAddSl( Favorite.screenPwd( Utils.getUriWithAuth( uri, crd ) ) );
    }

    public String getDescription(int flags) {
        String desc = "sftp:";
        if( uri != null ) desc += uri.getHost();
        return desc;
    }

    @Override
    public void setCredentials( Credentials crd_ ) {
        crd = crd_;
    }

    @Override
    public Credentials getCredentials() {
        return crd;
    }

    @Override
    public void setUri( Uri uri_ ) {
        if( uri_ == null )
            return;
        String ui = uri_.getUserInfo();
        if( ui != null )
            crd = new Credentials( ui );
        uri = Utils.updateUserInfo( uri_, null ); // store the uri without
                                                  // credentials!
    }

    @Override
    public Uri getUri() {
        return uri.buildUpon().encodedPath( Utils.mbAddSl( uri.getEncodedPath() ) ).build();
    }

    @Override
    public String getScheme() {
        return "sftp";
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case REAL:
        case RECEIVER:
        case SEARCH:
        case FILTER:
        case DIRSIZES:
        case SHOWS_PERM:
        case MULT_RENAME:
        case RENAME:
        case DELETE:
            return true;
        default:
            return super.hasFeature( feature );
        }
    }

    public int getSortMode() {
        return mode & ( MODE_SORTING | MODE_HIDDEN | MODE_SORT_DIR );
    }

    public final  Session getSession() {
        return session;
    }

    public final ChannelSftp getChannel() {
        try {
            synchronized( jsch ) {
                if( session == null ) {
                    Log.e( TAG, "No SSH session!" );
                    return null;
                }
                if( !session.isConnected() )
                    session.connect();
                if( !session.isConnected() )
                    return null;
                Channel channel = session.openChannel( "sftp" );
                channel.connect();
                return (ChannelSftp)channel;
            }
        } catch( Throwable e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    public final int connectAndLogin() throws Exception {
        try {
            Log.v( TAG, "connectAndLogin() is called in thread " + Thread.currentThread().getId() );
            Uri u = uri;
            if( u == null )
                return SFTPConnection.NO_CONNECT;
            int port = u.getPort();
            if( port == -1 )
                port = 22;

            String host = u.getHost();
            if( host == null )
                return SFTPConnection.NO_CONNECT;
            synchronized( jsch ) {
                if( up == null )
                    Init( null );

                if( session == null || !host.equalsIgnoreCase( session.getHost() ) ) {
                    Log.v( TAG, "creating a new session" );
                    lastHostKey = null;
                    if( crd == null ) {
                        String ui = u.getUserInfo();
                        if( ui == null ) {
                            Log.w( TAG, "No credentials provided!" );
                            return SFTPConnection.NO_LOGIN;
                        }
                        crd = new Credentials( ui );
                    }
                    jsch.removeAllIdentity();   // TODO: is there a way to add it only once???
                    File pkf = KeysKeeper.getPrivateKeyFile( ctx, crd.getUserName(), host );
                    if( pkf != null && pkf.exists() ) {
                        up.setPrivateKeyFileUsed( pkf );
                        jsch.addIdentity( pkf.getAbsolutePath() );
                    }
                    session = jsch.getSession( crd != null ? crd.getUserName() : null, host, port );

                    session.setUserInfo( up );
                }
                if( session.isConnected() ) {
                    return SFTPConnection.WAS_IN;
                }
                session.connect();
                lastHostKey = session.getHostKey();

                return SFTPConnection.LOGGED_IN;
            }
        } catch( Exception e ) {
            Log.e( TAG, uri != null ? uri.toString() : null, e );
            if( "Auth fail".equals( e.getMessage() ) ) {
                lastHostKey = session.getHostKey();
                disconnect();
                return SFTPConnection.NO_LOGIN;
            }
            disconnect();
            throw e;
        }
    }

    public final String getAlgorithm() {
        if( lastHostKey == null )
            return null;
        return lastHostKey.getType();
    }

    public final String getHost() {
        if( lastHostKey == null )
            return uri != null ? uri.getHost() : null;
        return lastHostKey.getHost();
    }

    public final boolean isHostKnown() {
        if( lastHostKey == null )
            return false;
        HostKeyRepository hkr = jsch.getHostKeyRepository();
        String key_base64 = lastHostKey.getKey();
        return hkr.check( lastHostKey.getHost(), Base64.decode( key_base64, Base64.DEFAULT ) ) == HostKeyRepository.OK;
    }

    public final String getFingerPrint() {
        if( lastHostKey == null ) {
            if( session == null )
                return null;
            lastHostKey = session.getHostKey();
            if( lastHostKey == null )
                return null;
        }
        if( "MD5".equals( showDigestAs ) ) {
            JSch.setConfig( "FingerprintHash", showDigestAs );
            return lastHostKey.getFingerPrint( jsch );  // it's hardcoded to out as HEX
        }
        if( "SHA256".equals( showDigestAs ) ) {
            try {
                String key_base64 = lastHostKey.getKey();
                byte[] key = Base64.decode( key_base64, Base64.DEFAULT );
                SHA256 hash = new SHA256();
                hash.init();
                hash.update( key, 0, key.length );
                byte[] digest = hash.digest();
                return  Base64.encodeToString( digest, Base64.NO_PADDING | Base64.NO_WRAP );
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
        return null;
    }

    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        Log.v( TAG, "readSource() is called in thread " + Thread.currentThread().getId() );
        if( tmp_uri != null )
            setUri( tmp_uri );
        if( uri == null )
            return false;
        search = SearchProps.parseSearchQueryParams( ctx, uri );
        reader = new ListEngine( readerHandler, this, search, pass_back_on_done );
        reader.setName( TAG + ".ListEngine" );
        return commander.startEngine( reader );
    }

    @Override
    protected void onReadComplete() {
        Log.v( TAG, "UI thread finishes the items obtaining. reader=" + reader );
        if( reader instanceof ListEngine ) {
            ListEngine list_engine = (ListEngine)reader;
            items = list_engine.getItems();
            numItems = items != null ? items.length + 1 : 1;
            String path = uri.getPath();
            parentLink = path == null || path.length() == 0 || path.equals( SLS ) ? SLS : PLS;
            notifyDataSetChanged();
        }
    }

    @Override
    protected void reSort() {
        if( items == null || items.length == 0 )
            return;
        Item item = items[0];
        ItemComparator cmpr = new ItemComparator( mode & CommanderAdapter.MODE_SORTING, ( mode & CommanderAdapter.MODE_CASE ) != 0,
                ( mode & CommanderAdapter.MODE_SORT_DIR ) == 0 );
        Arrays.sort( items, cmpr );
    }

    @Override
    public void openItem( int position ) {
        if( position == 0 ) { // ..
            if( search != null ) {
                Uri u = uri.buildUpon().clearQuery().build();
                commander.Navigate( u, null, null );
                return;
            }
            if( uri != null && parentLink != SLS ) {
                List<String> ps = uri.getPathSegments();
                if( ps != null && ps.size() > 0 ) {
                    int li = ps.size() - 1;
                    String lps = ps.get( li );
                    StringBuilder sb = new StringBuilder();
                    for( int i = 0; i < li; i++ ) {
                        sb.append( SLS );
                        sb.append( Utils.escapePath( ps.get( i ) ) );
                    }
                    Uri p_uri = uri.buildUpon().encodedPath( sb.toString() ).build();
                    commander.Navigate( p_uri, null, lps );
                }
            }
            return;
        }
        if( items == null || position < 0 || position > items.length )
            return;
        Item item = items[position - 1];

        if( item.dir ) {
            String cur = uri.getPath();
            if( cur == null || cur.length() == 0 )
                cur = SLS;
            else if( cur.charAt( cur.length() - 1 ) != SLC )
                cur += SLS;
            Uri item_uri = uri.buildUpon().appendEncodedPath( Utils.escapePath( item.name ) ).build();
            commander.Navigate( item_uri, null, null );
        } else {
            Uri auth_item_uri = getUri().buildUpon().appendEncodedPath( item.name ).build();
            commander.Open( auth_item_uri, crd );
        }
    }

    @Override
    public Uri getItemUri( int position ) {
        Uri u = getUri();
        if( u == null )
            return null;
        String item_name = getItemName( position, false );
        if( item_name == null )
            return null;
        return u.buildUpon().appendPath( item_name ).build();
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( items == null || position <= 0 || position > items.length )
            return null;
        if( !full )
            return items[position - 1].name;
        String path = toString();
        if( path.length() == 0 )
            return null;
        if( path.charAt( path.length() - 1 ) != SLC )
            path += SLS;
        return path + items[position - 1].name;
    }

    @Override
    public boolean renameItem( int position, String newName, boolean copy ) {
        return renameItem( items[position - 1], newName, copy );
    }

    public boolean renameItem( CommanderAdapter.Item item, String newName, boolean copy ) {
        if( copy ) {
            notify( s( Utils.RR.not_supported.r() ), Commander.OPERATION_FAILED );
            return false;
        }
        notify( Commander.OPERATION_STARTED );
        Item[] list = new Item[1];
        list[0] = item;
        commander.startEngine( new RenEngine( commander, this, list, newName ) );
        return true;
    }

    @Override
    public boolean renameItems( SparseBooleanArray cis, String pattern_str, String replace_to ) {
        Item[] subItems = bitsToItems( cis );
        try {
            MultRenEngine re = new MultRenEngine( ctx, this, subItems, pattern_str, replace_to );
            commander.startEngine( re );
        } catch( Exception e ) {
            Log.e( TAG, "Can't rename to " + replace_to + " with pattern " + pattern_str, e );
        }
        return false;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        String err_msg = null;
        try {
            Item[] subItems = bitsToItems( cis );
            if( subItems == null ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            if( move && to.getClass().getName().contains( "SFTPAdapter" ) ) {
                Uri dest_uri = to.getUri();
                if( uri.getHost().equals( dest_uri.getHost() ) ) { 
                    String new_path = Utils.mbAddSl( dest_uri.getPath() );
                    commander.startEngine( new RenEngine( commander, this, subItems, new_path ) );
                    return true;
                }
            } 
            notify( Commander.OPERATION_STARTED );
            CopyFromEngine cfe = new CopyFromEngine( commander, this, subItems, move, to );
            commander.startEngine( cfe );
            return true;
        } catch( Exception e ) {
            err_msg = "Exception: " + e.getMessage();
        }
        notify( err_msg, Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    public boolean receiveItems( String[] fileURIs, int move_mode ) {
        try {
            if( fileURIs == null || fileURIs.length == 0 ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            File[] list = Utils.getListOfFiles( fileURIs );
            if( list == null || list.length == 0 ) {
                notify( "Something wrong with the files", Commander.OPERATION_FAILED );
                return false;
            }
            notify( Commander.OPERATION_STARTED );
            return commander.startEngine( new CopyToEngine( commander, this, list, move_mode ) );
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public boolean createFile( String fileURI ) {
        return false;
    }

    @Override
    public void createFolder( String name ) {
        notify( Commander.OPERATION_STARTED );
        commander.startEngine( new MkDirEngine( this, Utils.mbAddSl( uri.getPath() ) + name ) );
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            Item[] list = bitsToItems( cis );
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new CalcSizesEngine( this, list ) );
        } catch( Exception e ) {
        }
    }

    @Override
    public boolean deleteItem( Item item  ) {
        Item[] list = new Item[1];
        list[0] = item;
        return deleteItems( list );
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        return deleteItems( bitsToItems( cis ) );
    }

    private boolean deleteItems( Item[] to_del ) {
        try {
            if( to_del != null ) {
                notify( Commander.OPERATION_STARTED );
                return commander.startEngine( new DelEngine( this, to_del ) );
            }
        } catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }

    protected int getPredictedAttributesLength() {
        return 25;
    }

    @Override
    public Object getItem( int position ) {
        Item item = new Item();
        item.name = "???";
        {
            if( position == 0 ) {
                item.name = parentLink;
            } else {
                if( items != null && position > 0 && position <= items.length ) {
                    return items[position - 1];
                }
            }
        }
        return item;
    }

    @Override
    public Item getItem( Uri u ) {
        try {
            if( u == null || ( uri != null && !uri.getHost().equals( u.getHost() ) ) )
                return null;
            if( session == null && connectAndLogin() < SFTPConnection.NEUTRAL )
                    return null;
            ChannelSftp c = getChannel();
            if( c == null )
                return null;
            String fpath = u.getPath();
            SftpATTRS attrs = c.lstat( fpath );
            if( attrs == null )
                return null;
            Item item = makeItem( u.getLastPathSegment(), attrs );
            if( attrs.isLink() )
                updateLinkItem( item, c, fpath );
             return item;
        } catch( Throwable e ) {
            Log.e( TAG, u.toString(), e );
        }
        return null;
    }

    public final Item makeItem( String name, SftpATTRS fa ) {
        Item item = new Item( name );
        item.dir  = fa.isDir();
        if( !item.dir )
            item.size = fa.getSize();
        item.date = new Date( (long)fa.getMTime() * 1000L );
        item.attr = fa.getPermissionsString() + " " + fa.getUId() + " " + fa.getGId();
        return item;
    }

    public final void updateLinkItem( Item item, ChannelSftp channel, String fpath ) {
        if( fpath == null ) return;
        try {
            SftpATTRS lfa = channel.stat( fpath );
            if( lfa.isDir() ) item.dir = true;
            item.linkTarget = channel.readlink( fpath );
        } catch( Exception e ) {
            Log.e( TAG, "SFTP link is invalid: " + fpath + " " + e.getMessage() );
        }
    }

/*
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
                    subItems[j++] = items[cis.keyAt( i ) - 1];
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }
 */

    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        new Thread( new Runnable() {
            @Override
            public void run() {
                SFTPAdapter.this.disconnect();
            }
        } ).start();
        items = null;
        uri = null;
        Log.d( TAG, "Destroying instance #" + instance_count-- );
    }

    public void disconnect() {
        Log.v( TAG, "disconnect() is called in thread " + Thread.currentThread().getId() );
        Log.d( TAG, "Disconnecting..." );
        try {
            if( session != null )
                session.disconnect();
        } catch( Exception e ) {
            Log.w( TAG, "" + uri, e );
        }
        session = null;
    }

    @Override
    public void finalize() {
        Log.d( TAG, "Finalizing..." );
        disconnect();
    }

    // ----------------------------------------

    private int content_requests_counter = 0;

    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            Log.v( TAG, "getContent() was called, " + ++content_requests_counter );
            String sftp_path_name = u.getPath();
            if( !Utils.str( sftp_path_name ) )
                return null;
            if( uri == null ) {
                File f = new File( sftp_path_name );
                uri = u.buildUpon().path( f.getParent() ).build();
            }
            if( connectAndLogin() < SFTPConnection.NEUTRAL ) {
                Log.e( TAG, "Can't connect. Requested URI: " + u );
                return null;
            }
            ChannelSftp c = getChannel();
            if( c == null )
                return null;
            InputStream is = c.get( sftp_path_name );
            openedChannels.put( is.hashCode(), c );
            return is;
        } catch( Exception e ) {
            latestErrorText = e.getMessage() + " Exception on request of the file " + u;
            Log.e( TAG, latestErrorText, e );
        }
        return null;
    }

    @Override
    public OutputStream saveContent( Uri u ) {
        try {
            String sftp_path_name = u.getPath();
            if( uri == null ) {
                File f = new File( sftp_path_name );
                uri = u.buildUpon().path( f.getParent() ).build();
            }
            if( Utils.str( sftp_path_name ) && connectAndLogin() < SFTPConnection.NEUTRAL )
                return null;
            ChannelSftp c = getChannel();
            if( c == null )
                return null;
            OutputStream os = c.put( sftp_path_name );
            openedChannels.put( os.hashCode(), c );
            return os;
        } catch( Exception e ) {
            Log.e( TAG, u.getPath(), e );
        }
        return null;
    }

    @Override
    public void closeStream( Closeable s ) {
        try {
            Log.v( TAG, "closeStream() was called, " + --content_requests_counter );
            if( s == null )
                return;
            int hash = s.hashCode();
            s.close();
            Channel c = openedChannels.get( hash );
            if( c == null )
                return;
            c.disconnect();
            openedChannels.remove( hash );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public IReciever getReceiver() {
        return this;
    }

    @Override
    public IReceiver getReceiver( Uri dir )  {
        return new Receiver( dir, getChannel() );
    }
}
