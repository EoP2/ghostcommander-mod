package com.ghostsq.commander.utils;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class APKInstallCallback extends Service {
    private static final String TAG = "APKInstallCallback";

    @Override
    public int onStartCommand( Intent in, int flags, int startId ) {
        Log.d( TAG, "Intent: " + in );
        int status = in.getIntExtra( PackageInstaller.EXTRA_STATUS, -999 );
        switch( status ) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Log.d( TAG, "Requesting user confirmation for installation" );
                Intent confirmationIntent = in.getParcelableExtra( Intent.EXTRA_INTENT );
                if( confirmationIntent != null ) {
                    confirmationIntent.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
                    try {
                        startActivity( confirmationIntent );
                    } catch( Exception e ) {
                        Log.e( TAG, "" + confirmationIntent, e );
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText( this, "Installation succeeded", Toast.LENGTH_LONG ).show();
                Log.d( TAG, "Installation succeeded" );
                break;
            case PackageInstaller.STATUS_FAILURE:
                Toast.makeText( this, "Installation failed", Toast.LENGTH_LONG ).show();
                Log.d( TAG, "Installation failed" );
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText( this, "Installation aborted", Toast.LENGTH_LONG ).show();
                Log.d( TAG, "Installation aborted " + in );
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                Toast.makeText( this, "Incompatible", Toast.LENGTH_LONG ).show();
                Log.d( TAG, "APK is not compatible" );
                break;
            case -999:
                Log.e( TAG, "No status in intent!" );
                break;
            default:
                Log.d( TAG, "Received status: " + status );
                break;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }
}