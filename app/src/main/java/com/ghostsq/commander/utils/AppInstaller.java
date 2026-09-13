package com.ghostsq.commander.utils;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.R;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class AppInstaller extends Engine {
    private final static String TAG = "AppsInstaller";
    private final Context ctx;
    private final PackageInstaller packageInstaller;
    private File[] files;
    private CommanderAdapter ca;
    private CommanderAdapter.Item[] items;

    public AppInstaller( Context ctx ) {
        this.ctx = ctx;
        packageInstaller = ctx.getPackageManager().getPackageInstaller();
    }

    public void fromFiles( File[] files ) {
        this.files = files;
    }

    public void fromItems( CommanderAdapter ca, CommanderAdapter.Item[] items ) {
        this.ca = ca;
        this.items = items;
    }

    @Override
    public void run() {
        boolean ok = false;
        if( files != null ) {
            if( files.length == 1 ) {
                File f = files[0];
                String ext = Utils.getFileExt( f.getName() );
                if( ".apks".equals( ext ) ||
                    ".xapk".equals( ext ) ) {
                    ok = install( f );
                }
            }
            if( !ok )
                ok = install( files );
        }
        else if( ca != null && items != null )
            ok = install( ca, items );
        if( ok )
            sendProgress( null, Commander.OPERATION_COMPLETED );
        else
            sendProgress( ctx.getString( R.string.fail ), Commander.OPERATION_FAILED  );
    }

    private boolean install( File[] files ) {
        if( files == null ) return false;
        long totalSize = 0;
        int  sessionId = 0;
        for( File f : files ) {
            Log.d( TAG, "to install: " + f.getName() );
            totalSize += f.length();
        }
        final PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams( PackageInstaller.SessionParams.MODE_FULL_INSTALL );
        sessionParams.setSize( totalSize );
        try {
            sessionId = packageInstaller.createSession( sessionParams );
            boolean ok = true;
            for( File f : files ) {
                ok = sessionWrite( sessionId, f ) && ok;
            }
            if( sessionCommit( sessionId ) )
                return true;
            else
                Log.e( TAG, "Fails!" );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    private boolean install( File xapk ) {
        try {
            long totalSize = 0;
            int  sessionId = 0;
            try( ZipFile zf = new net.lingala.zip4j.ZipFile( xapk ) ) {
                List<FileHeader> headers = zf.getFileHeaders();
                for( FileHeader fh : headers ) {
                    if( fh.isDirectory() ) continue;
                    Log.d( TAG, "to install: " + fh );
                    totalSize += fh.getUncompressedSize();
                }
                final PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams( PackageInstaller.SessionParams.MODE_FULL_INSTALL );
                sessionParams.setSize( totalSize );
                sessionId = packageInstaller.createSession( sessionParams );
                boolean ok = true;
                for( FileHeader fh : headers ) {
                    if( fh.isDirectory() ) continue;
                    String fn = fh.getFileName();
                    String ext = Utils.getFileExt( fn );
                    if( !".apk".equals( ext ) ) continue;
                    InputStream is = zf.getInputStream( fh );
                    if( is != null )
                        ok = sessionWrite( sessionId, is, fn, fh.getUncompressedSize() ) && ok;
                }
                if( sessionCommit( sessionId ) )
                    return true;
                else
                    Log.e( TAG, "Fails!" );
            }
        } catch( Exception e ) {
            Log.e( TAG, String.valueOf( xapk ), e );
        }
        return false;
    }

    private boolean install( CommanderAdapter ca, CommanderAdapter.Item[] items ) {
        if( ca == null || items == null ) return false;
        long totalSize = 0;
        int  sessionId = 0;

        for( CommanderAdapter.Item item : items ) {
            if( item.position < 0 ) {
                Log.e( TAG, "Item position is unknown " + item );
                return false;
            }
            Log.d( TAG, "to install: " + item.name );
            totalSize += item.size;
            if( item.uri == null )
                item.uri = ca.getItemUri( item.position );
            if( item.uri == null ) {
                Log.e( TAG, "Item URI is unknown " + item );
                return false;
            }
        }

        final PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams( PackageInstaller.SessionParams.MODE_FULL_INSTALL );
        sessionParams.setSize( totalSize );

        try {
            sessionId = packageInstaller.createSession( sessionParams );
            boolean ok = true;
            for( CommanderAdapter.Item item : items ) {
                InputStream is =  ca.getContent( item.uri );
                ok = sessionWrite( sessionId, is, item.name, item.size ) && ok;
                ca.closeStream( is );
            }
            if( sessionCommit( sessionId ) )
                return true;
            else
                Log.e( TAG, "Fails!" );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    private boolean sessionWrite( int sessionId, File file ) {
        try( FileInputStream in = new FileInputStream( file ) ) {
            return sessionWrite( sessionId, in, file.getName(), file.length() );
        } catch( IOException e ) {
            Log.e( TAG, "Failed to write " + file );
        }
        return false;
    }

    private boolean sessionWrite( int sessionId, InputStream in, String name, long length ) {
        OutputStream out = null;
        try( PackageInstaller.Session session = packageInstaller.openSession( sessionId ) ) {
            out = session.openWrite( name, 0, length );
            boolean ok = copy( ctx, in, out, name, length );
/*
            int total = 0;
            byte[] buffer = new byte[65536];
            int c;
            while( ( c = in.read( buffer ) ) != -1 ) {
                total += c;
                out.write( buffer, 0, c );
            }
            Log.d( TAG, "Success: streamed " + total + " bytes" );
 */
            session.fsync( out );
            return ok;
        } catch( IOException e ) {
            Log.e( TAG, "Failed to write " + name );
            return false;
        } finally {
            try {
                if( out != null )
                    out.close();
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
        }
    }

    private boolean sessionCommit( int sessionId ) {
        try( PackageInstaller.Session session = packageInstaller.openSession( sessionId ))  {
            Intent callbackIntent = new Intent( ctx.getApplicationContext(), APKInstallCallback.class );
            PendingIntent pendingIntent;
            pendingIntent = PendingIntent.getService( ctx.getApplicationContext(), 0, callbackIntent, PendingIntent.FLAG_MUTABLE );
            session.commit( pendingIntent.getIntentSender() );
            session.close();
            Log.d( TAG, "Session has been commited: " + packageInstaller.getMySessions() );
            return true;
        } catch( IOException e ) {
            Log.e( TAG, "", e );
            return false;
        }
    }
}
