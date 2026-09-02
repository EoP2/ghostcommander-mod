package com.ghostsq.commander;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@TargetApi(Build.VERSION_CODES.O)
class DataProxy extends ProxyFileDescriptorCallback {
    private static final String TAG = "DataProxy";
    private final CommanderAdapter ca;
    private final CommanderAdapter.Item item;
    private int mode = 0;
    private CancellationSignal signal;
    private InputStream is;
    private OutputStream os;
    private long isPos = 0;

    public DataProxy( CommanderAdapter ca, CommanderAdapter.Item item, int mode, CancellationSignal signal ) {
        this.ca = ca;
        this.item = item;
        this.mode = mode;
        this.signal = signal;
    }

    @Override
    public long onGetSize() throws ErrnoException {
        if( BuildConfig.DEBUG )
            Log.d( TAG, Thread.currentThread().getId() + " onGetSize()" );
        if( item.size < 0 && item.uri != null ) {
            CommanderAdapter.Item real_item = ca.getItem( item.uri );
            if( real_item != null ) {
                item.size = real_item.size;
                if( BuildConfig.DEBUG )
                    Log.d( TAG, "Obtained item size: " + item.size );
            }
        } else if( BuildConfig.DEBUG ) Log.d( TAG, "Known item size: " + item.size );
        return item.size;
    }

    @Override
    public int onRead( long offset, int size, byte[] data ) throws ErrnoException {
        if( BuildConfig.DEBUG ) Log.d( TAG, Thread.currentThread().getId() + " onRead(" + offset + ", " + size + ", " + data.length + ") cur pos=" + isPos );
        if( ( mode & ParcelFileDescriptor.MODE_READ_ONLY ) == 0 )
            throw new ErrnoException( "onRead", OsConstants.EINVAL );
        try {
            if( this.is != null && offset != this.isPos ) {
                if( BuildConfig.DEBUG )
                    Log.v( TAG, "Offset: " + offset + ", pos: " + this.isPos );
                long d = offset - this.isPos;
                if( d > 0 ) {
                    if( BuildConfig.DEBUG ) Log.v( TAG, "Skipping bytes: " + d );
                    long skipped = this.is.skip( d );
                    this.isPos += skipped;
                    if( BuildConfig.DEBUG )
                        Log.v( TAG, "Skipped bytes: " + skipped );
                    d = d - skipped;
                    if( d > 0 ) {
                        final long CHUNK = 1000000;
                        byte[] tmp = new byte[(int)Math.min( d, CHUNK )];
                        for( long c = 0; c < d; c += CHUNK ) {
                            long dd = Math.min( d - c, CHUNK );
                            readData( (int)dd, tmp );
                        }
                    }
                } else {
                    if( BuildConfig.DEBUG )
                        Log.d( TAG, Thread.currentThread().getId() + " closing old stream" );
                    ca.closeStream( this.is );
                    this.is = null;
                }
            }
            if( this.is == null ) {
                if( BuildConfig.DEBUG )
                    Log.d( TAG, Thread.currentThread().getId() + " creating a new stream" );
                this.is = ca.getContent( item.uri, offset );
                this.isPos = offset;
            }
            if( this.is == null ) {
                Log.e( TAG, Thread.currentThread().getId() + " No content stream for: " + item.uri );
                throw new ErrnoException( "onRead", OsConstants.EBADF );
            }
            return readData( size, data );
        } catch( IOException e ) {
            Log.e( TAG, "Failed to read the content of: " + item.uri, e );
            throw new ErrnoException( "onRead", OsConstants.EIO );
        }
    }

    private final int readData( int size, byte[] data ) throws IOException {
        if( size > data.length ) {
            Log.e( TAG, "!!!!!!!!! size " + size + " > data.length " + data.length );
            return -1;
        }
        int total_read = 0, remained = size;
        while( true ) {
            int has_read = is.read( data, total_read, remained );
            if( BuildConfig.DEBUG )
                Log.d( TAG, "Has read: " + has_read + " of requested " + remained + ", total: " + ( total_read + has_read ) );
            if( has_read <= 0 )
                break;
            this.isPos += has_read;
            total_read += has_read;
            if( total_read == size )
                break;
            if( total_read > size ) {
                Log.e( TAG, "Overreading!" );
                break;
            }
            remained = size - total_read;
            if( signal != null && signal.isCanceled() ) {
                Log.w( TAG, "Read has cancelled by signal" );
                break;
            }
        }
        return total_read;
    }

    @Override
    public int onWrite( long offset, int size, byte[] data ) throws ErrnoException {
        if( BuildConfig.DEBUG )
            Log.d( TAG, Thread.currentThread().getId() + " onWrite(" + offset + ", " + size + ", " + data.length + ") cur pos=" + isPos );
        if( ( mode & ParcelFileDescriptor.MODE_WRITE_ONLY ) == 0 )
            throw new ErrnoException( "onWrite", OsConstants.EINVAL );
        try {
            if( this.os == null ) {
                if( BuildConfig.DEBUG ) Log.d( TAG, "creating a new stream" );
                this.os = ca.saveContent( item.uri );
            }
            if( this.os == null ) {
                Log.e( TAG, "No content saving stream for: " + item.uri );
                throw new ErrnoException( "onWrite", OsConstants.EBADF );
            }
            // TODO: check the correctness of the offset value
            os.write( data, 0, size );
            return size;
        } catch( IOException e ) {
            Log.e( TAG, "Failed to write the content of: " + item.uri, e );
            throw new ErrnoException( "onWrite", OsConstants.EIO );
        }
    }

    @Override
    public void onFsync() throws ErrnoException {
        if( os == null )
            throw new ErrnoException( "onFsync", OsConstants.EINVAL );
        try {
            os.flush();
        } catch( IOException e ) {
            Log.e( TAG, "Failed to flush", e );
            throw new ErrnoException( "onWrite", OsConstants.EIO );
        }
    }

    @Override
    public void onRelease() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "onRelease()" );
        if( this.is != null ) {
            ca.closeStream( this.is );
            this.is = null;
        }
        if( this.os != null ) {
            ca.closeStream( this.os );
            this.os = null;
        }
        Looper l = Looper.myLooper();
        if( l != null )
            l.quitSafely();
    }
}
