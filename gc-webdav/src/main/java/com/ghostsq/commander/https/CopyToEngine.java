package com.ghostsq.commander.https;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Utils;

import shaded.org.apache.http.HttpEntity;
import shaded.org.apache.http.HttpHost;
import shaded.org.apache.http.StatusLine;
import shaded.org.apache.http.client.AuthCache;
import shaded.org.apache.http.client.methods.CloseableHttpResponse;
import shaded.org.apache.http.client.methods.HttpHead;
import shaded.org.apache.http.client.methods.HttpPut;
import shaded.org.apache.http.client.protocol.HttpClientContext;
import shaded.org.apache.http.entity.ContentType;
import shaded.org.apache.http.entity.FileEntity;
import shaded.org.apache.http.impl.auth.BasicScheme;
import shaded.org.apache.http.impl.client.BasicAuthCache;
import org.apache.jackrabbit.webdav.client.methods.HttpDelete;
import org.apache.jackrabbit.webdav.client.methods.HttpMkcol;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

class CopyToEngine extends WebDAVEngineBase  
{
    private   File[]    mList;
    private   boolean   move = false;
    private   boolean   del_src_dir = false;
    private   WifiLock  wifiLock;

    private   HttpClientContext http_context;

    private class ProgressFileEntity extends FileEntity {
        private   long      startTime;
        private   long      fileLen = 0, curFileDone = 0, secDone = 0;
        private   String    progressMessage = null;

        public ProgressFileEntity( final File file, final ContentType contentType, String msg ) {
            super( file, contentType );
            this.progressMessage = msg;
            this.fileLen = file.length();
        }

        @Override
        public void writeTo( final OutputStream outStream ) throws IOException {
            final InputStream inStream = new FileInputStream( super.file );
            try {
                final byte[] tmp = new byte[OUTPUT_BUFFER_SIZE];
                int l;
                while ((l = inStream.read(tmp)) != -1) {
                    if( CopyToEngine.this.isStopReq() ) return;
                    outStream.write(tmp, 0, l);
                    report( l );
                }
                outStream.flush();
            } finally {
                inStream.close();
            }
        }

        public final void report( int size ) {
            curFileDone += size;
            secDone += size;
            long cur_time = System.currentTimeMillis();
            long time_delta = cur_time - startTime;
            if( curFileDone == 0 || curFileDone == fileLen || time_delta > DELAY ) {    // once a sec. only
                int  progr = (int)( curFileDone * 100. / fileLen );
                int  speed = (int)( MILLI * secDone / time_delta );
                CopyToEngine.this.sendProgress( progressMessage, progr, -1, speed );
                startTime = cur_time;
                secDone = 0;
            }
        }

    }

    CopyToEngine( WebDAVAdapter a, File[] list, int move_mode_ ) {
        super( a );
        mList = list;
        move = ( move_mode_ & CommanderAdapter.MODE_MOVE ) != 0;
        del_src_dir = ( move_mode_ & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
        WifiManager manager = (WifiManager)owner.ctx.getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( android.os.Build.VERSION.SDK_INT >= 12 ? 3 : WifiManager.WIFI_MODE_FULL, TAG );
        wifiLock.setReferenceCounted( false );
    }

    @Override
    public void run() {
        try {
            super.getClient();
            if( client == null ) {
                sendProgress( null, Commander.OPERATION_FAILED );
                return;
            }
            Uri dest_uri = owner.getUri();
            AuthCache authCache = new BasicAuthCache();
            authCache.put( new HttpHost(dest_uri.getHost()), new BasicScheme() );
            http_context = HttpClientContext.create();
            http_context.setAuthCache(authCache);
            wifiLock.acquire();
            int cnt = copyFiles( mList, dest_uri );
            if( del_src_dir ) {
                File src_dir = mList[0].getParentFile();
                if( src_dir != null )
                    src_dir.delete();
            }
            sendResult( Utils.getOpReport( owner.ctx, cnt, move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
            super.run();
        } catch( Throwable e ) {
            sendProgress( e.getLocalizedMessage(), Commander.OPERATION_FAILED );
            Log.e( TAG, "", e );
        }
        finally {
            wifiLock.release();
        }
    }

    private final int copyFiles( File[] list, Uri dest ) {
        int counter = 0;
        try {
            long num = list.length;
            long dir_size = 0, byte_count = 0;
            for( int i = 0; i < num; i++ ) {
                File f = list[i];               
                if( !f.isDirectory() )
                    dir_size += f.length();
            }
            double conv = PERC/(double)dir_size;
            for( int i = 0; i < num; i++ ) {
                if( isStopReq() ) {
                    error( owner.ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                File f = list[i];
                if( f == null || !f.exists() ) continue;
                Log.v( TAG, "Uploading " + f.getAbsolutePath() );
                boolean dir = f.isDirectory(); 
                
                String fn = Uri.encode( f.getName() );
                if( dir ) fn += "/";
                Uri    file_uri = dest.buildUpon().appendEncodedPath( fn ).build();
                String file_url = file_uri.toString();
                if( dir ) {
                    HttpMkcol mcm = new HttpMkcol( file_url );
                    try {
                        client.execute( mcm, http_context );
                    } catch( Exception e ) {
                        Log.e( TAG, file_url, e );
                        error( owner.ctx.getString( Utils.RR.fail_del.r(), file_url ) );
                        break;
                    }
                    counter += copyFiles( f.listFiles(), file_uri );
                    if( !noErrors() ) break;
                } else if( f.isFile() ) {
                    boolean exists = false;
                    HttpHead hm = new HttpHead( file_url );
                    try {
                        CloseableHttpResponse chr = client.execute( hm, http_context );
                        StatusLine sl = chr.getStatusLine();
                        exists = sl.getStatusCode() < 300;
                    } catch( Exception e ) {
                        Log.e( TAG, file_url, e );
                    }
                    if( exists ) {
                        int res = askOnFileExist( owner.ctx.getString( Utils.RR.file_exist.r(), fn ), owner.commander );
                        if( res == Commander.ABORT ) break;
                        if( res == Commander.SKIP )  continue;
                        if( res == Commander.REPLACE ) {
                            HttpDelete dm = new HttpDelete( file_url );
                            try {
                                client.execute( dm, http_context );
                            } catch( Exception e ) {
                                error( owner.ctx.getString( Utils.RR.fail_del.r(), file_url ) );
                            }  finally {
                                dm.releaseConnection();
                            }
                        }
                    }

                    HttpPut pm = new HttpPut( file_url );
                    try {
                        String path_name = f.getAbsolutePath();
                        int pnl = path_name.length();
                        String cur_op_s = owner.ctx.getString( Utils.RR.uploading.r(), 
                                pnl > CUT_LEN ? "\u2026" + path_name.substring( pnl - CUT_LEN ) : path_name );
                        sendProgress( cur_op_s, 0, (int)(byte_count * conv), 0 );
                        
// FIXME NEED TESTING!!!!!!!!!!!!


                        String mime = Utils.getMimeByExt( Utils.getFileExt( f.getName() ) );
                        if( !Utils.str( mime ) || ("*"+"/*").equals( mime ) )
                            mime = "application/octet-stream";
                        HttpEntity http_ent = new ProgressFileEntity( f, ContentType.create( mime ), cur_op_s );
                        pm.setEntity( http_ent );   // The entity MUST be repeatable! (http_ent.isRepeatable() must return true)
                        CloseableHttpResponse chr = client.execute( pm, http_context );
                        StatusLine sl = chr.getStatusLine();
                        int st_code = sl.getStatusCode();
                        Log.d( TAG, "HTTP status=" + st_code + " " + sl.getReasonPhrase() );
                        if( st_code >= 400 ) {
                            Log.e( TAG, "Fail" );
                            error( "Failed " + sl.getReasonPhrase() );
                            break;
                        }
/*
                        fis.report( 0 );
                        fis.close();
 */
                    } catch( Exception e ) {
                        String e_msg = e.getMessage();
                        error( owner.ctx.getString( Utils.RR.rtexcept.r(), file_url, e_msg != null ? e_msg : "" ) );
                        Log.e( TAG, file_url, e );
                        break;
                    } finally {
                        pm.releaseConnection();
                    }
                }
                // not sure if it possible to change the date
                // Neither cadaver nor WinScp can do that
                //new_file.setLastModified( f.lastModified() ); // TODO
                counter++;
                if( move && !f.delete() ) {
                    error( owner.ctx.getString( Utils.RR.cant_del.r(), f.getCanonicalPath() ) );
                    break;
                }
            }   // for
        } catch( Exception e ) {
            e.printStackTrace();
            error( e.getLocalizedMessage() );
        }
        return counter;
    }
}
