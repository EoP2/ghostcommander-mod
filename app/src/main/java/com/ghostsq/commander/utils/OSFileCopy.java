package com.ghostsq.commander.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.OperationCanceledException;

import com.ghostsq.commander.adapters.Engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

@TargetApi(Build.VERSION_CODES.Q)
public class OSFileCopy implements FileUtils.ProgressListener, Executor {
    private final static String TAG = "OSFileCopy";
    private Engine engine;
    public CancellationSignal signal = new CancellationSignal();
    final double conv;
    final String sz_s, rep_s;
    long  size, copied_last_time = 0;
    boolean done = false;

    public OSFileCopy( Engine engine, long size, String rep_s, String sz_s ) {
        this.engine = engine;
        this.size = size;
        this.conv = 100. / size;
        this.rep_s = rep_s;
        this.sz_s = sz_s;
    }

    @Override
    public void onProgress( long now_copied ) {
        if( engine.isStopReq() ) {
            signal.cancel();
            return;
        }
        long time_delta = System.currentTimeMillis() - engine.progress_last_sent;
        done = now_copied == size;
        if( time_delta > Engine.DELAY || done ) {
            int speed = 0;
            if( time_delta > 50 || copied_last_time > 0 ) {
                speed = (int)( Engine.MILLI * ( now_copied - copied_last_time ) / time_delta );
                copied_last_time = now_copied;
                engine.progress_last_sent = System.currentTimeMillis();
            }
            engine.sendProgress( rep_s + engine.sizeOfsize( now_copied, sz_s ), engine.progress, size == 0 ? 100 : (int)( now_copied * conv ), speed );
        }
    }

    @Override
    public void execute( Runnable command ) {
        if( engine.isStopReq() )
            signal.cancel();
        command.run();
    }

    public static boolean copy( Context ctx, Engine engine, InputStream is, OutputStream os, String name, long size, String rep_s, String sz_s )
        throws OperationCanceledException, Exception
    {
        OSFileCopy cc = new OSFileCopy( engine, size, rep_s, sz_s );
        long copied = FileUtils.copy( is, os, cc.signal, cc, cc );
        if( size == copied || ( size == 0 && copied > 0 ) ) {
            if( !cc.done )
                cc.onProgress( size );
            return true;
        }
        return false;
    }
};

