package com.ghostsq.commander.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.widget.AdapterView;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.adapters.Manipulator;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/*
To make JCIFS-NG debugging messages appear in the logcat, enable them in the android shell, like
setprop log.tag.<tag (usually class) name> VERBOSE
for example:
setprop log.tag.SmbResourceLocatorImpl VERBOSE
setprop log.tag.NameServiceClientImpl VERBOSE

 */

public class SMBAdapter extends CommanderAdapterBase implements Engines.IReciever, Manipulator {
    private static final String TAG = "SMBAdapter";
    private final static String package_name = "com.ghostsq.commander.smb";
    private final static int OP_RELOGIN = 43995;
    private Resources smb_res;
    private static Properties prop = null;
    private CIFSContext baseContext, baseContext1, authContext;
    private boolean   as_guest = true;
    private Uri       uri = null;
    private Credentials credentials;
    private SmbItem[] items;

    // taken from http://code.google.com/p/boxeeremote/wiki/AndroidUDP
    private final InetAddress getBroadcastAddress( DhcpInfo dhcp ) throws IOException {
        if( dhcp == null ) return null;
        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
          quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

    private final CIFSContext createContext( boolean force_smb1, boolean in_domain ) {
        Log.d( TAG, "Creating the base context. In domain: " + in_domain );

        SharedPreferences psp = PreferenceManager.getDefaultSharedPreferences( ctx );
        boolean p = psp != null;
        // see https://github.com/AgNO3/jcifs-ng/issues/186#issuecomment-674384275
        if( p ) as_guest = psp.getBoolean( "as_guest", true );

        prop = new Properties();
        prop.putAll(System.getProperties());

        String minv = null, maxv = null;
        if( force_smb1 )
            minv = maxv = "SMB1";
        else {
            if( p ) {
                minv = psp.getString( "min_smb_ver", "SMB1" );
                maxv = psp.getString( "max_smb_ver", "SMB210" );
            }
        }
        Log.d( TAG, "New context compatibilities: min: " + minv + ", max: " + maxv );

        if( minv != null ) prop.put( "jcifs.smb.client.minVersion", minv );
        if( maxv != null ) prop.put( "jcifs.smb.client.maxVersion", maxv );

        if( false && BuildConfig.DEBUG )
            prop.put("jcifs.traceResources", "true");   // eats a lot of memory!

        String ros = in_domain ? "DNS,BCAST" : "BCAST";
        if( p ) {
            boolean resolve_using_DNS = psp.getBoolean( "resolve_using_DNS", false );
            if( resolve_using_DNS && !in_domain )
                ros = ros + ",DNS";
            String WINS_server = psp.getString( "WINS_server", null );
            if( WINS_server != null && WINS_server.length() > 0 ) {
                prop.put( "jcifs.netbios.wins", WINS_server );
                ros = "WINS," + ros;
            }
        }
        Log.d( TAG, "resolveOrder: " + ros );
        prop.put( "jcifs.resolveOrder", ros );

        if( commander != null ) {
            try {
                String ba_s = null;
                WifiManager wifi = (WifiManager) commander.getContext().getApplicationContext().getSystemService( Context.WIFI_SERVICE );
                if( wifi != null && wifi.isWifiEnabled() ) {
                    InetAddress ba = getBroadcastAddress( wifi.getDhcpInfo() );
                    if( ba != null )
                        ba_s = ba.getHostAddress();
                }
                if( ba_s == null )
                    Log.w( TAG, "Can't get the broadcast address." );
                else {
                    Log.d( TAG, "Broadcast address: " + ba_s );
                    prop.put( "jcifs.netbios.baddr", ba_s );
                }
            } catch( Exception e ) {
                Log.e( TAG, "setting the broadcast IP", e );
            }
        }
        // get around https://github.com/AgNO3/jcifs-ng/issues/40
        prop.put("jcifs.smb.client.ipcSigningEnforced", "false");
        // allow plaintext password fallback
        prop.put("jcifs.smb.client.disablePlainTextPasswords", "false");
        // disable dfs makes win10 shares with ms account work
        prop.put("jcifs.smb.client.dfs.disabled", String.valueOf( !p || psp.getBoolean( "disable_DFS", true ) ) );
        // this is needed to allow connection to MacOS 10.12.5 and higher according to https://github.com/IdentityAutomation/vfs-jcifs-ng/blob/master/src/test/java/net/idauto/oss/jcifsng/vfs2/provider/SmbProviderTestCase.java
        // prop.put("jcifs.smb.client.signingEnforced", "true");

        prop.put("jcifs.smb.useRawNTLM", String.valueOf( !p || psp.getBoolean( "use_raw_NTLM", false ) ) );
        prop.put("jcifs.smb.client.disableSpnegoIntegrity", String.valueOf( !p || psp.getBoolean( "disable_Spnego_Integrity", false ) ) );

        PropertyConfiguration propertyConfiguration = null;
        try {
            propertyConfiguration = new PropertyConfiguration(prop);
        } catch ( CIFSException e) {
            Log.w(TAG, "Caught a CIFSException on PropertyConfiguration", e );
        }

        return new BaseContext(propertyConfiguration);
    }

    CIFSContext getCIFSContext( boolean force_smb1, boolean with_credentials ) {
        boolean in_domain = false;
        String user_name = null;
        if( credentials != null ) {
            user_name = credentials.getUserName();
            if( user_name != null ) {
                user_name = user_name.replace( ';', '\\' );
                in_domain = user_name.indexOf( '\\' ) > 0;
            }
        }
        CIFSContext base_context = null;
        if( !force_smb1 ) {
            if( baseContext == null )
                baseContext = createContext( false, in_domain );
            base_context = baseContext;
        } else {
            Log.d( TAG, "Forcing the version SMB1" );
            if( baseContext1 == null )
                baseContext1 = createContext( true, in_domain );
            base_context = baseContext1;
        }
        if( base_context == null ) {
            Log.e( TAG, "No base context!!!" );
            return null;
        }
        if( with_credentials && credentials != null ) {
            Log.d( TAG, "Having the credentials, creating the NTLM auth context" );
            jcifs.Credentials auth = new NtlmPasswordAuthenticator( user_name, credentials.getPassword() );
            authContext = base_context.withCredentials( auth );
        } else {
            Log.d( TAG, "No credentials, creating an anonymous context" );
            authContext = as_guest ? base_context.withGuestCrendentials() : base_context.withAnonymousCredentials();
        }
        return authContext;
    }

    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        if( baseContext != null ) {
            final CIFSContext base_context = baseContext;
            new Thread( new Runnable() {
                @Override
                public void run() {
                    try {
                        base_context.close();
                    } catch( CIFSException e ) {
                        Log.e( TAG, "", e );
                    }
                }
            } );
        }
        if( baseContext1 != null ) {
            final CIFSContext base_context = baseContext1;
            new Thread( new Runnable() {
                @Override
                public void run() {
                    try {
                        base_context.close();
                    } catch( CIFSException e ) {
                        Log.e( TAG, "", e );
                    }
                }
            } );
        }
    }

    public SMBAdapter() {
    }
    
    public SMBAdapter( Context ctx_ ) {
        super( ctx_ );
        try {
            smb_res = ctx.getResources();
            Utils.changeLanguage( ctx, smb_res );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }
    
    @Override
    public String toString() {
        if( uri == null )
            return "";
        String ui = uri.getUserInfo();
        if( ui != null && credentials == null )
            return Favorite.screenPwd( uri );
        if( credentials == null )
            return uri.toString();
        Uri uri_with_cred_scr = Utils.getUriWithAuth( uri, credentials.getUserName(), Credentials.pwScreen );
        return uri_with_cred_scr.toString();    
    }
    @Override
    public Uri getUri() {
        return Utils.updateUserInfo( uri, null );
    }
    @Override
    public void setUri( Uri uri_ ) {
        uri = uri_;
        if( uri == null ) return;
        
        String ui = uri.getUserInfo();
        if( Utils.str( ui ) ) {
            setCredentials( new Credentials( ui ) );
            uri = Utils.updateUserInfo( uri, null );
        }
        String host = uri.getHost();
        if( !Utils.str( host ) ) return;
        String path = uri.getPath(); // JCIFS requires slash on the end to successful copy operations
        boolean ch = path == null || path.length() == 0;
        if( ch )
            path = SLS;
        else {
            ch = path.lastIndexOf( SLC ) != path.length()-1;
            if( ch ) 
                path = path + SLS;
        }
        if( ch )
            uri = uri.buildUpon().encodedPath( path ).build();
    }

    @Override
    public String getScheme() {
        return "smb";
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case REAL:
        case RECEIVER:
        case SEARCH:
        case FILTER:
        case DIRSIZES:
        case MULT_RENAME:
        case RENAME:
        case DELETE:
            return true;
        default: return super.hasFeature( feature );
        }
    }
    
    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        try {
            if( tmp_uri != null )
                setUri( tmp_uri );
            if( uri == null )
                return false;
            
            if( reader != null ) { // that's not good.
                if( reader.isAlive() ) {
                    Log.w( TAG, "Busy..." );
                    reader.interrupt();
                    Thread.sleep( 500 );
                    if( reader.isAlive() ) 
                        return false;      
                }
            }
            String host = uri.getHost();
            String uri_s = uri.toString();
            String unesc = Utils.unEscape( uri_s );

            search = SearchProps.parseSearchQueryParams( ctx, uri );

            reader = new ListEngine( unesc, this, smb_res, search, pass_back_on_done );
            reader.setHandler( readerHandler );
            reader.setName( TAG + ".ListEngine" );
            return commander.startEngine( reader );
        }
        catch( Exception e ) {
            Log.e( TAG, null, e );
        }
        catch( NoSuchFieldError e ) {
            Log.e( TAG, null, e );
            notify( "Try to install the latest version of the plugin", Commander.OPERATION_FAILED );
            return false;
        }
        notify( "Fail", Commander.OPERATION_FAILED );
        return false;
    }
    @Override
    protected void onReadComplete() {
        if( reader instanceof ListEngine ) {
            parentLink = uri == null || uri.getHost() == null || uri.getHost().length() <= 1 ? SLS : PLS;
            ListEngine list_engine = (ListEngine)reader;
            items = list_engine.getItems();
            numItems = items != null ? items.length + 1 : 1;
            reSort();
            notifyDataSetChanged();
        }
    }

    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        menu.add(0, OP_RELOGIN, 0, "Relogin" );
        super.populateContextMenu( menu, acmi, num );
    }

    public void doIt( int command_id, SparseBooleanArray cis ) {
        if( command_id == OP_RELOGIN ) {
            Message msg = Message.obtain();
            msg.what = Commander.OPERATION_FAILED_LOGIN_REQUIRED;
            Bundle b = msg.getData();
            b.putString( Commander.MESSAGE_STRING, uri.toString() );
            b.putParcelable( Commander.NOTIFY_CRD, credentials );
            commander.notifyMe( msg );
        }
    }

    public final SmbFile[] bitsToSmbFiles( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    counter++;
            SmbFile[] subItems = new SmbFile[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    subItems[j++] = items[ cis.keyAt( i ) - 1 ].f;
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "bitsToNames()", e );
        }
        return null;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        try {
            SmbFile[] subItems = bitsToSmbFiles( cis );
            if( subItems == null ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            if( move && getScheme().equals( to.getScheme() ) ) {
                Uri to_uri = to.getUri();
                if( to_uri.getHost().equalsIgnoreCase( uri.getHost() ) ) {
                    notify( Commander.OPERATION_STARTED );
                    MoveEngine me = new MoveEngine( ctx, subItems, new SmbFile( to_uri.toString(), authContext ) );
                    commander.startEngine( me );
                    return true;
                }
            }
            notify( Commander.OPERATION_STARTED );
            Engine eng = new CopyFromEngine( commander, subItems, move, to );
            commander.startEngine( eng );
            return true;
        }
        catch( Exception e ) {
            notify( "Failed to proceed.", Commander.OPERATION_FAILED );
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
        commander.startEngine( new MkDirEngine( commander.getContext(), uri.toString() + SLS + name, authContext ) );
    }

    @Override
    public boolean deleteItem( Item item ) {
        if( !(item instanceof SmbItem ) )
            return false;
        SmbFile[] list = new SmbFile[1];
        list[0] = ((SmbItem)item).f;
        return deleteItems( list );
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        return deleteItems( bitsToSmbFiles( cis ) );
    }

    private boolean deleteItems( SmbFile[] smb_files ) {
        try {
            if( smb_files != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new DelEngine( commander.getContext(), smb_files ) );
                return true;
            }
        }
        catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }

    @Override
    public Uri getItemUri( int position ) {
        Uri u = getUri();
        if( u == null ) return null;
        if( search != null ) {
            try {
                Item item = items[position - 1];
                SmbFile sf = (SmbFile)item.origin;
                String p = sf.getUncPath();
                if( p != null )
                    p = p.replace( '\\', '/' );
                return u.buildUpon().clearQuery().path( sf.getShare() + p ).build();
            } catch( Exception e ) {
                Log.e( TAG, u.toString() + " " + position, e );
            }
            return null;
        }
        String fn = getItemName( position, false );
        if( fn == null )
            return null;
        return u.buildUpon().appendEncodedPath( fn ).build();
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( items != null && position > 0 && position <= items.length ) {
            if( full ) {
                Uri u = getItemUri( position );
                if( u != null ) return u.toString();
            }
            else return items[position-1].name;
        }
        return null;
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
                String path = uri.getPath();
                int len_ = path.length()-1;
                if( len_ > 0 ) {
                    if( path.charAt( len_ ) == SLC )
                        path = path.substring( 0, len_ );
                    path = path.substring( 0, path.lastIndexOf( SLC ) );
                    if( path.length() == 0 )
                        path = SLS;
                    commander.Navigate( uri.buildUpon().encodedPath( path ).build(), null, uri.getLastPathSegment() + SLS );
                }
                else {
                    commander.Navigate( Uri.parse( "smb://" ), null, null );
                }
            }
            return;
        }
        if( items == null || position < 0 || position > items.length )
            return;
        SmbItem item = items[position - 1];
        SmbFile f = item.f;
        synchronized( f ) {
            if( item.dir ) {
                String can = f.getCanonicalPath();
                String fix = SmbItem.fixName( can );
                String esc = escapeSMBcanonical( fix );
                commander.Navigate( Uri.parse( esc ), null, null );
            } else {
                Uri auth_item_uri = getUri().buildUpon().appendEncodedPath( f.getName() ).build();
                commander.Open( auth_item_uri, credentials );
            }
        }
    }
    private final static String escapeSMBcanonical( String s ) {
        return s.replaceAll( "%", "%25" )
                .replaceAll( "#", "%23" )
                .replaceAll( "\\?", "%3F" )
                .replaceAll( "@", "%40" );
    }

    public final String getStatusString( int code ) {
        // see https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-erref/596a1078-e883-4972-9bbc-49e60bebca55
        switch( code ) {
            case 0xC0000001: return getString( R.string.STATUS_UNSUCCESSFUL );
            case 0xC000026E: return getString( R.string.STATUS_VOLUME_DISMOUNTED );
        }
        return null;
    }

    public final String getString( int res_id ) {
        try {
            if( smb_res == null )
                smb_res = ctx.getResources();
            return smb_res.getString( res_id );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }


    @Override
    public boolean receiveItems( String[] fileURIs, int move_mode ) {
        try {
            if( fileURIs == null || fileURIs.length == 0 ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            File[] list = Utils.getListOfFiles( fileURIs );
            if( list == null ) {
                notify( "Something wrong with the files", Commander.OPERATION_FAILED );
                return false;
            }
            notify( Commander.OPERATION_STARTED );
            String cur_str = uri.toString();
            String unesc = Utils.unEscape( cur_str );
            CopyToEngine cte = new CopyToEngine( commander, this, list, unesc, move_mode );
            commander.startEngine( cte );
            return true;
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public boolean renameItem( int position, String newName, boolean copy ) {
        if( position <= 0 || position > items.length )
            return false;
        return renameItem( items[position - 1], newName, copy );
    }

    @Override
    public boolean renameItem( Item item, String newName, boolean copy ) {
        if( copy ) {
            notify( s( Utils.RR.not_supported.r() ), Commander.OPERATION_FAILED );
            return false;
        }
        if( !( item instanceof SmbItem ) )
            return false;
        notify( Commander.OPERATION_STARTED );
        commander.startEngine( new RenEngine( commander.getContext(), ((SmbItem)item).f,
                                Utils.mbAddSl( uri.toString() ) + newName, authContext ) );
        return true;
    }

    @Override
    public boolean renameItems( SparseBooleanArray cis, String pattern_str, String replace_to ) {
        SmbFile[] list = bitsToSmbFiles( cis );
        try {
            MultRenEngine re = new MultRenEngine( ctx, authContext, list, pattern_str, replace_to );
            commander.startEngine( re );
        } catch( Exception e ) {
            Log.e( TAG, "Can't rename to " + replace_to + " with pattern " + pattern_str, e );
        }
        return false;
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            SmbFile[] list = bitsToSmbFiles( cis );
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new CalcSizesEngine( ctx, list, authContext ) );
        }
        catch(Exception e) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public synchronized void setCredentials( Credentials crd ) {
        credentials = crd;
        authContext = null;
    }
    @Override
    public Credentials getCredentials() {
        return credentials;
    }
    @Override
    public Object getItem( int position ) {
        if( position == 0 ) {
            Item item = new Item();
            item.name = parentLink;
            return item;
        }
        else {
            if( items != null && position > 0 && position <= items.length )
                return items[position - 1];
        }
        return null;
    }

    @Override
    protected int getPredictedAttributesLength() {
        try {
            return search != null ? uri.getPath().length() + 10 : 0;
        } catch( Exception e ) {}
        return 0;
    }

    @Override
    protected void reSort() {
        if( items == null ) return;
        SmbItemPropComparator comp = new SmbItemPropComparator( mode & MODE_SORTING, (mode & MODE_CASE) != CASE_SENS, ascending );
        Arrays.sort( items, comp );
    }

    @Override
    public Item getItem( Uri u ) {
        try {
            if( uri != null && !uri.getHost().equals( u.getHost() ) )
                return null;
            String uri_s = u.toString();
            String unesc = Utils.unEscape( uri_s );

            if( authContext == null )
                getCIFSContext( false, true );
            if( authContext == null )
                return null;
            SmbFile smb_file = new SmbFile( unesc, authContext );
            if( smb_file.exists() ) 
                return new SmbItem( smb_file, 0, null );
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }
    
    @Override
    public InputStream getContent( Uri u, long offset ) {
        try {
            if( uri != null && !uri.getHost().equals( u.getHost() ) )
                return null;
            //Log.d( TAG, "Content for " + u + " offset=" + offset );
            
            String uri_s = u.toString();
            String unesc = Utils.unEscape( uri_s );            
            
            if( authContext == null )
                getCIFSContext( false, true );
            if( authContext == null )
                return null;
            SmbFile smb_file = new SmbFile( unesc, authContext );

            smb_file.connect();
            if( smb_file.exists() && smb_file.isFile() ) {
                InputStream is = smb_file.getInputStream();
                if( offset > 0 )
                    is.skip( offset );
                return is;
            }
        } catch( Throwable e ) {
            Log.e( TAG, Utils.getCause( e ), e );
        }
        return null;
    }

    @Override
    public OutputStream saveContent( Uri u ) {
        try {
            if( uri != null && !uri.getHost().equals( u.getHost() ) )
                return null;
            String uri_s = u.toString();
            String unesc = Utils.unEscape( uri_s );            
            if( authContext == null )
                getCIFSContext( false, true );
            if( authContext == null )
                return null;
            SmbFile smb_file = new SmbFile( unesc, authContext );
            smb_file.connect();
            if( smb_file.exists() && smb_file.isFile() )
                return smb_file.getOutputStream();
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }
    
    @Override
    public void closeStream( Closeable s ) {
        try {
            s.close();
        } catch( IOException e ) {
            e.printStackTrace();
        }
    }
    static SMBAdapter createInstance() {
        return new SMBAdapter();
    }
    
    @Override
    public IReciever getReceiver() {
        return this;
    }

    @Override
    public IReceiver getReceiver( Uri dir )  {
        return new Receiver( dir, authContext );
    }
}
