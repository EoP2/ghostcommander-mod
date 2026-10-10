package com.ghostsq.commander.smb;

import android.content.res.Resources;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.FilterProps;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import java.io.InputStream;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;

import jcifs.CIFSContext;
import jcifs.Configuration;
import jcifs.DialectVersion;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbUnsupportedOperationException;
import jcifs.util.transport.TransportException;

import static com.ghostsq.commander.adapters.CommanderAdapter.MODE_DIRSZ;
import static com.ghostsq.commander.adapters.CommanderAdapter.SHOW_DIRSZ;

class ListEngine extends Engine {
    private static final String TAG = "smb.ListEngine"; 
    private String      smb_url = null;
    private String      pass_back_on_done;
    private SmbItem[]   items_tmp = null;
    private Resources   smb_res = null;
    private SMBAdapter  owner;
    private int         adMode;
    private SearchProps sq;
    private int         depth = 0;
    private int         pathLen = 0;

    ListEngine( String url, SMBAdapter owner_, Resources smb_res, SearchProps sq, String pass_back_on_done ) {
        this.owner = owner_;
        this.adMode = owner.getMode();
        this.smb_url = url;
        this.smb_res = smb_res;
        this.sq = sq;
        this.pass_back_on_done = pass_back_on_done;
    }
    public SmbItem[] getItems() {
        return items_tmp;
    }
    
    @Override
    public void run() {
        try {
            Log.d( TAG, "URI: " + smb_url );

            if( smb_url == null || smb_url.length() < 4 ) {
                sendProgress( smb_res.getString( R.string.nothing_read ), Commander.OPERATION_FAILED, pass_back_on_done );
                return;
            }
            if( smb_url.charAt( smb_url.length() - 1 ) != CommanderAdapterBase.SLC )
                smb_url += CommanderAdapterBase.SLS;
            SmbFile[] list = null;
            SmbFile f;
            CIFSContext cifs_ctx = null;
            boolean browse_mode = "smb://".equals( smb_url ) || smb_url.toLowerCase().indexOf( "smb://workgroup" ) == 0;
            for( int attempt = 1; attempt <= 3; attempt++ ) {
                Log.d( TAG, "Attempt #" + attempt );
                if( cifs_ctx == null )
                    cifs_ctx = owner.getCIFSContext( browse_mode, true );
                if( cifs_ctx == null ) {
                    Log.e( TAG, "No CIFS context" );
                    error( smb_res.getString( R.string.nothing_returned, smb_url ) + "\nCIFS context is null" );
                    sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                    return;
                }
                Configuration cfg = cifs_ctx.getConfig();
                Log.d( TAG, "Supporting SMB versions, min: " + cfg.getMinimumVersion() + ", max: " + cfg.getMaximumVersion() );
                f = new SmbFile( smb_url, cifs_ctx );
                try {
                    if( sq == null )
                        list = f.listFiles();
                    else {
                        String p = f.getUncPath();
                        pathLen = p != null ? p.length() : 0;
                        ArrayList<SmbFile> result = new ArrayList<>();
                        searchInDirectory( f, result );
                        list = new SmbFile[result.size()];
                        result.toArray( list );
                    }
                } catch( SmbUnsupportedOperationException uoe ) {
                    Log.w( TAG, "Unsupported Operation exception.", uoe );
                    boolean was_smb1 = cfg.getMinimumVersion() == DialectVersion.SMB1 &&
                                       cfg.getMaximumVersion() == DialectVersion.SMB1;
                    if( browse_mode ) {
                        if( was_smb1 ) {
                            error( owner.getString( R.string.msbrowse_err ) );
                            sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                            return;
                        }
                        cifs_ctx = owner.getCIFSContext( true, true );
                        continue;
                    }
                    cifs_ctx = owner.getCIFSContext( !was_smb1, true );
                    continue;
                } catch( SmbAuthException ae ) {
                    Log.w( TAG, "SMB Auth exception: " + ae.getNtStatus() + " " + ae );
                    jcifs.Credentials crd = cifs_ctx.getCredentials();
                    boolean was_with_auth = crd != null && !crd.isAnonymous() && !crd.isGuest();
                    if( was_with_auth ) {
                        boolean do_simple = false;
                        if( "smb://".equals( smb_url ) )
                            do_simple = true;
                        else {
                            try {
                                if( f.getType() == SmbFile.TYPE_WORKGROUP )
                                    do_simple = true;
                            } catch( Exception gte ) {}
                        }
                        if( do_simple ) {
                            Log.d( TAG, "To browse the networks the credentials shall be discarded." );
                            error( "Trying without credentials..." );
                            cifs_ctx = owner.getCIFSContext( true, false );
                            continue;
                        }
                    }
                    Log.d( TAG, "Asking for the credentials..." );
                    sendLoginReq( smb_url, owner.getCredentials(), pass_back_on_done );
                    return;
                } catch( SmbException e ) {
                    int status = e.getNtStatus();
                    if( status != 0 ) {
                        String status_s = owner.getStatusString( status );
                        if( status_s != null )
                            error( status_s );
                    }
                    String msg = e.getMessage();
                    Throwable cause = e.getCause();
                    if( cause instanceof TransportException ) {
                        error( msg );
                        TransportException te = (TransportException)cause;
                        if( te.getCause() instanceof SocketException ) {
                            error( "Reason:\n" + te.getMessage() );
                            sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                            return;
                        }
                    }
                    else if( cause instanceof UnknownHostException ) {
                        error( msg + " unknown host.");
                        sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                        return;
                    }
                    if( msg != null && "0x".equals( msg.substring( 0, 2 ) ) ) {
                        if( BuildConfig.DEBUG )
                            error( msg );
                    } else {
                        error( msg );
                    }
                    Throwable ec = e.getCause();
                    if( ec != null ) error( "Cause:\n" + ec.getMessage() );
                    sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                    return;
                } catch( Exception e ) {
                    Log.e( TAG, smb_url, e );
                    error( owner.getString( R.string.exception ) );
                    if( BuildConfig.DEBUG )
                        error( e.getMessage() );
                    sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                    return;
                }
                if( list == null ) {
                    error( smb_res.getString( R.string.nothing_returned, smb_url ) + "\nreturned list is null" );
                    sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
                    return;
                }

                ArrayList<SmbItem> items_list = new ArrayList<SmbItem>( list.length );

                boolean dirsz = ( adMode & MODE_DIRSZ  ) == SHOW_DIRSZ;
                final double conv = 100. / list.length;
                int j = 0;
                for( int i = 0; i < list.length; i++ ) {
                    SmbFile sf = list[i];
                    if( toShow( sf ) ) {
                        SmbItem smb_item = new SmbItem( sf, adMode & CommanderAdapter.MODE_ICONS, smb_res );
                        FilterProps fp = owner.getFilter();
                        if( fp != null && !fp.match( smb_item ) )
                            continue;
                        items_list.add( smb_item );
                        if( sq != null ) {
                            String p = sf.getUncPath();
                            if( p != null ) {
                                if( pathLen < p.length() )
                                    p = p.substring( pathLen );
                                int fnl = sf.getName().length();
                                if( fnl < p.length() )
                                    smb_item.attr = p.substring( 0, p.length() - fnl ).replace( '\\', '/' );
                                else
                                    smb_item.attr = "./";
                            }
                            smb_item.origin = sf;
                        } else {
                            try {
                                if( dirsz && sf.isDirectory() ) {
                                    sendProgress( smb_item.name, (int)(i * conv) );
                                    long dir_sz = getSizes( sf );
                                    if( dir_sz >= 0 )
                                        smb_item.size = dir_sz;
                                }
                            } catch( Throwable t ) {
                                Log.e( TAG, "File: " + smb_item.name, t );
                            }
                        }
                    }
                }
                items_tmp = new SmbItem[items_list.size()];
                items_list.toArray( items_tmp );
                sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                return;
            }
            error( smb_res.getString( R.string.nothing_returned, smb_url ) );
            if( BuildConfig.DEBUG )
                error( "All attempts were exhausted." );
        } catch( OutOfMemoryError err ) {
            error( "Out Of Memory" );
        } catch( Throwable t ) {
            Log.e( TAG, "Exception :((", t );
            error( "Exception: " + t.getMessage() );
            
        } finally {
            super.run();
        }
        sendProgress( errMsg, Commander.OPERATION_FAILED, pass_back_on_done );
    }

    protected final boolean toShow( SmbFile f ) {
        try {
            if( ( adMode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.HIDE_MODE ) {
                if( f.getName().charAt( 0 ) == '.' ) return false;
                if( ( f.getAttributes() & SmbFile.ATTR_HIDDEN ) != 0 ) return false;
            }
            int type = f.getType();
            if( type == SmbFile.TYPE_PRINTER || type == SmbFile.TYPE_NAMED_PIPE ) return false;
            return true;
        } catch( Exception e ) {
            Log.e( TAG, "Exception on file " + f );
        }
        return false;
    }

    private long getSizes( SmbFile dir ) throws Exception {
        Thread.yield();
        SmbFile[] children = dir.listFiles();
        if( children == null )
            return -1;
        long count = 0;
        for( SmbFile f : children ) {
            if( isStopReq() )
                return 0;
            if( f.isDirectory() ) {
                if( depth++ > 60 )
                    error( owner.ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
                else {
                    long sz = getSizes( f );
                    if( sz < 0 )
                        return -1;
                    count += sz;
                }
                depth--;
            } else {
                   count += f.length();
            }
        }
        return count;
    }

    private int searchInDirectory( SmbFile dir, ArrayList<SmbFile> result ) throws Exception {
        long cur_time = System.currentTimeMillis();
        if( cur_time - progress_last_sent > DELAY ) {
            progress_last_sent = cur_time;
            sendProgress( dir.getPath(), progress = 0 );
        }
        SmbFile[] children = dir.listFiles();
        if( children == null ) return 0;
        final double conv = 100. / children.length;
        int i = 0;
        for( SmbFile f : children ) {
            i++;
            if( isStopReq() ) return 0;
            int np = (int)(i * conv);
            if( np - conv + 1 >= progress ) {
                cur_time = System.currentTimeMillis();
                if( sq.content != null || cur_time - progress_last_sent > 500 ) {
                    progress_last_sent = cur_time;
                    sendProgress( dir.getPath(), progress = np );
                }
            }
            if( isMatched( f ) ) {
                result.add( f );
            }
            if( !sq.olo && f.isDirectory() ) {
                if( depth++ > 30 )
                    error( owner.ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
                else
                    searchInDirectory( f, result );
                depth--;
            }
        }
        sendProgress( dir.getPath(), 100 );
        return i;
    }

    private final boolean isMatched( SmbFile f ) {
        if( f == null ) return false;
        try {
            boolean dir = f.isDirectory();
            if( dir ) {
                if( !sq.dirs  ) return false;
            } else {
                if( !sq.files ) return false;
            }
            long modified = f.lastModified();
            if(  sq.mod_after != null && modified <  sq.mod_after.getTime() ) return false;
            if( sq.mod_before != null && modified > sq.mod_before.getTime() ) return false;
            long size = f.length();
            if( size < sq.larger_than || size > sq.smaller_than )
                return false;
            if( !sq.match( f.getName() ) )
                return false;
            if( sq.content != null && !dir && !searchInsideFile( f, sq.content ) )
                return false;
            return true;
        }
        catch( Exception e ) {
            Log.e( TAG, f.getName(), e );
        }
        return false;
    }
    private final boolean searchInsideFile( SmbFile f, String search_for ) {
        try( InputStream is = f.getInputStream() ) {
            return searchInContent( is, f.length(), f.getName(), search_for );
        }
        catch( Exception e ) {
            Log.e( TAG, "File: " + f.getName() + ", str=" + search_for, e );
        }
        return false;
    }
}
