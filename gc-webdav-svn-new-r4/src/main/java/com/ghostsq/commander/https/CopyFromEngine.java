package com.ghostsq.commander.https;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.IReceiver;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.HttpEntity;
import shaded.org.apache.http.HttpStatus;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpGet;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

class CopyFromEngine extends PropFinder
{
    private   Commander     commander;
    private   CommanderAdapter rcp;
    private   Item[]        list;
    private   boolean       move;
    private   WifiLock      wifiLock;
    public    String        cur_fn, cur_op_s, cur_f_size;
    public    double        cur_conv;

    CopyFromEngine( Commander c, WebDAVAdapter a, Item[] list_, boolean move_, CommanderAdapter rcp ) {
        super( a );
        commander = c;
        list        = list_;
        move        = move_;
        this.rcp = rcp;

        WifiManager manager = (WifiManager)owner.ctx.getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( WifiManager.WIFI_MODE_FULL, TAG );
        wifiLock.setReferenceCounted( false );
    }
    @Override
    public void run() {
        try {

            if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
                Context ctx = owner.ctx;
                FSAdapter fsa = new FSAdapter( ctx );
                fsa.setUri( Uri.parse( Utils.createTempDir( ctx ).getAbsolutePath() ) );
                super.recipient = rcp.getReceiver();
                rcp = fsa;
            } else
                recipient = null;

            wifiLock.acquire();
            super.getClient();
            int total = copyFiles( list, "", rcp.getUri() );
            
            if( recipient != null ) {
                  sendReceiveReq( new File( rcp.getUri().getPath() ), move );
                  return;
            }
            // FIXME: when only one empty dir was copied, it reports "Nothing was copied" !
            sendResult( Utils.getOpReport( owner.ctx, total, move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
        } catch( InterruptedException e ) {
            Log.e( TAG, null, e );
            error( owner.ctx.getString( Utils.RR.interrupted.r() ) );
        } catch( Exception e ) {
            Log.e( TAG, null, e );
            error( owner.ctx.getString( Utils.RR.failed.r(), e.getLocalizedMessage() ) );
        } finally {
            wifiLock.release();
        }
    }
    
    private final int copyFiles( Item[] l, String path, Uri dest_dir_uri ) throws InterruptedException {
        int counter = 0;
        try {
            Context ctx = owner.ctx;
            IReceiver receiver = rcp.getReceiver( dest_dir_uri );
            int num = l.length;
            long total_copied = 0, total_size = 0;
            for( Item item : l ) {
                if( item.dir ) continue;
                total_size += item.size;
            }
            for( int i = 0; i < num; i++ ) {
                if( stop || isInterrupted() )
                    throw new Exception( ctx.getString( Utils.RR.interrupted.r() ) );
                DavItem item = (DavItem)l[i];
                String file_name = item.name;
                String rel_path_name = path + file_name;
                if( item.dir ) {
                    Uri dest_subdir_uri = super.handleDirOnReceiver( commander, receiver, file_name );
                    if( dest_subdir_uri == null )
                        break;
                    Item[] children = super.getItems( item.getURI( sBaseUri ) );
                    counter += copyFiles( children, rel_path_name + CommanderAdapterBase.SLS, dest_subdir_uri );
                    if( !noErrors() ) break;
                    if( move ) {
// TODO                        adapter.api.delete( entry.path );
                    }
                } else {
                    Uri dest_item_uri = receiver.getItemURI( file_name, false );
                    int res = handleItemOnReceiver( commander, receiver, dest_item_uri, file_name );
                    if( res == Commander.ABORT ) break;
                    if( res == Commander.SKIP ) continue;

                    OutputStream os = receiver.receive( file_name );
                    if( os == null ) {
                        Log.e( TAG, "No output stream, file: " + file_name );
                        error( ctx.getString( Utils.RR.rtexcept.r(), file_name, "" ) );
                        break;
                    }


                    Long s_l = item.size;
                    cur_f_size = s_l != null ? Utils.getHumanSize( s_l.longValue() ) : "";
                    cur_fn = file_name;
                    int pnl = cur_fn.length();
                    cur_op_s = ctx.getString( Utils.RR.retrieving.r(),
                            pnl > CUT_LEN ? "\u2026" + cur_fn.substring( pnl - CUT_LEN ) : cur_fn );
                    String item_url = (String)item.origin;
                    if( item_url.charAt( 0 ) == '/' ) {
                        Uri base_uri = owner.getUri();
                        item_url = base_uri.getScheme() + "://" + base_uri.getAuthority() + item_url;
                    }
                    HttpGet gm = new HttpGet( item_url );
                    InputStream is = null;
                    try {
                        CloseableHttpResponse chr = client.execute( gm );
                        StatusLine sl = chr.getStatusLine();
                        if( sl.getStatusCode() != HttpStatus.SC_OK ) {
                              error( sl.getReasonPhrase() );
                              break;
                        }
                        HttpEntity he = chr.getEntity();
                        if( he == null ) {
                              error( "No HTTP entity!" );
                              break;
                        }
                        is = he.getContent();
                        boolean ok;
                        long file_size = item.size;
                        if( file_size < 1000000 || rcp.hasFeature( CommanderAdapter.Feature.FS ) ||
                                ( BuildConfig.DEBUG && file_name.charAt( 0 ) == 'I' ) )
                            ok = super.copyStreamToReceiver( ctx, receiver, is, file_name, file_size, item.date );
                        else
                            ok = super.conveyStreamToReceiver( ctx, receiver, is, file_name, file_size, item.date );
                        if( ok ) {
                            total_copied += file_size;
                            super.progress = (int)( total_copied * 100 / total_size );
                            counter++;
                            if( move ) {
// TODO                        adapter.api.delete( entry.path );                        }
                            }
                        }
                    } catch( Exception e1 ) {
                        error( owner.ctx.getString( Utils.RR.rtexcept.r(), file_name, e1.getLocalizedMessage() ) );
                        break;
                    } finally {
                        if( is != null )
                            is.close();
                        gm.releaseConnection();
                    }                    
                }
            }
        }
        catch( RuntimeException e ) {
            Log.e( TAG, path, e );
            error( owner.ctx.getString( Utils.RR.rtexcept.r(), cur_fn, e.getMessage() ) );
        }
        catch( Exception e ) {
            Log.e( TAG, path, e );
            error( e.getMessage() );
        }
        return counter;
    }
}
