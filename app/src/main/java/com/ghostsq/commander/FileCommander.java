package com.ghostsq.commander;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Html;
import android.util.Base64;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.HomeAdapter;
import com.ghostsq.commander.adapters.MediaScanEngine;
import com.ghostsq.commander.adapters.SAFAdapter;
import com.ghostsq.commander.root.MountAdapter;
import com.ghostsq.commander.root.RootAdapter;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;

public class FileCommander extends Activity implements Commander, ServiceConnection, View.OnClickListener {
//    private static final Logger log = LoggerFactory.getLogger("FileCommander");    
    private final static String TAG = "GhostCommanderActivity";
    public final static int REQUEST_PERM_RW_EXTERNAL = 4230;
    public final static int REQUEST_CODE_PREFERENCES = 1, REQUEST_CODE_SRV_FORM = 2, REQUEST_CODE_OPEN = 3, REQUEST_CODE_MULT_RENAME = 4;
    public final static String PREF_RESTORE_ACTION = "com.ghostsq.commander.PREF_RESTORE";
    public final static String FOREGROUND_CHANNEL  = "com.ghostsq.commander.channel.FOREGROUND";
    public final static String ONGOING_CHANNEL     = "com.ghostsq.commander.channel.ONGOING";
    public final static String ATTENTION_CHANNEL   = "com.ghostsq.commander.channel.ATTENTION";

    private ArrayList<Dialogs> dialogs;
    private ProgressDialog waitPopup;
    public  Panels panels;
    private boolean on = false, exit = false, dont_restore = false, sxs_auto = true, show_confirm = true, back_exits = false,
            ab = false, start_failed = false;
    private int file_exist_resolution = Commander.UNKNOWN;
    private IBackgroundWork background_work;
    private NotificationManager notMan = null;
    private ArrayList<NotificationId> bg_ids = new ArrayList<NotificationId>();
    private final static String PARCEL = "parcel", TASK_ID = "task_id";
    private String curTheme;
    private Uri      pending_uri;
    private Runnable pending_operation;

    private class NotificationId {
        public long id;
        public long started, last;

        public NotificationId(long id_) {
            id = id_;
            started = System.currentTimeMillis();
            last = started;
        }

        public final boolean is( long fid ) {
            return id == fid;
        }
    }

    public final void showMemory( String s ) {
        final ActivityManager sys = (ActivityManager)getSystemService( Context.ACTIVITY_SERVICE );
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        sys.getMemoryInfo( mem );
        showMessage( s + "\n Memory: " + mem.availMem + ( mem.lowMemory ? " !!!" : "" ) );
    }

    public final void showMessage( String s ) {
        boolean html = Utils.isHTML( s );
        Toast.makeText( this, html ? Html.fromHtml( s ) : s, Toast.LENGTH_LONG ).show();
    }

    public int getWidth() {
        return panels.getWidth();
    }

    public boolean isActionBar() {
        return ab;
    }

    protected final Dialogs getDialogsInstance( int id ) {
        for( int i = 0; i < dialogs.size(); i++ )
            if( dialogs.get( i ).getId() == id )
                return dialogs.get( i );
        return null;
    }

    protected final Dialogs obtainDialogsInstance( int id ) {
        Dialogs dh = getDialogsInstance( id );
        if( dh == null ) {
            dh = new Dialogs( this, id );
            dialogs.add( dh );
        }
        return dh;
    }

    protected final void addDialogsInstance( Dialogs dh ) {
        dialogs.add( dh );
    }

    @Override
    public void onCreate( Bundle savedInstanceState ) {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Creating...\n" );
        curTheme = ColorsKeeper.getTheme( this );
        Utils.setTheme( this, curTheme );
        super.onCreate( savedInstanceState );
        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( this );
        // a hack to let the file path be exposed on newer systems
        if( Build.VERSION.SDK_INT > Build.VERSION_CODES.M &&
               ( !shared_pref.getBoolean( "open_content", true ) || 
                 !shared_pref.getBoolean( "send_content", true ) ) ) {
            StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
            StrictMode.setVmPolicy( builder.build() );
        }
        ab = Utils.setActionBar( this, true, false );
        if( !ab ) {
            requestWindowFeature( Window.FEATURE_NO_TITLE );
        }
        
        // TODO: show progress when there is no title
        // requestWindowFeature( Window.FEATURE_INDETERMINATE_PROGRESS );

        dialogs = Dialogs.createDialogsArray();
        back_exits = shared_pref.getBoolean( "exit_on_back", false );
        Utils.changeLanguage( this );
        String panels_mode = shared_pref.getString( "panels_sxs_mode", "y" );
        sxs_auto = "a".equals( panels_mode );
        boolean sxs = sxs_auto ? getRotMode() : panels_mode.equals( "y" );
        panels = new Panels( this, sxs );
        setConfirmMode( shared_pref );
        notMan = (NotificationManager)getSystemService( Context.NOTIFICATION_SERVICE );
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            ForwardCompat.createNotificationChannel( notMan, ONGOING_CHANNEL, getString( R.string.progress ), getString( R.string.inprogress ),
                    NotificationManager.IMPORTANCE_LOW );
            ForwardCompat.createNotificationChannel( notMan, ATTENTION_CHANNEL, getString( R.string.alert ), getString( R.string.alert ),
                    NotificationManager.IMPORTANCE_HIGH );
        }

        boolean bgwork_in_foreground_serviec = shared_pref.getBoolean( "foreground_service", false );
        startBackgroundWork( bgwork_in_foreground_serviec );

        SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
        PackageInfo pi = null;
        try {
            pi = getPackageManager().getPackageInfo( getPackageName(), 0 );
            Log.d( TAG, "Started " + pi.versionName );
        } catch( PackageManager.NameNotFoundException e ) {
            Log.e( TAG, "Package name not found", e );
        }
        final String FT = "first_time";
        if( prefs.getBoolean( FT, true ) ) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean( FT, false );
            editor.commit();

            Dialogs dh = obtainDialogsInstance( R.id.about );
            dh.setAppendedMessage( getString( R.string.keys_text ) );
            dh.showDialog();
        }
        if( BuildConfig.DEBUG ) Log.d( TAG, "Create's done\n" );
    }

    private void startBackgroundWork( boolean fg ) {
        Intent start_svc = new Intent( this, BackgroundWork.class );
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fg ) {
            ForwardCompat.createNotificationChannel( notMan, FOREGROUND_CHANNEL, getString( R.string.foreground_service ), "",
                    NotificationManager.IMPORTANCE_LOW );
            startForegroundService( start_svc );
            bindService( start_svc, this, BIND_IMPORTANT );
        } else
            bindService( start_svc, this, BIND_AUTO_CREATE | BIND_IMPORTANT );
    }

    @Override
    protected void onStart() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Starting\n" );
        super.onStart();

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ) {
            if( ForwardCompat.requestPermission( this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_PERM_RW_EXTERNAL ) )
                doStart();
            else
                pending_operation = this::doStart;
        } else
            doStart();
        if( BuildConfig.DEBUG ) Log.d( TAG, "Start's end\n" );
    }

    @Override
    public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
        if( requestCode != REQUEST_PERM_RW_EXTERNAL ) return;
        StringBuilder sb = new StringBuilder();
        boolean show_alert = false;
        for( int i = 0; i < permissions.length; i++ ) {
            if( grantResults[i] != PackageManager.PERMISSION_GRANTED && permissions[i] != null )
                show_alert = true;
            sb.append( "Permission " ).append( permissions[i] ).append( ": " )
              .append( grantResults[i] == PackageManager.PERMISSION_GRANTED ? "Granted" :
                       grantResults[i] == PackageManager.PERMISSION_DENIED  ? "Denied"  : "???" ).append( "\n" );
        }
        String s = sb.toString();
        Log.d( TAG, s );
        if( show_alert )
            showError( getString( R.string.need_perm ) );
        if( pending_uri != null && checkPermission( Manifest.permission.READ_EXTERNAL_STORAGE, android.os.Process.myPid(), android.os.Process.myUid() ) == PackageManager.PERMISSION_GRANTED ) {
            panels.Navigate( panels.getCurrent(), pending_uri, null, null );
            pending_uri = null;
        }
        if( pending_operation != null ) {
            new Handler().post( pending_operation );
            pending_operation = null;
        }
    }

    private void doStart() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Do Starting" );
        if( background_work == null ) {
            Log.e( TAG, "No background work!" );
            start_failed = true;
            return;
        }

        on = true;
        Intent intent = getIntent();
        if( isPickMode( intent ) ) {
            Log.d( TAG, "*** PICKER MODE ***" );
            String title = intent.getStringExtra( "title" );
            showInfo( title != null ? title : getString( R.string.pick_mode ) );
        }
        if( dont_restore ) {
            dont_restore = false;
            Log.d( TAG, "Not restoration mode" );
        } else {
            if( BuildConfig.DEBUG ) Log.d( TAG, "Restoring panels..." );
            Utils.changeLanguage( this );

            SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
            Panels.State s = panels.createEmptyStateObject( this );
            s.restore( prefs );
            panels.restoreFaves();
            String action = intent.getAction();
            Log.i( TAG, "Action: " + action );
            if( Intent.ACTION_VIEW.equals( action ) ) {
                Log.d( TAG, "Not restoring " + s.getCurrent() );
                panels.setState( s, s.getCurrent() );
                Log.d( TAG, "VIEW opens in " + panels.getCurrent() );
                onNewIntent( intent );
                return;
            }
            if( Intent.ACTION_SEARCH_LONG_PRESS.equals( action ) ) {
                showSearchDialog();
                return;
            }
            if( Intent.ACTION_SEND.equals( action ) ||
                Intent.ACTION_SEND_MULTIPLE.equals( action )) {
                panels.showPickButton();
            }
            panels.setState( s, -1 );
        }
    }

    @Override
    protected void onPause() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Pausing\n" );
        super.onPause();
        on = false;
        Panels.State s = panels.getState( this );
        if( s == null )
            return;
        SharedPreferences.Editor editor = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE ).edit();
        s.store( editor );
        editor.commit();
        panels.storeFaves();
    }

    @Override
    protected void onResume() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Resuming\n" );
        super.onResume();
        on = true;
    }

    @Override
    protected void onStop() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Stopping\n" );
        super.onStop();
        on = false;
    }

    @Override
    protected void onDestroy() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Destroying\n" );
        on = false;
        super.onDestroy();
        if( notMan != null )
            notMan.cancelAll();
        panels.Destroy();
        unbindService( this );
        stopService( new Intent( this, BackgroundWork.class ) );
        Utils.cleanTempDirs( this );
        if( isFinishing() && exit ) {
            Log.i( TAG, "Good bye cruel world..." );
            System.exit( 0 );
        }
    }

    @Override
    protected void onSaveInstanceState( Bundle outState ) {
        Log.i( TAG, "Saving Instance State" );
        Panels.State s = panels.getState( this );
        if( s != null )
            s.store( outState );
        super.onSaveInstanceState( outState );
    }

    @Override
    protected void onRestoreInstanceState( Bundle savedInstanceState ) {
        Log.i( TAG, "Restoring Instance State" );
        if( savedInstanceState != null ) {
            Panels.State s = panels.createEmptyStateObject( this );
            s.restore( savedInstanceState );
            panels.setState( s, -1 );
        }
        super.onRestoreInstanceState( savedInstanceState );
    }

    @Override
    public void onConfigurationChanged( Configuration newConfig ) {
        Utils.changeLanguage( this );
        super.onConfigurationChanged( newConfig );
        panels.setLayoutMode( sxs_auto ? newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE : panels.getLayoutMode() );
    }

    @Override
    public void onCreateContextMenu( ContextMenu menu, View v, ContextMenuInfo menuInfo ) {
        try {
            super.onCreateContextMenu( menu, v, menuInfo );
            Utils.changeLanguage( this );
            int n = 1;
            AdapterView.AdapterContextMenuInfo acmi = (AdapterView.AdapterContextMenuInfo)menuInfo;
            CommanderAdapter ca = panels.getListAdapter( true );
            panels.setSelection( acmi.position );
            String title = ca.getItemName( acmi.position, false );
            SparseBooleanArray cis = panels.getMultiple( false );
            if( cis != null ) {
                boolean one_of_checked = false;
                int cnt = 0;
                for( int i = 0; i < cis.size(); i++ ) {
                    if( cis.valueAt( i ) ) {
                        cnt++;
                        if( cis.keyAt( i ) == acmi.position )
                            one_of_checked = true;
                    }
                }
                if( one_of_checked && cnt > 1 ) {
                    title = Utils.getNItems( this, cnt );
                    n = cnt;
                }
            }
            menu.setHeaderTitle( title );
            Log.d( TAG, "Menu for position: " + acmi.position );
            ca.populateContextMenu( menu, acmi, n );
        } catch( Exception e ) {
            Log.e( TAG, "onCreateContextMenu()", e );
        }
    }

    @Override
    public boolean onContextItemSelected( MenuItem item ) {
        try {
            int item_id = item.getItemId();
            panels.resetQuickSearch();
            AdapterView.AdapterContextMenuInfo acmi;
            acmi = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();
            if( acmi == null )
                return false;
            Log.d( TAG, "Selected position: " + acmi.position );
            panels.setSelection( acmi.position );
            if( item_id == 0 ) {
                Log.w( TAG, "No menu item id!" );
                return false;
            }
            if( OPEN == item_id )
                panels.openItem( acmi.position );
            else
                dispatchCommand( item_id );
            return true;
        } catch( Exception e ) {
            Log.e( TAG, "onContextItemSelected()", e );
            return false;
        }
    }

    @Override
    protected Dialog onCreateDialog( int id ) {
        if( !on ) {
            Log.e( TAG, "onCreateDialog() is called when the activity is down" );
        }
        Dialogs dh = obtainDialogsInstance( id );
        Dialog d = dh.createDialog( id );
        return d != null ? d : super.onCreateDialog( id );
    }

    @Override
    protected void onPrepareDialog( int id, Dialog dialog ) {
        Dialogs dh = getDialogsInstance( id );
        if( dh != null )
            dh.prepareDialog( id, dialog );
        super.onPrepareDialog( id, dialog );
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        //Log.d( TAG, "menu creating" );
        try {
            Utils.changeLanguage( this );
            // Inflate the currently selected menu XML resource.
            MenuInflater inflater = getMenuInflater();
            if( ab ) {
                inflater.inflate( R.menu.actions, menu );
                inflater.inflate( R.menu.menu, menu );
                MenuItem list_menu = menu.findItem( R.id.list );
                if( list_menu != null )
                    list_menu.setVisible( false );
            } else
                inflater.inflate( R.menu.menu, menu );
            if( "play".equals( BuildConfig.FLAVOR ) ) {
                MenuItem donate_item = menu.findItem( R.id.donate );
                donate_item.setVisible( false );
            }
            return true;
        } catch( Error e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu ) {
        //Log.d( TAG, "menu preparing" );
        try {
            CommanderAdapter ca = panels.getListAdapter( true );
            int[] all_ids = Tools.getIds();
            for( int id : all_ids ) {
                MenuItem mi = menu.findItem( id );
                if( mi == null ) continue;
                boolean vis = false;
                CommanderAdapter.Feature f = Tools.getFeature( id );
                if( f != null && ca != null )
                    vis = ca.hasFeature( f );
                mi.setVisible( vis );
            }
            menu.findItem( R.id.hidden ).setChecked( panels.areHiddenFilesShown() );
            menu.findItem( R.id.filter ).setChecked( panels.isFiltered() );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return true;
    }

    @Override
    public boolean onMenuItemSelected( int featureId, MenuItem item ) {
        panels.resetQuickSearch();
        boolean processed = super.onMenuItemSelected( featureId, item );
        if( !processed )
            dispatchCommand( item.getItemId() );
        return true;
    }

    public void onPanelChange() {
        Log.d( TAG, "invalidating..." );
        invalidateOptionsMenu();
    }

    @Override
    public void issue( Intent in, int ret ) {
        if( in == null )
            return;
        try {
            Log.d( TAG, "Issuing an intent: " + in.toString() );
            if( ret == 0 )
                startActivity( in );
            else
                startActivityForResult( in, ret );
        } catch( Exception e ) {
            Log.e( TAG, in.getDataString(), e );
        }
    }

    @Override
    protected void onActivityResult( int requestCode, int resultCode, Intent data ) {
        super.onActivityResult( requestCode, resultCode, data );
        Log.d( TAG, "onActivityResult( " + requestCode + ", " + data + " )" );
        switch( requestCode ) {
        case REQUEST_CODE_PREFERENCES:
        // if( resultCode == RESULT_OK ) // FIXME How to know there were changes actually made in the prefs?
            {
                if( data != null && PREF_RESTORE_ACTION.equals( data.getAction() ) ) {
                    SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE | MODE_MULTI_PROCESS );
                    panels.restoreFaves();
                }
                SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( this );
                if( sharedPref == null )
                    return;
                String pref_theme = sharedPref.getString( "color_themes", "u" );
                if( !pref_theme.equals( "u" ) && !pref_theme.equals( curTheme ) ) {
                    Log.w( TAG, "Need to restart the activity!.." );
                    recreate();
                    return;
                }
                back_exits = sharedPref.getBoolean( "exit_on_back", false );
                String lang_ = sharedPref.getString( "language", "" );
                panels.applySettings( sharedPref, false );
                String panels_mode = sharedPref.getString( "panels_sxs_mode", "y" );
                sxs_auto = panels_mode.equals( "a" );
                boolean sxs = sxs_auto ? getRotMode() : panels_mode.equals( "y" );
                panels.setLayoutMode( sxs );
                panels.showToolbar( sharedPref.getBoolean( "show_toolbar", true ) );
                setConfirmMode( sharedPref );
            }
            break;
        case REQUEST_CODE_SRV_FORM: {
            if( resultCode == RESULT_OK ) {
                dont_restore = true;
                Uri uri = data.getData();
                if( uri != null ) {
                    Credentials crd = null;
                    try {
                        crd = (Credentials)data.getParcelableExtra( Credentials.KEY );
                        boolean aff_fave = data.getBooleanExtra( ServerForm.ADD_FAVE_KEY, false );
                        String comment = data.getStringExtra( ServerForm.COMMENT_KEY );
                        if( aff_fave )
                            panels.addToFavorites( uri, crd, comment );
                    } catch( Exception e ) {
                        Log.e( TAG, "on taking credentials from parcel", e );
                    }
                    Navigate( uri, crd, null );
                }
            }
        }
            break;
        case ACTIVITY_REQUEST_CREATE_SHORTCUT:
            if( data != null ) {
                data.setAction( "com.android.launcher.action.INSTALL_SHORTCUT" );
                sendBroadcast( data );
            }
            break;
        case ACTIVITY_REQUEST_FOR_NOTIFY_RESULT:
            if( data != null ) {
                on = true;
                Message msg = data.getParcelableExtra( MESSAGE_EXTRA );
                if( msg != null )
                    notifyMe( msg );
                else
                    panels.refreshLists( null );
            }
            break;
        case REQUEST_CODE_OPEN:
            try {
                File temp_file_dir = Utils.getTempDir( this );
                File temp_dir = new File( temp_file_dir, "to_open" );
                File[] temp_files = temp_dir.listFiles();
                for( File f : temp_files )
                    f.deleteOnExit();
            } catch( Exception e ) {
            }
            break;
        case REQUEST_OPEN_DOCUMENT_TREE:
            if( data != null ) {
                Uri uri = data.getData();
                Navigate( uri, null, null );
            }
            break;
        case REQUEST_CODE_MULT_RENAME:
            if( data != null )
                panels.renameItems( data.getStringExtra( "PATTERN" ), data.getStringExtra( "REPLACE" ) );
            break;
        default:
            handleActivityResult( requestCode, resultCode, data );
        }
    }

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event ) {
        on = true;
        // Log.v( TAG, "global key:" + keyCode + ", number:" + event.getNumber()
        // + ", uchar:" + event.getUnicodeChar() );
        char c = (char)event.getUnicodeChar();
        panels.resetQuickSearch();
        switch( c ) {
        case '=':
            dispatchCommand( R.id.eq );
            return true;
        case '/':
            dispatchCommand( R.id.search );
            return true;
        case '&':
            dispatchCommand( R.id.filter );
            return true;
        case '1':
            dispatchCommand( R.id.F1 );
            return true;
        case '9':
            dispatchCommand( R.id.F9 );
            return true;
        case '0':
            dispatchCommand( R.id.F10 );
            return true;
        }
        switch( keyCode ) {
        case KeyEvent.KEYCODE_TAB:
            panels.togglePanels( false );
            return true;
        case KeyEvent.KEYCODE_VOLUME_DOWN:
            if( panels.volumeLegacy ) {
                panels.togglePanels( false );
                return true;
            }
            break;
        case KeyEvent.KEYCODE_SEARCH:
            showSearchDialog();
            return false;
        }
        return super.onKeyDown( keyCode, event );
    }

    @Override
    public boolean onKeyUp( int keyCode, KeyEvent event ) {
        on = true;
        if( keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN )
            return true;

        return super.onKeyUp( keyCode, event );
    }

    /*
     * @see android.view.View.OnClickListener#onClick(android.view.View)
     */
    // ?????????????????????????????????????????????????
    // @Override
    public void onClick( View button ) {
        panels.resetQuickSearch();
        if( button == null )
            return;
        dispatchCommand( button.getId() );
    }

    public final boolean backExit() {
        if( back_exits || panels.getListAdapter( true ) instanceof HomeAdapter ) {
            finish();
            return true;
        }
        return false;
    }

    @SuppressLint("NewApi")
    public void dispatchCommand( int id ) {
        on = true;
        try {
            Utils.changeLanguage( this );
            if( id == R.id.keys || id == R.id.F1 ) {
                showInfo( getString( R.string.keys_text ) );
                return;
            }
            if( id == R.id.F3 || id == R.id.F3t ) {
                panels.openForView( R.id.F3t == id );
                return;
            }
            if( id == R.id.F4 || id == R.id.F4t ) {
                panels.openForEdit( null, R.id.F4t == id );
                return;
            }
            if( id == R.id.F2
             || id == R.id.F2t
             || id == R.id.new_zip
             || id == R.id.F8
             || id == R.id.F8t ) {
                boolean touch = R.id.F2t == id || R.id.F8t == id || R.id.new_zipt == id;
                int count = Utils.getCount( panels.getMultiple( touch ) );
                if( count > 0 || R.id.new_zip == id ) {
                    if( R.id.F2 == id && count > 1 ) {
                        if( panels.getListAdapter( true ).hasFeature( CommanderAdapter.Feature.MULT_RENAME ) ) {
                            Intent i = new Intent( this, MultRename.class );
                            panels.prepareMultRenameIntent( i );
                            startActivityForResult( i, REQUEST_CODE_MULT_RENAME );
                            return;
                        }
                    }
                    showDialog( id );
                } else
                    showMessage( getString( R.string.no_items ) );
                return;
            }
            if( id == R.id.F5F6 || id == R.id.F5F6t ) {
                boolean touch = R.id.F5F6t == id;
                int count = Utils.getCount( panels.getMultiple( touch ) );
                if( count > 0 )
                    showDialog( id );
                else
                    showMessage( getString( R.string.no_items ) );
                return;
            }
            if( id == R.id.open_via_SAF
             || id == R.id.about ) {
                showDialog( id );
                return;
            }
            if( id == R.id.new_item ) {
                CommanderAdapter ca = panels.getListAdapter( true );
                if( ca instanceof SAFAdapter && ((SAFAdapter)ca).showingPUPs() ) {
                    panels.createFolder( null );
                    return;
                }
                showDialog( id );
                return;
            }
            if( id == R.id.donate ) {
                if( "free".equals( BuildConfig.FLAVOR ) ) {
                    startViewURIActivity( R.string.donate_uri );
                }
                return;
            }
            if( id == R.id.prefs || id == R.id.F9 ) {
                openPrefs();
                return;
            }
            if( id == R.id.exit || id == R.id.F10 ) {
                exit = true;
                finish();
                return;
            }
            if( id == R.id.menu ) {
                openOptionsMenu();
                return;
            }
            if( id == R.id.oth_sh_this || id == R.id.eq ) {
                panels.makeOtherAsCurrent();
                return;
            }
            if( id == R.id.eq_dir ) {
                panels.makeOtherAsCurDirItem();
                return;
            }
            if( id == R.id.swap ) {
                panels.swapPanels();
                return;
            }
            if( id == R.id.toggle_panels_mode ) {
                panels.togglePanelsMode();
                return;
            }
            if( id == R.id.tgl ) {
                panels.togglePanels( true );
                return;
            }
            if( id == R.id.sz || id == R.id.szt ) {
                panels.showSizes( R.id.szt == id );
                return;
            }
            if( id == R.id.sha1
             || id == R.id.sha256
             || id == R.id.md5 ) {
                panels.showSums( id );
                return;
            }
            if( id == R.id.action_back ) {
                panels.goUp();
                return;
            }
            if( id == R.id.totop ) {
                panels.goTop();
                return;
            }
            if( id == R.id.tobot ) {
                panels.goBot();
                return;
            }
            if( id == R.id.home ) {
                Navigate( Uri.parse( "home:" ), null, null );
                return;
            }
            if( id == R.id.favs ) {
                Navigate( Uri.parse( "favs:" ), null, null );
                return;
            }
            if( id == R.id.sdcard ) {
                Navigate( Uri.parse( Panels.DEFAULT_LOC ), null, null );
                return;
            }
            if( id == R.id.root ) {
                Uri cu = panels.getFolderUriWithAuth( true );
                String shm = cu != null ? cu.getScheme() : null;
                String to_go;
                if( "root".equals( shm ) )
                    to_go = cu.getPath();
                else
                    to_go = RootAdapter.DEFAULT_LOC + ( cu == null || Utils.str( shm ) ? "" : cu.getPath() );
                Navigate( Uri.parse( to_go ), null, null );
                return;
            }
            if( id == R.id.mount ) {
                Navigate( Uri.parse( MountAdapter.DEFAULT_LOC ), null, null );
                return;
            }
            if( id == R.id.trashcan ) {
                File tc = getExternalFilesDir( "trashcan" );
                if( tc != null )
                    Navigate( Uri.parse( tc.getAbsolutePath() ), null, null );
                return;
            }
            if( id == R.id.search ) {
                showSearchDialog();
                return;
            }
            if( id == R.id.open ) {
                String path = panels.getSelectedItemName( true, true );
                String ext = Utils.getFileExt( path );
                if( ".zip".equalsIgnoreCase( ext ) ) {
                    int pl = path.length();
                    if( pl < 9 || !".fb2.zip".equals( path.substring( pl-8 ) ) )
                        Navigate( Uri.parse( path ).buildUpon().scheme( "zip" ).build(), null, null );
                }
                return;
            }
            if( id == R.id.open_zip ) {
                String path = panels.getSelectedItemName( true, true );
                if( !Utils.str( path ) ) return;
                if( ".zip".equals( Utils.getFileExt( path ) ) ) {
                    Dialogs di = obtainDialogsInstance( R.id.open_zip );
                    di.setActiveFile( path );
                    di.showDialog();
                } else
                    Navigate( Uri.parse( path ).buildUpon().scheme( "zip" ).build(), null, null );
                return;
            }
            if( id == R.id.extract ) {
                panels.unpackZip();
                return;
            }
            if( id == R.id.enter ) {
                panels.openGoPanel();
                return;
            }
            if( id == R.id.add_fav ) {
                panels.addCurrentToFavorites();
                return;
            }
            if( id == R.id.action_sort ) {
                showSortDialog();
                return;
            }
            if( id == R.id.refresh ) {
                panels.refreshLists( null );
                return;
            }
            if( id == R.id.sel_dlg ) {
                showDialog( id );
                return;
            }
            if( id == R.id.sel_between ) {
                panels.checkBetween();
                return;
            }
            if( id == R.id.filter ) {
                if( !panels.cancelFilter() )
                    showDialog( R.id.filter );
                invalidateOptionsMenu();
                return;
            }
            if( id == R.id.online ) {
                Intent intent = new Intent( Intent.ACTION_VIEW );
                intent.setData( Uri.parse( getString( R.string.help_uri ) ) );
                startActivity( intent );
                return;
            }
            if( id == SEND_TO || id == R.id.send ) {
                panels.tryToSend();
                return;
            }
            if( id == OPEN_WITH ) {
                panels.tryToOpen();
                return;
            }
            if( id == COPY_NAME ) {
                panels.copyName();
                return;
            }
            if( id == SHRCT_CMD ) {
                panels.createDesktopShortcut();
                return;
            }
            if( id == FAV_FLD ) {
                panels.faveSelected();
                return;
            }
            if( id == R.id.softkbd ) {
                panels.panelsView.postDelayed( () -> {
                    InputMethodManager imm = (InputMethodManager)getSystemService( Context.INPUT_METHOD_SERVICE );
                    imm.toggleSoftInput( InputMethodManager.SHOW_FORCED , 0 );
                }, 100 );
                return;
            }
            if( id == R.id.hidden ) {
                panels.toggleHidden();
                invalidateOptionsMenu();
                return;
            }
            if( id == R.id.dirsz ) {
                panels.toggleDirSizes();
                return;
            }
            if( id == R.id.rescan ) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( this );
                MediaScanEngine mse = new MediaScanEngine( this, new File( Panels.DEFAULT_LOC ), sp.getBoolean( "scan_all", false ), true );
                mse.setHandler( new SimpleHandler() );
                startEngine( mse );
                return;
            }
            if( id == R.id.compare ) {
                panels.compareItems();
                return;
            }
            if( id == R.id.install ) {
                panels.installApks();
                return;
            }
            if( id == R.id.touch ) {
                panels.setItemsDate( new Date().getTime() );
                return;
            }
            if( id == R.id.set_date ) {
                Dialogs di = obtainDialogsInstance( id );
                di.setTimestamp( panels.getSelectedItemTime( true ) );
                di.showDialog();
                return;
            }
            CommanderAdapter ca = panels.getListAdapter( true );
            if( ca != null )
                ca.doIt( id, panels.getMultiple( true ) );
        } catch( Throwable e ) {
            Log.e( TAG, "Failed command: " + id, e );
        }
    }

    private void showSortDialog() {
        final CommanderAdapter ca = panels.getListAdapter( true );
        if( ca == null ) return;

        ArrayList<Integer> criteria = new ArrayList<Integer>();
        ArrayList<String>  labels   = new ArrayList<String>();
        if( ca.hasFeature( CommanderAdapter.Feature.BY_NAME ) ) {
            criteria.add( CommanderAdapter.SORT_NAME );
            labels.add( getString( R.string.sort_by_name ) );
        }
        if( ca.hasFeature( CommanderAdapter.Feature.BY_EXT ) ) {
            criteria.add( CommanderAdapter.SORT_EXT );
            labels.add( getString( R.string.sort_by_ext ) );
        }
        if( ca.hasFeature( CommanderAdapter.Feature.BY_SIZE ) ) {
            criteria.add( CommanderAdapter.SORT_SIZE );
            labels.add( getString( R.string.sort_by_size ) );
        }
        if( ca.hasFeature( CommanderAdapter.Feature.BY_DATE ) ) {
            criteria.add( CommanderAdapter.SORT_DATE );
            labels.add( getString( R.string.sort_by_date ) );
        }
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( this );
        if( sharedPref.getBoolean( "sort_by_access_date", false ) && ca.hasFeature( CommanderAdapter.Feature.BY_ACCESS_DATE ) ) {
            criteria.add( CommanderAdapter.SORT_ACCD );
            labels.add( getString( R.string.sort_by_adate ) );
        }
        if( criteria.isEmpty() )
            return;

        final int[] criteria_a = new int[criteria.size()];
        for( int i = 0; i < criteria_a.length; i++ )
            criteria_a[i] = criteria.get( i );

        int cur_criterion = panels.getSortingMode();
        int checked = 0;
        for( int i = 0; i < criteria_a.length; i++ )
            if( criteria_a[i] == cur_criterion ) {
                checked = i;
                break;
            }

        final int[] chosen = { criteria_a[checked] };

        new AlertDialog.Builder( this )
            .setTitle( R.string.sorting )
            .setSingleChoiceItems( labels.toArray( new String[0] ), checked,
                new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface dialog, int which ) {
                        chosen[0] = criteria_a[which];
                    }
                } )
            .setPositiveButton( R.string.sort_asc, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dialog, int which ) {
                    panels.changeSorting( chosen[0], true );
                }
            } )
            .setNegativeButton( R.string.sort_desc, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dialog, int which ) {
                    panels.changeSorting( chosen[0], false );
                }
            } )
            .show();
    }

    public class SimpleHandler extends Handler {
        @Override
        public void handleMessage( Message msg ) {
            FileCommander.this.notifyMe( msg );
        }
    };

    private final void openPrefs() {
        try {
            Intent launchPreferencesIntent = new Intent().setClass( this, Prefs.class );
            startActivityForResult( launchPreferencesIntent, REQUEST_CODE_PREFERENCES );
        } catch( Error e ) {
            Log.e( TAG, "Preferences can't open", e );
        }
    }

    private final void showSearchDialog() {
        CommanderAdapter ca = panels.getListAdapter( true );
        if( ca != null && ca.hasFeature( CommanderAdapter.Feature.SEARCH ) ) {
            Dialogs dh = obtainDialogsInstance( R.id.find );
            dh.setData( ca.getSearch() );
            showDialog( R.id.find );
            return;
        } else
            showError( getString( R.string.find_on_fs_only ) );
    }

    public void Search( SearchProps q ) {
        CommanderAdapter ca = panels.getListAdapter( true );
        Uri u = ca.getUri();
        if( u == null )
            return;
        u = q.updateUri( this, u );
        Navigate( u, null, null );
    }

    /*
     * Commander interface implementation
     */
    @Override
    public void Navigate( Uri uri, Credentials crd, String posTo ) {
        if( uri == null ) return;
        String uri_s = uri.toString();
        if( "ftp:".equals( uri_s ) || "sftp:".equals( uri_s ) || "smb:".equals( uri_s ) ) {
            Intent in = new Intent( this, ServerForm.class );
            in.putExtra( "schema", uri.getScheme() );
            startActivityForResult( in, REQUEST_CODE_SRV_FORM );
            return;
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            String scheme = uri.getScheme();
            if( !Utils.str( scheme ) || "file".equals( scheme )  ) {
                if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || BuildConfig.FLAVOR.equals( "root" ) ) {
                    String[] perms = new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    };
                    if( !ForwardCompat.requestPermission( this, perms, REQUEST_PERM_RW_EXTERNAL ) ) {
                        pending_uri = uri;
                        return;
                    }
                } else {
                    if( !Environment.isExternalStorageManager() ) {
                        new AlertDialog.Builder( this )
                            .setTitle( R.string.alert )
                            .setMessage( R.string.manage_all_files )
                            .setPositiveButton( R.string.dialog_ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick( DialogInterface dialog, int which ) {
                                    Intent in = new Intent( Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION );
                                    in.setData( Uri.parse( "package:" + getApplicationContext().getPackageName() ) );
                                    try {
                                        startActivity( in );
                                    } catch( Exception e ) {
                                        Log.e( TAG, in.getDataString(), e );
                                    }
                                }
                            } )
                            .setNegativeButton( R.string.dialog_cancel, null )
                            .show();
                        return;
                    }
                }
            }
        }
        panels.Navigate( panels.getCurrent(), uri, crd, posTo );
        onPanelChange();
    }

    @Override
    public void Open( Uri uri, Credentials crd ) {
        try {
            if( uri == null )
                return;
            String scheme = uri.getScheme();
            String path = uri.getPath();
            String ext = Utils.getFileExt( "zip".equals( scheme ) ? uri.getFragment() : path );
            if( !Utils.str( ext ) )
                ext = Utils.getFileExt( uri.getFragment() );
            String mime = Utils.getMimeByExt( ext );

            if( isPickMode() ) {
                Intent in = new Intent();
                if( ContentResolver.SCHEME_CONTENT.equals( scheme ) ) {
                    in.setDataAndType( uri, mime );
                } else if( !Utils.str( scheme ) || "file".equals( scheme ) ) {
                    in.setData( FileProvider.makeURI( path ) );
                } else if( !getIntent().getBooleanExtra( Intent.EXTRA_LOCAL_ONLY, false ) ) {
                    Uri ca_uri = uri;
                    if( crd != null ) {
                        String username = crd.getUserName();
                        StreamProvider.storeCredentials( this, crd, uri );
                        ca_uri = Utils.updateUserInfo( uri, Uri.encode(username) );
                    }
                    Uri cu = StreamProvider.put( ca_uri, path, mime, -1 );
                    in.setDataAndType( cu, mime );
                }
                in.setFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION );
                setResult( RESULT_OK, in );
                finish();
                return;
            }
            if( ContentResolver.SCHEME_CONTENT.equals( scheme ) ) {
                Intent in = new Intent( Intent.ACTION_VIEW );
                in.setDataAndType( uri, mime );
                in.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET |
                             Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
                Log.d( TAG, "Open uri " + uri.toString() + " intent: " + in.toString() );
                startActivityForResult( in, REQUEST_CODE_OPEN );
            } else if( !Utils.str( scheme ) || "file".equals( scheme ) ) {
                if( ext != null && ( ext.compareToIgnoreCase( ".zip" ) == 0 || ext.compareToIgnoreCase( ".jar" ) == 0 ) ) {
                    int pl = path.length();
                    if( pl < 9 || !".fb2.zip".equals( path.substring( pl-8 ) ) ) {
                        Navigate( uri.buildUpon().scheme( "zip" ).build(), null, null );
                        return;
                    }
                }
                tryOpen( uri, mime );
            } else {
                OpenRemoteFile( uri, crd, scheme, path, mime );
            }
        } catch( ActivityNotFoundException e ) {
            showMessage( "Application for open '" + uri.toString() + "' is not available, " );
        } catch( Exception e ) {
            Log.e( TAG, uri.toString(), e );
        }
    }

    private boolean tryOpen( Uri uri, String mime ) {
        String scheme = uri.getScheme();
        String path = uri.getPath();
        Intent i = new Intent();
        i.setAction( Intent.ACTION_VIEW );
        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( this );
        for( int att1 = 0; att1 < 2; att1++ ) {
            boolean use_content = shared_pref.getBoolean( "open_content", true );
            Uri u = null;
            for( int att2 = 0; att2 < 2; att2++ ) {
                if( use_content ) {
                    u = FileProvider.makeURI( path );
                    i.setFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
                } else {
                    i.setFlags( 0 );
                    if( Utils.str( scheme ) )
                        u = uri;
                    else
                        u = uri.buildUpon().scheme( "file" ).authority( "" ).encodedPath( uri.getEncodedPath().replace( " ", "%20" ) )
                            .build();
                }
                if( tryOpen( i, u, mime ) ) return true;
                use_content = !use_content;
            }
            mime = mime.substring( 0, mime.indexOf( '/' ) + 1 ) + "*";
        }
        showError( getString( R.string.cant_open ) );
        return false;
    }

    private boolean tryOpen( Intent in, Uri u, String mime ) {
        try {
            in.setDataAndType( u, mime );
            in.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET );
            Log.d( TAG, "Open uri " + u.toString() + " intent: " + in.toString() );
            startActivityForResult( in, REQUEST_CODE_OPEN );
            return true;
        } catch( Exception e ) {
            Log.w( TAG, "Can't open URI " + u, e );
        }
        return false;
    }

    private final void OpenRemoteFile( Uri uri, Credentials crd, String scheme, String path, String mime ) throws Exception {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.O && mime != null && ( mime.startsWith( "audio" ) || mime.startsWith( "video" ) ) ) {
            startService( new Intent( this, StreamServer.class ) );
            Intent i = new Intent( Intent.ACTION_VIEW );

            if( crd != null ) {
                String username = crd.getUserName();
                StreamServer.storeCredentials( this, crd, uri );
                uri = Utils.updateUserInfo( uri, username );
            }
            String http_url = "http://127.0.0.1:" + StreamServer.server_port + "/";
            if( true )
                http_url += Uri.encode( uri.toString() );
            else
                http_url += Base64.encodeToString( uri.toString().getBytes(), Base64.URL_SAFE | Base64.NO_WRAP );
            Log.d( TAG, "URI:" + http_url );
            i.setDataAndType( Uri.parse( http_url ), mime );
            i.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET );
            Log.d( TAG, "Issuing an intent: " + i.toString() );
            startActivity( i );
            return;
        } else {
            // !!! the following code is not compatible with apps which want the stream be seekable !
            Uri ca_uri = uri;
            if( crd != null ) {
                String username = crd.getUserName();
                StreamProvider.storeCredentials( this, crd, uri );
                ca_uri = Utils.updateUserInfo( uri, Uri.encode(username) );
            }
            Uri cu = StreamProvider.put( ca_uri, path, mime, -1 );
            Intent in = new Intent( Intent.ACTION_VIEW );
            in.addFlags( Intent.FLAG_GRANT_READ_URI_PERMISSION );
            if( tryOpen( in, cu, mime ) )
                return;
            File temp_file_dir = Utils.getTempDir( this );
            if( temp_file_dir == null )
                return;
            File temp_dir = new File( temp_file_dir, "to_open" );
            if( !temp_dir.exists() ) {
                if( !temp_dir.mkdirs() )
                    return;
            } else if( !temp_dir.isDirectory() )
                return;
            CommanderAdapter ca = CA.CreateAdapterInstance( uri, this );
            if( ca == null )
                return;
            ca.Init( this );
            ca.setUri( uri );
            ca.setCredentials( crd );
            String temp_file = null;
            if( "zip".equals( scheme ) ) {
                temp_file = uri.getFragment();
                if( Utils.str( temp_file ) && temp_file.indexOf( '/' ) >= 0 )
                    temp_file = temp_file.replace( '/', '_' );
            } else
                temp_file = path.substring( path.lastIndexOf( '/' ) + 1 );
            new RemoteContentOpener( this, ca, uri, new File( temp_dir, temp_file ) ).start();
        }
    }

    class RemoteContentOpener extends Thread {
        Handler handler = new Handler();
        Context ctx;
        CommanderAdapter ca;
        Uri u;
        Item item;
        File temp_file;
        ProgressDialog pd;
        
        RemoteContentOpener( Context ctx, CommanderAdapter ca, Uri u, File temp_file ) {
            this.ctx = ctx;
            this.ca = ca;
            this.u = u;
            this.temp_file = temp_file;
            pd = ProgressDialog.show( ctx, "", ctx.getString( R.string.loading ), true, true );
        }
        
        @Override
        public void run() {
            try {
                item = ca.getItem( u );
                if( item == null ) {
                    pd.cancel();
                    Log.e( TAG, "No item for: " + u.toString() );
                    return;
                }
                //
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( FileCommander.this );
                long ros = 5000000;
                try {
                    ros = Long.parseLong( sp.getString( "remote_open_size", "5000000" ) );
                } catch( Exception ignored ) {}
                if( item.size > ros ) {
                    handler.post( new Runnable() {
                        @Override
                        public void run() {
                            pd.cancel();
                            FileCommander.this.showError( getString( R.string.too_big_file, temp_file.getName() ) );
                        }
                    } );
                    return;
                }
                FileOutputStream fos = new FileOutputStream( temp_file );
                InputStream is = ca.getContent( u );
                Utils.copyBytes( is, fos );
                fos.close();
                ca.closeStream( is );
                ca.prepareToDestroy();
                
                if( item.mime == null )
                    item.mime = Utils.getMimeByExt( Utils.getFileExt( item.name ) );
                
                handler.post( new Runnable() {
                    @Override
                    public void run() {
                        pd.cancel();
                        String encoded = Utils.escapePath( temp_file.toString() );
                        FileCommander.this.tryOpen( Uri.parse( encoded ), item.mime );
                    }
                } );
                return;
            } catch( Exception e ) {
                Log.e( TAG, u.toString(), e );
            }
            handler.post( new Runnable() {
                @Override
                public void run() {
                    pd.cancel();
                    FileCommander.this.showError( getString( R.string.cant_open ) + "\n" + temp_file.getName() );
                }
            } );
        }
    }
    
    @Override
    public Context getContext() {
        return this;
    }

    @Override
    protected void onNewIntent( Intent intent ) {
        on = true;
        super.onNewIntent( intent );
        try {
            Bundle extras = intent.getExtras();
            if( extras != null ) {
                long task_id = extras.getLong( TASK_ID );
                if( task_id > 0 ) {
                    // switch the current active task with the one which comes
                    // from the background
                    Dialogs dh = obtainDialogsInstance( Dialogs.PROGRESS_DIALOG );
                    if( dh != null ) {
                        long d_task_id = dh.getTaskId();
                        if( d_task_id != 0 )
                            bg_ids.add( new NotificationId( d_task_id ) );
                        dh.cancelDialog();
                    }
                    remBgNotifId( task_id );
                    dh.setTaskId( task_id );
                    dh.showDialog();

                    Parcelable pe = intent.getParcelableExtra( PARCEL );
                    if( pe != null && pe instanceof Message )
                        notifyMe( (Message)pe );
                    return;
                }
            }
            String action = intent.getAction();
            Uri uri = intent.getData();
            if( uri == null || !Intent.ACTION_VIEW.equals( action ) ) {
                handleActivityResult( -1, -1, intent );
                return;
            }
            Log.d( TAG, "New Intent URI: " + uri );
            Credentials crd = null;
            try {
                crd = intent.getParcelableExtra( Credentials.KEY );
            } catch( Throwable e ) {
                Log.e( TAG, "on extracting credentials from an intent", e );
            }
            String file_name = null;
            String type = intent.getType();
            if( "inode/directory".equals( type ) || "resource/folder".equals( type ) ) {
                panels.Navigate( panels.getCurrent(), uri, crd, null );
                dont_restore = true;
                return;
            }
            if( "application/x-zip-compressed".equals( type ) || "application/zip".equals( type ) ) {
                String scheme = uri.getScheme();
                if( !Utils.str( scheme ) || "file".equals( scheme ) )
                    uri = uri.buildUpon().scheme( "zip" ).build();
                else if( ContentResolver.SCHEME_CONTENT.equals( scheme ) ) {
                    uri = getFileUriFromContentUri( uri, "zip" );
                    if( uri == null )
                        return;
                }
            } else if( ContentResolver.SCHEME_CONTENT.equals( uri.getScheme() ) ) {
                // Unknown content is being passed. Let's just save it
                try {
                    InputStream is = getContentResolver().openInputStream( uri );
                    if( is != null ) {
                        File dwf = new File( Panels.DEFAULT_LOC, "download" );
                        if( !dwf.exists() )
                            dwf.mkdirs();

                        String fn = uri.getLastPathSegment();
                        File f = null;
                        for( int i = 0; i < 99; i++ ) {
                            file_name = i == 0 ? fn : fn + "_" + i;
                            f = new File( dwf, file_name );
                            if( f.exists() )
                                continue;
                            if( f.createNewFile() )
                                break;
                            f = null;
                        }
                        if( f != null ) {
                            BufferedOutputStream bos = new BufferedOutputStream( new FileOutputStream( f ), 8192 );
                            byte[] buf = new byte[4096];
                            int n;
                            while( ( n = is.read( buf ) ) != -1 )
                                bos.write( buf, 0, n );
                            bos.close();
                            is.close();
                            uri = Uri.fromFile( dwf );
                            showMessage( getString( R.string.copied_f, f.toString() ) );
                        } else
                            showError( getString( R.string.not_accs, fn ) );
                    }
                } catch( Exception e ) {
                    showError( getString( R.string.not_accs, "" ) ); // TODO more verbose
                }
            }
            panels.Navigate( panels.getCurrent(), uri, crd, file_name );
            dont_restore = true;
        } catch( Exception e ) {
            Log.e( TAG, "Can't extract a parcel from intent!", e );
        }
    }

    private final Uri getFileUriFromContentUri( Uri uri, String dst_schema ) {
        try {
            String path = SAFAdapter.getPath( getContext(), uri, false );
            if( path != null ) {
                File file = new File( path );
                if( file.canWrite() ) {
                    Uri.Builder b = new Uri.Builder();
                    b.scheme( dst_schema ).path( path );
                    return b.build();
                }
            }
            ContentResolver cr = getContentResolver();
            final String[] projection = { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
            Cursor c = cr.query( uri, projection, null, null, null );
            int nci = c.getColumnIndex( OpenableColumns.DISPLAY_NAME );
            int sci = c.getColumnIndex( OpenableColumns.SIZE );
            c.moveToFirst();
            String fn = c.getString( nci );
            Long fs = c.getLong( sci );
            if( !Utils.str( fn ) )
                fn = "temp.zip";
            if( uri.toString().contains( "downloads" ) ) {
                String dn_dir = Utils.getPath( Utils.PubPathType.DOWNLOADS );
                File dn_file = new File( dn_dir, fn );
                if( dn_file.exists() && dn_file.length() == fs )
                    return Uri.parse( "zip:" + dn_file.getAbsolutePath() );
            }
            InputStream is = cr.openInputStream( uri );
            if( is == null )
                throw new Exception( "No input stream" );
            File temp_dir = Utils.createTempDir( this );
            File dest = new File( temp_dir, fn );
            if( dest.exists() )
                dest.delete();
            FileOutputStream fos = new FileOutputStream( dest );
            if( !Utils.copyBytes( is, fos ) )
                throw new Exception( "Copy failed" );
            is.close();
            fos.close();
            return Uri.parse( "zip:" + dest.getAbsolutePath() );
        } catch( Throwable e ) {
            Log.e( TAG, uri.toString(), e );
            showError( getString( R.string.cant_open ) );
            return null;
        }
    }

    private final void handleActivityResult( int requestCode, int resultCode, Intent data ) {
        CommanderAdapter ca = panels.getListAdapter( true );
        if( ca != null && ca.handleActivityResult( requestCode, resultCode, data ) )
            return;
        ca = panels.getListAdapter( false );
        if( ca != null )
            ca.handleActivityResult( requestCode, resultCode, data );
    }

    @Override
    public boolean startEngine( Engine e ) {
        e.setContext( getApplicationContext() );
        if( background_work != null ) {
            background_work.start( e );
            return true;
        }
        Log.e( TAG, "Background work service is not available to run Engine " + e );
        return false;
    }

    @Override
    public boolean stopEngine( long task_id ) {
        if( background_work == null )
            return false;
        return background_work.stopEngine( task_id );
    }

    @Override
    public boolean notifyMe( Message progress ) {
        final boolean TERMINATE = true, CONTINUE = false;
        String string = null;
        try {
            if( progress.obj != null ) {
                if( progress.obj instanceof Bundle )
                    string = ( (Bundle)progress.obj ).getString( MESSAGE_STRING );
                else if( progress.obj instanceof String ) {
                    string = (String)progress.obj;
                    Log.w( TAG, "Old version message type!" );
                }
            }
            Bundle b = progress.getData();
            if( string == null )
                string = b.getString( MESSAGE_STRING );
            String cookie = b != null ? b.getString( NOTIFY_COOKIE ) : null;
            if( progress.what == Commander.OPERATION_STARTED ) {
                setProgressBarIndeterminateVisibility( true );
                if( Utils.str( string ) ) {
                    //showInfo( string );
                    showMessage( string );
                }
                return CONTINUE;
            }
            long task_id = b != null ? b.getLong( Commander.NOTIFY_TASK ) : -1;
            //Log.v( TAG, "Msg: " + progress.what + " from " + task_id + " txt:" + string + " p1:" + progress.arg1 + " p2:" + progress.arg2 );
            Dialogs dh = null;
            if( progress.what == OPERATION_IN_PROGRESS ) {
                if( progress.arg1 >= 0 ) {
                    boolean id_found = false;
                    if( on ) {
                        dh = obtainDialogsInstance( Dialogs.PROGRESS_DIALOG );
                        if( task_id == dh.getTaskId() ) {
                            dh.showDialog();
                            dh.setProgress( string, progress.arg1, progress.arg2, b != null ? b.getInt( NOTIFY_SPEED, -1 ) : 0 );
                            remBgNotifId( task_id );
                            return CONTINUE;
                        }
                        for( NotificationId bg_id : bg_ids ) {
                            if( bg_id.is( task_id ) ) {
                                id_found = true;
                                break;
                            }
                        }
                        if( !id_found ) {
                            Dialog dlg = dh.getDialog();
                            if( dlg == null || !dlg.isShowing() ) {
                                dh.setTaskId( task_id );
                                dh.showDialog();
                                dh.setProgress( string, progress.arg1, progress.arg2, b != null ? b.getInt( NOTIFY_SPEED, -1 )
                                        : 0 );
                                return CONTINUE;
                            }
                        }
                    }
                    if( !id_found )
                        addBgNotifId( task_id );
                    setSystemOngoingNotification( (int)task_id, string, progress.arg2 > 0 ? progress.arg2 : progress.arg1 );
                    return CONTINUE;
                } else {
                    if( waitPopup == null )
                        waitPopup = ProgressDialog.show( this, "", getString( R.string.wait ), true, true );
                }
                return CONTINUE;
            }
            dh = getDialogsInstance( Dialogs.PROGRESS_DIALOG );
            if( dh != null && task_id == dh.getTaskId() ) {
                Log.d( TAG, "Cancelling dialog " + task_id );
                dh.cancelDialog();
            } else {
                // Log.d( TAG, "No opened dialog found " + task_id );
                remBgNotifId( task_id );
            }

            if( notMan != null )
                notMan.cancel( (int)task_id );
            if( !on ) {
                setSystemAttentionNotification( (int)task_id, progress );
                return progress.what != OPERATION_SUSPENDED_FILE_EXIST;
            }
            if( waitPopup != null ) {
                waitPopup.cancel();
                waitPopup = null;
            }
            setProgressBarIndeterminateVisibility( false );
            panels.operationFinished();
            switch( progress.what ) {
            case OPERATION_SUSPENDED_FILE_EXIST: {
                dh = obtainDialogsInstance( Dialogs.FILE_EXIST_DIALOG );
                dh.setMessageToBeShown( string, null );
                long l_src_date = b.getLong( Commander.SRC_DATE, -1 );
                Date   src_date = l_src_date > 0 ? new Date( l_src_date ) : null;
                long l_dst_date = b.getLong( Commander.DST_DATE, -1 );
                Date   dst_date = l_dst_date > 0 ? new Date( l_dst_date ) : null;
                dh.setFileExistDetails( progress.arg1, src_date, dst_date, b.getLong( Commander.SRC_SIZE, -1 ), b.getLong( Commander.DST_SIZE, -1 ) );
                dh.showDialog();
            }
                return CONTINUE;
            case OPERATION_FAILED:
            case OPERATION_FAILED_REFRESH_REQUIRED:
                if( Utils.str( cookie ) ) {
                    int which_panel = cookie.charAt( 0 ) == '1' ? 1 : 0;
                    panels.setPanelTitle( getString( R.string.fail ), which_panel );
                }
                if( Utils.str( string ) )
                    showError( string );
                if( progress.what == OPERATION_FAILED_REFRESH_REQUIRED ) {
                    String posto = b != null ? b.getString( NOTIFY_POSTO ) : null;
                    panels.refreshLists( posto );
                } else
                    panels.redrawLists();
                return TERMINATE;
            case OPERATION_FAILED_LOGIN_REQUIRED:
                if( string != null ) {
                    dh = obtainDialogsInstance( Dialogs.LOGIN_DIALOG );
                    if( b != null ) {
                        boolean pw_only = b.getBoolean( "PW_ONLY", false );
                        Parcelable crd_p = b.getParcelable( NOTIFY_CRD );
                        Credentials crd = crd_p != null && crd_p instanceof Credentials ? (Credentials)crd_p : null;
                        int panel_idx = Utils.str( cookie ) ? cookie.charAt( 0 ) == '1' ? 1 : 0 : -1;
                        dh.setCredentials( crd, panel_idx, pw_only );
                    }
                    dh.setMessageToBeShown( string, cookie );
                    showDialog( Dialogs.LOGIN_DIALOG );
                }
                return TERMINATE;
            case OPERATION_COMPLETED_REFRESH_REQUIRED:
                String posto = b != null ? b.getString( NOTIFY_POSTO ) : null;
                Uri uri = b != null ? (Uri)b.getParcelable( NOTIFY_URI ) : null;
                if( uri != null ) {
                    Navigate( uri, null, posto );
                    break;
                }
                panels.refreshLists( posto );
                break;
            case OPERATION_COMPLETED:
                if( Utils.str( cookie ) ) {
                    int which_panel = cookie.charAt( 0 ) == '1' ? 1 : 0;
                    String item_name = cookie.substring( 1 );
                    panels.recoverAfterRefresh( item_name, which_panel );
                } else
                    panels.recoverAfterRefresh( null, -1 );
                break;
            }
            if( ( show_confirm || progress.arg1 == OPERATION_REPORT_IMPORTANT ) && Utils.str( string ) )
                showInfo( string );
        } catch( Exception e ) {
            Log.e( TAG, string, e );
        }
        return TERMINATE;
    }

    public final void addBgNotifId( long id ) {
        for( NotificationId bg_id : bg_ids ) {
            if( bg_id.is( id ) )
                return;
        }
        bg_ids.add( new NotificationId( id ) );
    }

    private final boolean remBgNotifId( long id ) {
        for( NotificationId bg_id : bg_ids ) {
            if( bg_id.is( id ) ) {
                bg_ids.remove( bg_id );
                notMan.cancel( (int)id );
                return true;
            }
        }
        return false;
    }

    private PendingIntent getPendingIntent( long task_id, Parcelable parcel ) {
        Intent intent = new Intent( this, FileCommander.class );
        intent.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP );
        intent.setAction( Intent.ACTION_MAIN );
        intent.putExtra( TASK_ID, task_id );
        if( parcel != null )
            intent.putExtra( PARCEL, parcel );
        int pe_flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) pe_flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity( this, 0, intent, pe_flags );
    }

    private Notification notification;

    private void setSystemOngoingNotification( int id, String str, int p ) {
        if( notMan == null || str == null )
            return;
        NotificationId n_id = null;
        for( NotificationId bg_id : bg_ids ) {
            if( bg_id.is( id ) )
                n_id = bg_id;
        }
        if( n_id == null )
            return;
        long cur_time = System.currentTimeMillis();
        if( n_id.last + 1000 > cur_time )
            return;
        n_id.last = cur_time;
        if( notification == null ) {
            Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder( this, ONGOING_CHANNEL ) :
                new Notification.Builder( this );
            notification = nb
                .setContentTitle( getString( R.string.inprogress ) )
                .setContentText( str )
                .setSmallIcon( R.drawable.not_icon )
                .setContentIntent( getPendingIntent( id, null ) )
                .build();
        }
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        RemoteViews not_view = new RemoteViews( getPackageName(), R.layout.progress );
        not_view.setTextViewText( R.id.text, str);//.replace( "\n", " " ) );
        not_view.setProgressBar( R.id.progress_bar, 100, p, false );
        not_view.setTextViewText( R.id.percent, "" + p + "%" );
        notification.contentView = not_view;
        notMan.notify( id, notification );
    }

    private void setSystemAttentionNotification( int id, Message msg ) {
        if( notMan == null || msg == null )
            return;
        String str = "";
        try {
            if( msg.obj instanceof Bundle )
                str = ( (Bundle)msg.obj ).getString( MESSAGE_STRING );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }

        if( !Utils.str( str ) ) {
            Log.w( TAG, "No title in notification" );
            return;
        }
        Notification.Builder nb;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            nb = new Notification.Builder( this, ATTENTION_CHANNEL );
            nb.setLargeIcon( Icon.createWithResource( this, R.mipmap.icon ) );
        } else
            nb = new Notification.Builder( this );
        Notification notification = nb
            .setContentText( Html.fromHtml( str ) )
            .setSmallIcon( R.drawable.not_icon )
            .setContentIntent( getPendingIntent( id, msg ) )
            .build();
        if( msg.what == OPERATION_SUSPENDED_FILE_EXIST ) {
            notification.flags |= Notification.FLAG_SHOW_LIGHTS;
            notification.defaults |= Notification.DEFAULT_VIBRATE;
            notification.ledARGB = 0xFFFF0000;
            notification.ledOnMS = 300;
            notification.ledOffMS = 1000;
        }
        notMan.notify( id, notification );
    }

    public void setResolution( int r ) {
        synchronized( this ) {
            file_exist_resolution = r;
            notify();
        }
    }

    @Override
    public int getResolution() {
        int r = file_exist_resolution;
        file_exist_resolution = Commander.UNKNOWN;
        return r;
    }

    @Override
    public void showError( String msg ) {
        try {
            if( on ) {
                Dialogs dh = obtainDialogsInstance( Dialogs.ALERT_DIALOG );
                dh.setMessageToBeShown( msg, null );
                dh.showDialog();
                return;
            }
        } catch( Exception e ) {
            Log.w( TAG, msg, e );
        }
        showMessage( msg );
    }

    @Override
    public void showInfo( String msg ) {
        if( !on )
            return;
        if( msg.length() < 32 )
            showMessage( msg );
        else {
            Dialogs dh = obtainDialogsInstance( Dialogs.INFO_DIALOG );
            dh.setMessageToBeShown( msg, null );
            dh.showDialog();
        }
    }

    public final void startViewURIActivity( int res_id ) {
        Intent in = new Intent( Intent.ACTION_VIEW );
        in.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS );
        in.setData( Uri.parse( getString( res_id ) ) );
        Log.d( TAG, "Issuing an intent: " + in.toString() );
        startActivity( in );
    }

    private boolean getRotMode() {
        boolean sideXside = false;
        try {
            Display disp = getWindowManager().getDefaultDisplay();
            sideXside = disp.getWidth() > disp.getHeight();
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return sideXside;
    }

    private void setConfirmMode( SharedPreferences sharedPref ) {
        show_confirm = sharedPref.getBoolean( "show_confirm", true );
    }

    public final boolean isPickMode() {
        return isPickMode( getIntent() );
    }

    public static boolean isPickMode( Intent in ) {
        String sia = in.getAction();
        return Intent.ACTION_GET_CONTENT.equals( sia );
    }

    // ServiceConnection implementation

    @Override
    public void onServiceConnected( ComponentName name, IBinder service ) {
        background_work = ( (IBackgroundWork.IBackgroundWorkBinder)service ).init( this );
        if( start_failed )
            doStart();
    }

    @Override
    public void onServiceDisconnected( ComponentName name ) {
        background_work = null;
    }
}
