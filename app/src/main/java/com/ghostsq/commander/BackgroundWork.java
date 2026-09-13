package com.ghostsq.commander;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.Engines;

// TODO try use Worker ? https://developer.android.com/guide/background/persistent/how-to/long-running
// TransferJob (API34+): https://developer.android.com/develop/background-work/background-tasks/uidt

public class BackgroundWork extends Service implements IBackgroundWork {
    private final static String TAG = "BackgroundWork";
    private final static int NOT_ID = 5522;
    private Engines engines = null;
    private Commander commander = null;
    private WorkerHandler workerHandler = null;

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d( TAG, "onStartCommand");
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
            new Notification.Builder( this, FileCommander.FOREGROUND_CHANNEL ) :
            new Notification.Builder( this );
        Notification notification = nb
            .setContentTitle( getString( R.string.foreground_service ) )
//            .setContentText( str )
            .setSmallIcon( R.drawable.not_icon )
            .setContentIntent( getPendingIntent() )
            .build();
//        notification.flags |= Notification.FLAG_ONGOING_EVENT;

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
            startForeground( NOT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC );
        } else {
            startForeground( NOT_ID, notification );
        }
        return super.onStartCommand( intent, flags, startId );
    }

    private PendingIntent getPendingIntent() {
        Intent intent = new Intent( this, FileCommander.class );
        intent.setAction( Intent.ACTION_MAIN );
        int pe_flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) pe_flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity( this, 0, intent, pe_flags );
    }

    private class BackgroundWorkBinder extends Binder implements IBackgroundWorkBinder {
        public IBackgroundWork init( Commander c ) {
            commander = c;
            workerHandler.SetCommander( c );
            return BackgroundWork.this;
        }
    }

    private final IBinder binder = new BackgroundWorkBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        engines = new Engines();
        workerHandler = new WorkerHandler( commander, engines );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        engines.terminateAll();
        Log.d( TAG, "onDestroy" );
    }

    @Override
    public IBinder onBind( Intent intent ) {
        Log.d( TAG, "onBind");
        return binder;
    }

    protected static class WorkerHandler extends Handler {
        private Commander commander;
        private Engines engines;

        public WorkerHandler( Commander commander, Engines engines ) {
            this.engines = engines;
        }

        public void SetCommander( Commander commander ) {
            this.commander = commander;
        }

        @Override
        public void handleMessage( Message msg ) {
            Bundle b = msg.getData();
            long id = b != null ? b.getLong( Commander.NOTIFY_TASK ) : 0;
            try {
                String[] items = b != null ? b.getStringArray( Engines.IReciever.NOTIFY_ITEMS_TO_RECEIVE ) : null;
                if( items != null ) {
                    Engine eng = engines.get( id );
                    if( eng != null ) {
                        Engines.IReciever recipient = eng.getReciever();
                        if( recipient != null ) {
                            int mode = CommanderAdapter.MODE_MOVE_DEL_SRC_DIR;
                            if( b.getBoolean( Commander.NOTIFY_MOVE ) )
                                mode |= CommanderAdapter.MODE_REPORT_AS_MOVE;
                            recipient.receiveItems( items, mode );
                        }
                    }
                }
            } catch( Exception e ) {
                Log.e( TAG, "Task id:" + id, e );
            }
            if( commander == null || commander.notifyMe( msg ) ) {
                engines.remove( id );
            }
        }
    };

    // --- IBackgroundWork implementation
    
    public void start( Engine engine ) {
        engine.setHandler( workerHandler );
        engines.addAndStart( engine );
    }

    public boolean stopEngine( long task_id ) {
        engines.remove( task_id );
        return true;
    }

}
