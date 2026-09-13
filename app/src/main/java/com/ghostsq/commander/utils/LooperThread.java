package com.ghostsq.commander.utils;

import android.os.Handler;
import android.os.Looper;

public class LooperThread extends Thread {
    public Handler mHandler;

    public LooperThread() {
        super();
        setName( "LooperThread" );
    }

    public final Handler getHandler() {
        try {
            while( true ) {
                synchronized( this ) {
                    if( mHandler != null )
                        return mHandler;
                }
                Thread.sleep( 10 );
            }
        } catch( InterruptedException ignored ) {
        }
        return null;
    }

    public void run() {
        Looper.prepare();
        synchronized( this ) {
            mHandler = new Handler();
        }
        Looper.loop();
    }
}
