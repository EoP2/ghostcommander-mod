package com.ghostsq.commander.adapters;

import android.content.Context;
import android.location.Address;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.OperationCanceledException;
import android.util.Log;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.SearchProps;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.OSFileCopy;
import com.ghostsq.commander.utils.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Engine extends Thread {
    protected final String TAG = getClass().getName();
    public    final static int  DELAY = 1300, MILLI = 1000;
    protected final static double PERC = 100.;
    protected static int CUT_LEN = 42;
    protected Context ctx;
    protected Handler thread_handler = null;
	protected boolean stop = false;
	protected String  errMsg = null;
	protected long    threadStartedAt = 0;
    protected int     file_exist_behaviour = Commander.UNKNOWN;
    protected byte[]  buf = null;
    protected Engines.IReciever recipient = null;   // deprecated, to delete
	public    long    progress_last_sent = System.currentTimeMillis();
	public    int     progress = 0;


    protected Engine() {
        setEngineName( null );
    }
    protected Engine( long stackSize ) {
        super( null, null, null, stackSize );
        setEngineName( null );
    }
/*
    protected Engine( Handler h ) {
        thread_handler = h; // TODO - distinct the member from the parent class
    }
    protected Engine( Handler h, Runnable r ) {
        super( r );
        thread_handler = h; 
    }
*/    

    public void setContext( Context ctx ) {
        this.ctx = ctx;
    }
    protected void setEngineName( String name ) {
        setName( name == null ? getClass().getName() : name );
    }
    public void setHandler( Handler h ) {
        if( thread_handler != null ) {
            Log.w( TAG, "Handler was already set" );
            return;
        }
        thread_handler = h;
    }
	
    public synchronized boolean reqStop() {
        if( isAlive() ) {
            Log.i( getClass().getName(), "reqStop()" );
            if( stop )
                interrupt();
            else
                stop = true;
            return true;
        }
        else {
            Log.w( getClass().getName(), "Engine thread is not alive" );
            return false;
        }
    }
    public synchronized boolean isStopReq() {
        return stop || isInterrupted();
    }
    protected Bundle wrap( String str ) {
        Bundle b = new Bundle( 1 );
        b.putString( Commander.MESSAGE_STRING, str );
        return b;
    }

    protected final void sendProgress( String s, int p ) {
        sendProgress( s, p, -1, -1 );
    }
    protected final void sendProgress( String s, int p1, int p2 ) {
        sendProgress( s, p1, p2, -1 );
    }
    protected final void sendProgress() {   // launch the spinner 
        if( thread_handler == null ) return;
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_IN_PROGRESS, -1, -1, null );
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        thread_handler.sendMessage( msg );
    }
    public final void sendProgress( String s, int p1, int p2, int speed ) {
        if( BuildConfig.DEBUG ) Log.v( TAG, s + " p1=" + p1 + " p2=" + p2 + " speed=" + speed );
        if( thread_handler == null ) return;
        Message msg = null;
        if( p1 < 0 )
            msg = thread_handler.obtainMessage( p1, -1, -1, wrap( s ) );
        else
            msg = thread_handler.obtainMessage( Commander.OPERATION_IN_PROGRESS, p1, p2, wrap( s ) );
        
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        if( speed >= 0 )
            b.putInt( Commander.NOTIFY_SPEED, speed );
        thread_handler.sendMessage( msg );
    }
    protected final void sendProgress( String s, int p, String cookie ) {
        //Log.v( TAG, "sendProgress: " + s + ", cookie: " + cookie );
        if( thread_handler == null ) return;
        
        Message msg = null;
        if( p < 0 )
            msg = thread_handler.obtainMessage( p, -1, -1, wrap( s ) );
        else
            msg = thread_handler.obtainMessage( Commander.OPERATION_IN_PROGRESS, p, -1, wrap( s ) );
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        b.putString( Commander.NOTIFY_COOKIE, cookie );
        thread_handler.sendMessage( msg );
    }

    protected final void sendFileExist( String s, long src_date, long dst_date, long src_size, long dst_size, int allow_options ) {
        if( thread_handler == null ) return;
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_SUSPENDED_FILE_EXIST, allow_options, -1, wrap( s ) );
        Bundle b = msg.getData();
        if( src_date > 0 )
            b.putLong( Commander.SRC_DATE, src_date );
        if( dst_date > 0 )
            b.putLong( Commander.DST_DATE, dst_date );
        if( src_size >= 0 )
            b.putLong( Commander.SRC_SIZE, src_size );
        if( dst_size >= 0 )
            b.putLong( Commander.DST_SIZE, dst_size );
        thread_handler.sendMessage( msg );
    }

    protected final void sendLoginReq( String s, Credentials crd ) {
        sendLoginReq( s, crd, null );
    }
    protected final void sendLoginReq( String s, Credentials crd, String cookie ) {
        sendLoginReq( s, crd, cookie, false );
    }
    protected final void sendLoginReq( String s, Credentials crd, String cookie, boolean pw_only ) {
        if( thread_handler == null ) return;
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_FAILED_LOGIN_REQUIRED, -1, -1, wrap( s ) );
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        b.putParcelable( Commander.NOTIFY_CRD, crd );
        if( cookie != null )
            b.putString( Commander.NOTIFY_COOKIE, cookie );
        if( pw_only )
            b.putBoolean( "PW_ONLY", true );
        thread_handler.sendMessage( msg );
    }
    
    protected final void sendReceiveReq( String[] items ) {
        sendReceiveReq( items, false );
    }
    
    protected final void sendReceiveReq( String[] items, boolean move ) {
        if( thread_handler == null ) return;
        if( items == null || items.length == 0 ) {
            sendProgress( ctx != null ? ctx.getString( R.string.copy_err ) : "No items", Commander.OPERATION_FAILED );
            return;
        }
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_COMPLETED );
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        b.putBoolean( Commander.NOTIFY_MOVE, move );
        b.putStringArray( Engines.IReciever.NOTIFY_ITEMS_TO_RECEIVE, items );
        thread_handler.sendMessage( msg );
    }
    protected final void sendReceiveReq( File dest_folder ) {
        sendReceiveReq( dest_folder, false );
    }
    protected final void sendReceiveReq( File dest_folder, boolean move ) {
        File[] temp_content = dest_folder.listFiles();
        String[] paths = new String[temp_content.length];
        for( int i = 0; i < temp_content.length; i++ )
            paths[i] = temp_content[i].getAbsolutePath();
        sendReceiveReq( paths, move );
    }    
    protected final void error( String err ) {
        error( err, false );
    }
    protected final void error( String err, boolean flush ) {
        Log.e( getClass().getSimpleName(), err == null ? "Unknown error" : err );
    	if( errMsg == null )
    		errMsg = err;
    	else
    	    errMsg = errMsg + "\n" +err;
    }
    protected final boolean noErrors() {
        return errMsg == null;
    }
    protected final void sendResult( String report ) {
        if( errMsg != null )
            sendProgress( report + "\n - " + errMsg, Commander.OPERATION_FAILED_REFRESH_REQUIRED );
        else {
            sendProgress( report, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
        }
    }
    protected final void sendRefrReq( String posto ) {
        if( thread_handler == null ) return;
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
        if( posto != null ) {
            Bundle b = msg.getData();
            b.putLong( Commander.NOTIFY_TASK, getId() );
            b.putString( Commander.NOTIFY_POSTO, posto );
        }
        thread_handler.sendMessage( msg );
    }
    protected final void sendReport( String s ) {
        if( thread_handler == null ) return;
        Address a;
        Message msg = thread_handler.obtainMessage( Commander.OPERATION_COMPLETED, Commander.OPERATION_REPORT_IMPORTANT, -1, wrap( s ) );
        Bundle b = msg.getData();
        b.putLong( Commander.NOTIFY_TASK, getId() );
        thread_handler.sendMessage( msg );
    }
    protected final void doneReading( String msg, String cookie ) {
        if( errMsg != null )
            sendProgress( errMsg, Commander.OPERATION_FAILED, cookie );
        else {
            sendProgress( msg, Commander.OPERATION_COMPLETED, cookie );
        }
    }
    protected final boolean tooLong( int sec ) {
        if( threadStartedAt == 0 ) return false;
        boolean yes = System.currentTimeMillis() - threadStartedAt > sec * 1000;
        threadStartedAt = 0;
        return yes;
    }
    public String sizeOfsize( long n, String sz_s ) {
        return "\n" + Utils.getHumanSize( n ) + "/" + sz_s;
    }

    final static int OLD_OPTIONS = Commander.ABORT|Commander.REPLACE|Commander.SKIP;
    final static int NEW_OPTIONS = OLD_OPTIONS|Commander.REPLACE_OLD;

    protected final int askOnFileExist( String msg, Commander commander ) throws InterruptedException {
        return askOnFileExist( commander, msg, -1, -1, -1, -1, OLD_OPTIONS );
    }

    protected final int askOnFileExist( Commander commander, String msg, long src_date, long dst_date, long src_size, long dst_size, int allow_options ) throws InterruptedException {
        if( ( file_exist_behaviour & Commander.APPLY_ALL ) != 0 )
            return file_exist_behaviour & ~Commander.APPLY_ALL;
        int res;
        synchronized( commander ) {
            sendFileExist( msg, src_date, dst_date, src_size, dst_size, allow_options );
            while( ( res = commander.getResolution() ) == Commander.UNKNOWN )
                commander.wait();
        }
        if( res == Commander.ABORT ) {
            error( commander.getContext().getString( R.string.interrupted ) );
            return res;
        }
        if( ( res & Commander.APPLY_ALL ) != 0 )
            file_exist_behaviour = res;
        return res & ~Commander.APPLY_ALL;
    }
    
    public final Engines.IReciever getReciever() {
        return recipient; 
    }

    protected final CommanderAdapter substituteReceiver( Context ctx,  CommanderAdapter rcp ) {
        if( !rcp.hasFeature( CommanderAdapter.Feature.RECEIVER ) ) {
            FSAdapter fsa = new FSAdapter( ctx );
            fsa.setUri( Uri.parse( Utils.createTempDir( ctx ).getAbsolutePath() ) );
            recipient = rcp.getReceiver();
            rcp = fsa;
        } else
            recipient = null;
        return rcp;
    }

    protected final void deleteDir( File dir ) {
        if( dir == null )
            return;
        File[] children = dir.listFiles();
        for( File f : children ) {
            if( f.isDirectory() )
                deleteDir( f );
            try {
                f.delete();
            } catch( Exception e ) {
                Log.e( TAG, f.getAbsolutePath(), e );
            }
        }
        try {
            dir.delete();
        } catch( Exception e ) {
            Log.e( TAG, dir.getAbsolutePath(), e );
        }
    }

    protected Uri handleDirOnReceiver( Commander commander, IReceiver receiver, String name ) {
        try {
            Context ctx = commander.getContext();
            Uri dest_dir_uri = receiver.getItemURI( name, true );
            if( dest_dir_uri == null ) {
                dest_dir_uri = receiver.makeDirectory( name );
                if( dest_dir_uri == null ) {
                    error( ctx.getString( R.string.cant_md, name ), true );
                    return null;
                }
            }
            if( !receiver.isDirectory( dest_dir_uri ) ) {
                error( ctx.getString( R.string.cant_md, name ) + "\n" + ctx.getString( R.string.file_exist, name ), true );
                return null;
            }
            return dest_dir_uri;
        } catch( Exception e ) {
            Log.w( TAG, "Interrupted", e );
        }
        return null;
    }

    protected int handleItemOnReceiver( Commander commander, IReceiver receiver, Uri dest_item_uri, String name ) {
        return handleItemOnReceiver( commander, receiver, dest_item_uri, name, -1, -1 );
    }

    protected int handleItemOnReceiver( Commander commander, IReceiver receiver, Uri dest_item_uri, String name, long src_date, long src_size ) {
        try {
            Context ctx = commander.getContext();
            if( dest_item_uri != null ) {
                long dest_date = -1;
                int res = Commander.UNKNOWN;
                if( receiver instanceof IDestination && src_date > 0 && src_size >= 0 ) {
                    IDestination destination = (IDestination)receiver;
                    CommanderAdapter.Item dest_item = destination.getItem( dest_item_uri );
                    if( dest_item != null ) {
                        if( dest_item.date != null )
                            dest_date = dest_item.date.getTime();
                        res = askOnFileExist( commander, ctx.getString( R.string.file_exist, "<small>" + name.replace( "&", "&amp;" ) + "</small>" ),
                                src_date, dest_date, src_size, dest_item.size, NEW_OPTIONS );
                    }
                }
                if( res == Commander.UNKNOWN )
                    res = askOnFileExist( ctx.getString( R.string.file_exist, name ), commander );
                if( res == Commander.REPLACE_OLD )
                    res = src_date > dest_date ? Commander.REPLACE : Commander.SKIP;
                if( res == Commander.REPLACE ) {
                    if( !receiver.delete( dest_item_uri ) ) {
                        error( ctx.getString( R.string.cant_del, name ) );
                        return Commander.ABORT;
                    }
                } else
                    return res;
            }
        } catch( Exception e ) {
            Log.w( TAG, name, e );
            return Commander.ABORT;
        }
        return Commander.GO_ON;
    }

    @Deprecated
    protected boolean copyStreamToReceiver( Context ctx, IReceiver receiver, InputStream is, String name, long size, java.util.Date ts, int total_progress ) {
        this.progress = total_progress;
        return copyStreamToReceiver( ctx, receiver, is, name, size, ts );
    }

    protected boolean copyStreamToReceiver( Context ctx, IReceiver receiver, InputStream is, String name, long size, java.util.Date ts ) {
        boolean ok = false;
        OutputStream os = null;
        try {
            os = receiver.receive( name );
            if( os != null ) {
                ok = copy( ctx, is, os, name, size );
            } else {
                Log.e( TAG, "No output stream, file: " + name );
                error( ctx.getString( R.string.cant_create, name, "" ) );
                return false;
            }
        } catch( Exception e ) {
            Log.e( TAG, name, e );
            error( ctx.getString( R.string.acc_err, name, e.getMessage() ) );
            return false;
        } finally {
            try {
                if( is != null ) is.close();
            } catch( IOException e ) {
                Log.e( TAG, name, e );
            }
            if( os != null ) receiver.closeStream( os );
        }
        if( !ok || isStopReq() ) {
            Uri dest_item_uri = receiver.getItemURI( name, false );
            if( dest_item_uri != null ) {
                if( !receiver.delete( dest_item_uri ) )
                    error( ctx.getString( R.string.cant_del, dest_item_uri ) );
            }
            return false;
        } else {
            if( ts != null ) {
                Uri dest_item_uri = receiver.getItemURI( name, false );
                if( dest_item_uri != null ) {
                    receiver.setDate( dest_item_uri, ts );
                    return true;
                }
                return false;
            }
            return true;
        }
    }

    // Attention: does not close the streams!
    protected boolean copy( Context ctx, InputStream is, OutputStream os, String name, long size ) {
        int fnl = name.length();
        String rep_s = ctx.getString( R.string.copying,
                fnl > CUT_LEN ? "\u2026" + name.substring( fnl - CUT_LEN ) : name );
        String sz_s = Utils.getHumanSize( size, false );
        sendProgress( rep_s + sizeOfsize( 0, sz_s ), progress, 0, 0 );
        progress_last_sent = System.currentTimeMillis();
        if( android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            is instanceof FileInputStream && os instanceof FileOutputStream ) {
            try {
                return OSFileCopy.copy( ctx, this, is, os, name, size, rep_s, sz_s );
            } catch( OperationCanceledException e ) {
                error( ctx.getString( R.string.interrupted ) );
            } catch( Exception e ) {
                Log.e( TAG, name, e );
                error( ctx.getString( R.string.acc_err, name, e.getMessage() ) );
            }
            return false;
        }
        final double conv = PERC / size;
        int n, speed = 0;
        long copied = 0, copied_since_time = 0;
        try {
            if( this.buf == null ) {
                long free = Runtime.getRuntime().freeMemory();
                int buf_sz = Math.min( (int)(free / 8), 65536 );
                Log.d( TAG, "Buffer size: " + buf_sz + ", free=" + free );
                this.buf = new byte[buf_sz];
            }
            while( true ) {
                n = is.read( buf );
                if( n < 0 ) break;
                os.write( buf, 0, n );
                copied += n;
                copied_since_time += n;
                long time_delta = System.currentTimeMillis() - progress_last_sent;
                if( time_delta > DELAY ) {
                    speed = (int)( MILLI * copied_since_time / time_delta );
                    copied_since_time = 0;
                    progress_last_sent = System.currentTimeMillis();
                    sendProgress( rep_s + sizeOfsize( copied, sz_s ), progress, (int)( copied * conv ), speed );
                }
//                Thread.sleep( 1 );
                if( stop )
                    return false;
            }
            return true;
        } catch( Exception e ) {
            Log.e( TAG, name, e );
            error( ctx.getString( R.string.acc_err, name, e.getMessage() ) );
        }
        return false;
    }

    /**
     * Double buffering copy
     * @return true on success
     */
    protected boolean conveyStreamToReceiver( Context ctx, IReceiver receiver, InputStream in, String name, long size, java.util.Date ts ) {
        int fnl = name.length();
        String rep_s = ctx.getString( R.string.copying,
                fnl > CUT_LEN ? "\u2026" + name.substring( fnl - CUT_LEN ) : name );

        long copied = 0;
        String sz_s = Utils.getHumanSize( size, false );
        double conv = 100. / size;
        OutputStream out = null;
        WritingThread wt = null;
        try {
            out = receiver.receive( name );
            if( out == null ) {
                Log.e( TAG, "Can't get the output stream, file: " + name );
                return false;
            }
            wt = new WritingThread( out );
            wt.start();

            long byte_count = 0;
            long done = 0, nn = 0, start_time = 0;
            int speed = 0;
            while( true ) {
                if( isStopReq() ) {
                    stop = true;
                    in.close();
                    wt.interrupt();
                    synchronized( wt ) {
                        while( wt.to_write != 0 )
                            wt.wait( 1000 );
                    }
                    break;
                }
                if( nn == 0 ) {
                    start_time = System.currentTimeMillis();
                    sendProgress( rep_s + sizeOfsize( done, sz_s ), progress, (int)( byte_count * conv ), speed );
                }
                int n = in.read( wt.bufs[wt.read_bi] );
                synchronized( wt ) {
                    while( wt.to_write != 0 )
                        wt.wait( 1000 );
                    wt.read_bi  = ++wt.read_bi % 2;
                    wt.write_bi = ++wt.write_bi % 2;
                    wt.to_write = n;
                    wt.notify();
                }
                if( n < 0 ) break;
                byte_count += n;
                done += n;
                nn += n;
                long time_delta = System.currentTimeMillis() - start_time;
                if( time_delta > DELAY ) {
                    speed = (int)( MILLI * nn / time_delta );
                    nn = 0;
                }
            }
            sendProgress( rep_s + sizeOfsize( done, sz_s ), progress, (int)( byte_count * conv ), ++speed );
            in.close();
        } catch( Exception e ) {
            if( wt != null ) {
                wt.interrupt();
                try {
                    wt.wait( 1000 );
                } catch( Exception e1 ) {
                    Log.e( TAG, name, e1 );
                }
            }
            Log.e( TAG, name, e );
            String msg = e.getMessage();
            error( ctx.getString( R.string.acc_err, name, msg ) );
            return false;
        } finally {
            try {
                if( in != null ) in.close();
            } catch( IOException e ) {
                Log.e( TAG, name, e );
            }
            if( out != null ) receiver.closeStream( out );
        }
        if( isStopReq() ) {
            try {
                synchronized( this ) {
                    wait( 2000 );
                }
            } catch( Exception e ) {
                Log.e( TAG, name, e );
            }
            Uri dest_item_uri = receiver.getItemURI( name, false );
            if( dest_item_uri != null ) {
                if( !receiver.delete( dest_item_uri ) )
                    error( ctx.getString( R.string.cant_del, dest_item_uri ) );
                return false;
            }
        } else {
            Uri dest_item_uri = receiver.getItemURI( name, false );
            if( dest_item_uri != null ) {
                receiver.setDate( dest_item_uri, ts );
                return true;
            }
        }
        return false;
    }

    static class WritingThread extends Thread {
        public  int  to_write = 0;
        private OutputStream os;

        public  static int BLOCK_SIZE = 65536;
        public  byte bufs[][] = { new byte[BLOCK_SIZE], new byte[BLOCK_SIZE] };
        public  int  read_bi = 0, write_bi = 1;

        WritingThread( OutputStream os_ ) {
            os = os_;
            setName( "WritingThread" );
        }

        @Override
        public void run() {
            try {
                while( true ) {
                    synchronized( this ) {
                        while( to_write == 0 )
                            wait( 1000 );
                    }
                    if( to_write < 0 ) break;
                    os.write( bufs[write_bi], 0, to_write );
                    synchronized( this ) {
                        to_write = 0;
                        notify();
                    }
                }
            } catch( InterruptedException e ) {
                Log.w( getName(), "Interrupted" );
            } catch( IOException e ) {
                Log.e( getName(), "IO error", e );
            }
            synchronized( this ) {
                to_write = 0;
                notify();
            }
        }
    }

    protected final boolean isMatched( CommanderAdapter.Item item, SearchProps sp ) {
        if( sp == null ) return false;
        return sp.match( item );
    }

    // Attention: This method does not close the input stream!
    protected final boolean searchInContent( InputStream is, long sz, String name, String search_for ) {
        long start_time = System.currentTimeMillis();
        try {
            byte[] bb = search_for.getBytes();
            final  int  l = search_for.length();
            final  double conv = 100. / sz;
            BufferedInputStream bis = new BufferedInputStream( is, Math.min( (int)sz, 1048576 ) );
            int    cnt = 0, p = 0;
            sendProgress( name, progress, 0 );
            while( true ) {
                int ch = bis.read();
                if( ch == -1 )
                    return false;
                if( ch == bb[0] ) {
                    bis.mark( l );
                    for( int i = 1; i < l; i++ ) {
                        ch = bis.read();
                        if( ch == -1 )
                            return false;
                        if( ch != bb[i] ) {
                            bis.reset();
                            break;
                        }
                        if( i >= l-1 )
                            return true;
                    }
                }
                int np = (int)(cnt++ * conv);
                if( np - 10 >= p ) {
                    final long cur_time = System.currentTimeMillis();
                    if( ( np > 45 && np < 55 ) || cur_time - progress_last_sent > 500 ) {
                        progress_last_sent = cur_time;
                        sendProgress( name, progress, p = np );
                    }
                }
                if( isStopReq() ) return false;
            }
        }
        catch( Exception e ) {
            Log.e( TAG, "File: " + name + ", str=" + search_for, e );
        } finally {
            //Log.d( TAG, "Elapsed time: " + (System.currentTimeMillis() - start_time ) + "ms" );
            sendProgress( name, progress, 100 );
        }
        return false;
    }
}
