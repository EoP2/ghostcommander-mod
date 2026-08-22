package com.ghostsq.commander;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Html;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;

import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Feature;
import com.ghostsq.commander.adapters.SAFAdapter;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

public class Dialogs implements DialogInterface.OnClickListener {
    private final static String TAG = "Dialogs";
    public final static int ALERT_DIALOG = 193, INPUT_DIALOG = 860, PROGRESS_DIALOG = 493,
            INFO_DIALOG = 864, LOGIN_DIALOG = 995,
            FILE_EXIST_DIALOG = 328;
    private final static String SP_TRASH = "to_trash";

    private final static int numDialogTypes = 7;
    protected String toShowInAlertDialog = null, cookie = null, activeFileName, toAppend = null;
    private Object data;
    private int dialogId;
    private long taskId = 0;
    public Dialog dialogObj;
    private FileCommander owner;
    private boolean valid = true;
    private int progressCounter = 0;
    private long progressAcSpeed = 0;
    private Credentials crd = null;
    private int which_panel = -1;
    private boolean pw_only = false;

    private int allow_options = 0;
    private Date src_date, dst_date;
    private long src_size = -1, dst_size = -1, timestamp = -1;

    static ArrayList<Dialogs> createDialogsArray() {
        return new ArrayList<>( numDialogTypes );
    }

    Dialogs( FileCommander owner_, int id ) {
        owner = owner_;
        dialogObj = null;
        dialogId = id;
    }

    public final void setData( Object data ) {
        this.data = data;
    }

    public final void setCookie( String cookie_ ) {
        cookie = cookie_;
    }

    public final void setCredentials( Credentials crd_, int which_panel_, boolean pw_only ) {
        this.crd = crd_;
        this.which_panel = which_panel_;
        this.pw_only = pw_only;
    }

    public final void setFileExistDetails( int allow_options, Date src_date, Date dst_date, long src_size, long dst_size ) {
        this.allow_options = allow_options;
        this.src_date = src_date;
        this.dst_date = dst_date;
        this.src_size = src_size;
        this.dst_size = dst_size;
    }

    public final int getId() {
        return dialogId;
    }

    public final long getTaskId() {
        return taskId;
    }

    public final void setTaskId( long taskId_ ) {
        taskId = taskId_;
    }

    public final Dialog getDialog() {
        return dialogObj;
    }

    public final void showDialog() {
        if( dialogObj == null || !dialogObj.isShowing() )
            owner.showDialog( dialogId );
    }

    public final void cancelDialog() {
        if( dialogObj != null && dialogObj.isShowing() )
            dialogObj.cancel();
        progressCounter = 0;
        progressAcSpeed = 0;
        taskId = 0L;
    }

    private AlertDialog build( View inner_view ) {
        AlertDialog ad = new AlertDialog.Builder( owner )
                .setView( inner_view )
                .setPositiveButton( R.string.dialog_ok, this )
                .setNegativeButton( R.string.dialog_cancel, this )
                .create();
        ad.getWindow().setSoftInputMode( WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN );
        return ad;
    }

    private void setScaleSpinner( View view ) {
        ArrayAdapter<CharSequence> saa = ArrayAdapter.createFromResource( owner.getContext(),
                R.array.scales, android.R.layout.simple_spinner_item ); //android.R.layout.simple_spinner_item R.layout.simple_spinner
        saa.setDropDownViewResource( android.R.layout.simple_spinner_dropdown_item );
        Spinner sb = view.findViewById( R.id.scale_bigger );
        Spinner ss = view.findViewById( R.id.scale_smaller );
        sb.setAdapter( saa );
        ss.setAdapter( saa );
    }

    private long setScale( int spinner_id, long v ) {
        Spinner ss = dialogObj.findViewById( spinner_id );
        for( int i = 3; i >= 0; i-- ) {
            long ts = 1 << i * 10;
            if( v >= ts ) {
                ss.setSelection( i );
                return v / ts;
            }
        }
        return v;
    }

    private int getScale( int spinner_id ) {
        Spinner ss = dialogObj.findViewById( spinner_id );
        if( ss == null ) return 1;
        Object si = ss.getSelectedItem();
        if( si == null ) return 1;
        String scale = si.toString();
        switch( scale ) {
            case "K": return  1024;
            case "M": return  1024 * 1024;
            case "G": return  1024 * 1024 * 1024;
        }
        return 1;
    }

    protected final Dialog createDialog( int id ) {
        try {
            Utils.changeLanguage( owner );
            LayoutInflater factory = LayoutInflater.from( owner );
            if( id == INPUT_DIALOG || id == R.id.open_zip ) {
                final View openArchiveView = factory.inflate( R.layout.open_archive, null );
                dialogObj = build( openArchiveView );
                return dialogObj;
            }
            if( id == R.id.new_zip
                    || id == R.id.new_zipt ) {
                final View newArchiveView = factory.inflate( R.layout.new_archive, null );
                dialogObj = build( newArchiveView );
                return dialogObj;
            }
            if( id == R.id.set_date ) {
                final View timestampView = factory.inflate( R.layout.timestamp, null );
                dialogObj = build( timestampView );
                return dialogObj;
            }
            if( id == R.id.F2
             || id == R.id.F2t
             || id == R.id.open_via_SAF ) {
                final View textEntryView = factory.inflate( R.layout.input, null );
                dialogObj = build( textEntryView );
                return dialogObj;
            }
            if( id == R.id.F5F6
             || id == R.id.F5F6t
             || id == R.id.new_item ) {
                final View twoActionView = factory.inflate( R.layout.two_action, null );
                dialogObj = new AlertDialog.Builder( owner )
                        .setView( twoActionView )
                        .create();
                return dialogObj;
            }
            if( id == R.id.sel_dlg ) {
                final View selectView = factory.inflate( R.layout.select_unselect, null );
                dialogObj = new AlertDialog.Builder( owner )
                        .setView( selectView )
                        .create();
                return dialogObj;
            }
            if( id == R.id.find ) {
                final View searchView = factory.inflate( R.layout.search, null );
                View search_params = searchView.findViewById( R.id.search_params );
                if( search_params != null )
                    search_params.setVisibility( View.VISIBLE );
                setScaleSpinner( searchView );
                dialogObj = build( searchView );
                return dialogObj;
            }
            if( id == R.id.filter ) {
                final View filterView = factory.inflate( R.layout.filter, null );
                dialogObj = build( filterView );
                setScaleSpinner( filterView );
                return dialogObj;
            }
            if( id == LOGIN_DIALOG ) {
                final View textEntryView = factory.inflate( R.layout.login, null );
                dialogObj = build( textEntryView );
                return dialogObj;
            }
            if( id == FILE_EXIST_DIALOG ) {
                final View oc = factory.inflate( R.layout.file_exists, null );
                TextView fileExistsTitle = oc.findViewById( R.id.dlg_title );
                if( fileExistsTitle != null )
                    fileExistsTitle.setText( R.string.error );
                dialogObj = new AlertDialog.Builder( owner )
                        .setView( oc )
                        .setNegativeButton( R.string.dialog_cancel, this )
                        .create();
                return dialogObj;
            }
            if( id == R.id.F8
                    || id == R.id.F8t ) {
                final View deleteView = factory.inflate( R.layout.delete, null );
                return dialogObj = build( deleteView );
            }
            if( id == PROGRESS_DIALOG ) {
                final View progressView = factory.inflate( R.layout.progress, null );
                return dialogObj = new AlertDialog.Builder( owner )
                        .setView( progressView )
                        .setTitle( R.string.progress )
                        .setPositiveButton( R.string.dialog_close, this )
                        .setNegativeButton( R.string.dialog_cancel, this )
                        .setCancelable( false )
                        .create();
            }
            if( id == ALERT_DIALOG ) {
                return dialogObj = new AlertDialog.Builder( owner )
                        .setIcon( android.R.drawable.ic_dialog_alert )
                        .setTitle( R.string.alert )
                        .setMessage( "" )
                        .setPositiveButton( R.string.dialog_ok, this )
                        .create();
            }
            if( id == R.id.about
                    || id == INFO_DIALOG ) {
                AlertDialog.Builder adb = new AlertDialog.Builder( owner )
                        .setPositiveButton( R.string.dialog_ok, this );
                View tvs = factory.inflate( R.layout.info, null );
                if( tvs != null ) {
                    adb.setView( tvs );
                } else
                    adb.setMessage( "" );
                return dialogObj = adb.create();
            }
        } catch( Exception e ) {
            Log.e( TAG, "id=" + id, e );
        } finally {
            if( dialogObj == null )
                Log.e( TAG, "Failed. id=" + id );
        }
        return null;
    }

    class DatePickerButton implements View.OnClickListener {
        java.text.DateFormat df;
        Calendar cal = Calendar.getInstance();
        Button   button;
        CheckBox cb;

        public DatePickerButton( Context ctx, Button button_, CheckBox cb_ ) {
            df = DateFormat.getDateFormat( ctx );
            button = button_;
            cb = cb_;
            CharSequence cs = button.getText();
            if( cs == null || cs.length() == 0 )
                button.setText( df.format( cal.getTime() ) );
            button.setOnClickListener( this );
        }
        @Override
        public void onClick( View v ) {
              Date d = null;
              try {
                   d = df.parse( button.getText().toString() );
              } catch( Exception e ) {}
              cal.setTime( d == null ? new Date() : d );
              new DatePickerDialog( owner, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet( DatePicker vw, int y, int m, int d ) {
                        Calendar cda = new GregorianCalendar( y, m, d );
                        button.setText( df.format( cda.getTime() ) );
                        if( cb != null ) cb.setChecked( true );
                    }
              }, cal.get( Calendar.YEAR ) ,
                 cal.get( Calendar.MONTH ),
                 cal.get( Calendar.DAY_OF_MONTH ) ).show();
        }
    }

    @SuppressLint("StringFormatInvalid")
    protected void prepareDialog( int id, Dialog dialog ) {
        if( dialog != dialogObj ) {
            Log.e( TAG, "Dialogs corrupted!" );
            return;
        }
        Utils.changeLanguage( owner );
        try {
            TextView prompt = (TextView)dialog.findViewById( R.id.prompt );
            EditText edit = (EditText)dialog.findViewById( R.id.edit_field );
            TextView dlgTitle = (TextView)dialog.findViewById( R.id.dlg_title );
            if( id == PROGRESS_DIALOG ) {
                TextView t = (TextView)dialogObj.findViewById( R.id.text );
                if( t != null )
                    t.setText( "" );
                return;
            }
            if( id == R.id.F2 || id == R.id.F2t ) {
                final String op_title = owner.getString( R.string.rename_title );
                String op = owner.getString( R.string.to_rename );
                if( op == null || op.length() <= 1 )
                    op = op_title;
                if( dlgTitle != null )
                    dlgTitle.setText( op_title );

                String item_name = owner.panels.getSelectedItemName( R.id.F2t == id );
                if( item_name == null ) {
                    owner.showMessage( owner.getString( R.string.rename_err ) );
                    item_name = "";
                }
                if( prompt != null )
                    prompt.setText( owner.getString( R.string.oper_item_to, op, item_name ) );
                if( edit != null ) {
                    edit.setWidth( owner.getWidth() - 80 );
                    edit.setText( item_name.replace( "/", "" ) );
                }
                return;
            }
            if( id == R.id.open_via_SAF ) {
                if( dlgTitle != null )
                    dlgTitle.setText( owner.getString( R.string.saf ) );
                prompt.setText( owner.getString( R.string.saf_pick ) );
                if( edit != null ) {
                    edit.setWidth( owner.getWidth() - 80 );
                    String item_name = owner.panels.getSelectedItemName( true, true );
                    edit.setText( item_name );
                }
                return;
            }
            if( id == R.id.new_item ) {
                if( dlgTitle != null )
                    dlgTitle.setText( R.string.new_item );
                if( prompt != null )
                    prompt.setText( R.string.new_item_prompt );
                if( edit != null ) {
                    edit.setWidth( owner.getWidth() - 80 );
                    edit.setText( "" );
                }
                CommanderAdapter ca = owner.panels.getListAdapter( true );
                boolean canFile   = ca != null && ca.hasFeature( CommanderAdapter.Feature.SF4 );
                boolean canFolder = ca != null && ca.hasFeature( CommanderAdapter.Feature.F7 );
                View.OnClickListener cl = new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        String file_name = edit != null ? edit.getText().toString() : "";
                        if( !Utils.str( file_name ) ) return;
                        if( v.getId() == R.id.action_2 )
                            owner.panels.createFolder( file_name );
                        else
                            owner.panels.createNewFile( file_name );
                        Dialogs.this.dialogObj.cancel();
                    }
                };
                Button b1 = dialog.findViewById( R.id.action_1 );
                Button b2 = dialog.findViewById( R.id.action_2 );
                b1.setText( R.string.new_item_file );
                b1.setVisibility( canFile ? View.VISIBLE : View.GONE );
                b1.setOnClickListener( cl );
                b2.setText( R.string.new_item_folder );
                b2.setVisibility( canFolder ? View.VISIBLE : View.GONE );
                b2.setOnClickListener( cl );
                return;
            }
            if( id == R.id.F5F6 || id == R.id.F5F6t ) {
                if( dlgTitle != null )
                    dlgTitle.setText( R.string.F5F6 );
                final boolean touch = dialogId == R.id.F5F6t;
                if( prompt != null ) {
                    String summ = owner.panels.getActiveItemsSummary( touch );
                    if( summ == null ) {
                        dialog.cancel();
                        owner.showMessage( owner.getString( R.string.op_not_alwd, owner.getString( R.string.F5F6 ) ) );
                        valid = false;
                        return;
                    } else
                        valid = true;
                    prompt.setText( owner.getString( R.string.oper_item_to, owner.getString( R.string.F5F6 ), summ ) );
                }
                if( edit != null ) {
                    edit.setWidth( owner.getWidth() - 70 );

                    CommanderAdapter ca = owner.panels.getListAdapter( false );

                    Uri u = ca != null ? ca.getUri() : null;
                    String cts = u != null ? u.toString() : "";
                    if( !Utils.str( cts ) ) return;
                    if( cts.charAt( 0 ) == '/' )
                        cts = Utils.unEscape( cts );
                    int qm_pos = cts.indexOf( '?' );
                    if( qm_pos > 0 )
                        cts = cts.substring( 0, qm_pos );
                    edit.setText( Utils.mbAddSl( cts ) );
                    if( Utils.getCount( owner.panels.getMultiple( touch ) ) == 1 )
                        edit.selectAll();
                }
                CommanderAdapter src = owner.panels.getListAdapter( true );
                boolean canCopy = src != null && src.hasFeature( CommanderAdapter.Feature.F5 );
                boolean canMove = src != null && src.hasFeature( CommanderAdapter.Feature.F6 );
                View.OnClickListener cl = new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        String file_name = edit != null ? edit.getText().toString() : "";
                        if( !Utils.str( file_name ) ) return;
                        if( file_name.charAt( 0 ) == '/' )
                            file_name = Utils.escapePath( file_name );
                        owner.panels.copyFiles( file_name, v.getId() == R.id.action_2, touch );
                        Dialogs.this.dialogObj.cancel();
                    }
                };
                Button b1 = dialog.findViewById( R.id.action_1 );
                Button b2 = dialog.findViewById( R.id.action_2 );
                b1.setText( R.string.copy_title );
                b1.setVisibility( canCopy ? View.VISIBLE : View.GONE );
                b1.setOnClickListener( cl );
                b2.setText( R.string.move_title );
                b2.setVisibility( canMove ? View.VISIBLE : View.GONE );
                b2.setOnClickListener( cl );
                return;
            }
            if( id == R.id.open_zip ) {
                final String op = owner.getString( R.string.open );
                if( dlgTitle != null )
                    dlgTitle.setText( op );
                prompt.setText( owner.getString( R.string.file_name ) );
                TextView file_path = (TextView)dialog.findViewById( R.id.file_path );
                file_path.setText( this.activeFileName );
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource( owner,
                        R.array.encoding, android.R.layout.simple_spinner_item );
                // Specify the layout to use when the list of choices appears
                adapter.setDropDownViewResource( android.R.layout.simple_spinner_dropdown_item );
                Spinner encoding_spin = (Spinner)dialog.findViewById( R.id.encoding );
                encoding_spin.setAdapter( adapter );

                CheckBox encrypr_cb = (CheckBox)dialog.findViewById( R.id.encrypt );
                encrypr_cb.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        CheckBox encrypr_cb = (CheckBox)v;
                        View pwb = Dialogs.this.dialogObj.findViewById( R.id.password_block );
                        pwb.setVisibility( encrypr_cb.isChecked() ? View.VISIBLE : View.GONE );
                    }
                } );
                return;
            }

            if( id == R.id.new_zip || id == R.id.new_zipt ) {
                final String op = owner.getString( R.string.create_zip_title );
                if( dlgTitle != null )
                    dlgTitle.setText( op );
                if( prompt != null ) {
                    String summ = owner.panels.getActiveItemsSummary( R.id.new_zipt == id );
                    if( summ == null ) {
                        dialog.dismiss();
                        summ = owner.getString( R.string.no_items );
                        owner.showMessage( owner.getString( R.string.op_not_alwd, op ) );
                    }
                    prompt.setText( owner.getString( R.string.oper_item_to,
                            owner.getString( R.string.copy_title ), summ ) );
                }
                if( edit != null ) {
                    edit.setWidth( owner.getWidth() - 70 );
                    String path = null;
                    CommanderAdapter oth_ca = owner.panels.getListAdapter( false );
                    if( oth_ca.hasFeature( Feature.FS ) )
                        path = oth_ca.getUri().getPath();
                    else {
                        oth_ca = null;
                        CommanderAdapter act_ca = owner.panels.getListAdapter( true );
                        if( act_ca.hasFeature( Feature.FS ) )
                            path = act_ca.getUri().getPath();
                    }
                    if( path == null )
                        path = Utils.getTempDir( owner ).getAbsolutePath();
                    path += "/.zip";
                    edit.setText( path );
                    edit.setSelection( path.length() - 4 );
                }

                CheckBox encrypr_cb = (CheckBox)dialog.findViewById( R.id.encrypt );
                encrypr_cb.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        CheckBox encrypr_cb = (CheckBox)v;
                        View pwb = Dialogs.this.dialogObj.findViewById( R.id.password_block );
                        pwb.setVisibility( encrypr_cb.isChecked() ? View.VISIBLE : View.GONE );
                    }
                } );

                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource( owner,
                        R.array.encoding, android.R.layout.simple_spinner_item );
                // Specify the layout to use when the list of choices appears
                adapter.setDropDownViewResource( android.R.layout.simple_spinner_dropdown_item );
                Spinner encoding_spin = (Spinner)dialog.findViewById( R.id.encoding );
                encoding_spin.setAdapter( adapter );


                return;
            }
            if( id == R.id.set_date ) {
                if( dlgTitle != null )
                    dlgTitle.setText( R.string.set_date );
                if( timestamp <= 0 )
                    timestamp = new Date().getTime();
                java.text.DateFormat df = DateFormat.getDateFormat( owner );
                EditText edit_date = dialog.findViewById( R.id.edit_date );
                edit_date.setText( df.format( timestamp ) );
                ImageButton date_but = dialog.findViewById( R.id.set_date );
                date_but.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        Date dt = null;
                        try {
                            dt = df.parse( edit_date.getText().toString() );
                        } catch( ParseException e ) {
                            Log.e( TAG, edit_date.getText().toString(), e );
                        }
                        if( dt == null ) dt = new Date();
                        Calendar cal = Calendar.getInstance();
                        cal.setTime( dt );
                        new DatePickerDialog( owner, new DatePickerDialog.OnDateSetListener() {
                            @Override
                            public void onDateSet( DatePicker vw, int y, int m, int d ) {
                                cal.set( y, m, d );
                                timestamp = cal.getTimeInMillis();
                                edit_date.setText( df.format( timestamp ) );
                            }
                        }, cal.get( Calendar.YEAR ),
                                cal.get( Calendar.MONTH ),
                                cal.get( Calendar.DAY_OF_MONTH ) ).show();
                    }
                } );
                @SuppressLint("SimpleDateFormat")
                SimpleDateFormat tf = new SimpleDateFormat( "HH:mm:ss" );
                EditText edit_time = dialog.findViewById( R.id.edit_time );
                edit_time.setText( tf.format( timestamp ) );
                ImageButton time_but = dialog.findViewById( R.id.set_time );
                time_but.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        Date dt = null;
                        try {
                            dt = tf.parse( edit_time.getText().toString() );
                        } catch( ParseException e ) {
                            Log.e( TAG, edit_time.getText().toString(), e );
                        }
                        if( dt == null ) dt = new Date();
                        Calendar cal = Calendar.getInstance();
                        cal.setTime( dt );
                        new TimePickerDialog( owner, new TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet( TimePicker vw, int h, int m ) {
                                cal.set( Calendar.HOUR_OF_DAY, h );
                                cal.set( Calendar.MINUTE, m );
                                timestamp = cal.getTimeInMillis();
                                edit_time.setText( tf.format( timestamp ) );
                            }
                        }, cal.get( Calendar.HOUR_OF_DAY ),
                                cal.get( Calendar.MINUTE ), true ).show();
                    }
                } );

                return;
            }
            if( id == R.id.find || id == R.id.filter ) {
                if( id == R.id.filter ) {
                    if( dlgTitle != null )
                        dlgTitle.setText( R.string.filter );
                } else
                if( id == R.id.find ) {
                    if( dlgTitle != null )
                        dlgTitle.setText( R.string.search_title );
                    if( prompt != null )
                        prompt.setText( R.string.search_prompt );
                }
                Query q = null;
                if( data instanceof Query )
                    q = (Query)data;

                if( edit != null ) {
                    Editable edit_text = edit.getText();
                    if( edit_text.length() == 0 )
                        edit.setText( q == null ? "*" : q.file_mask );
                }
                if( q != null ) {
                    ( (CheckBox)dialogObj.findViewById( R.id.for_dirs ) ).setChecked( q.dirs );
                    ( (CheckBox)dialogObj.findViewById( R.id.for_files ) ).setChecked( q.files );

                    SearchProps sp = null;
                    if( q instanceof SearchProps ) {
                        sp = (SearchProps)q;
                        ( (CheckBox)dialogObj.findViewById( R.id.in_subf ) ).setChecked( !sp.olo );
                    }
                    EditText eb = dialogObj.findViewById( R.id.edit_bigger );
                    if( q.larger_than > 0 ) {
                        eb.setText( String.valueOf( setScale( R.id.scale_bigger, q.larger_than ) ) );
                    }
                    EditText es = dialogObj.findViewById( R.id.edit_smaller );
                    if( q.smaller_than < Long.MAX_VALUE )
                        es.setText( String.valueOf( setScale( R.id.scale_smaller, q.smaller_than ) ) );
                }
                java.text.DateFormat df = null;
                Button mod_after_date = (Button)dialog.findViewById( R.id.mod_after_date );
                if( mod_after_date != null ) {
                    CheckBox cb_ma = dialogObj.findViewById( R.id.mod_after );
                    new DatePickerButton( owner, mod_after_date, cb_ma );
                    if( q != null && q.mod_after != null ) {
                        cb_ma.setChecked( true );
                        df = DateFormat.getDateFormat( owner );
                        mod_after_date.setText( df.format( q.mod_after ) );
                    }
                }

                Button mod_before_date = (Button)dialog.findViewById( R.id.mod_before_date );
                if( mod_before_date != null ) {
                    CheckBox cb_mb = dialogObj.findViewById( R.id.mod_before );
                    new DatePickerButton( owner, mod_before_date, cb_mb );
                    if( q != null && q.mod_before != null ) {
                        cb_mb.setChecked( true );
                        if( df == null ) df = DateFormat.getDateFormat( owner );
                        mod_before_date.setText( df.format( q.mod_before ) );
                    }
                }
                return;
            }
            if( id == R.id.sel_dlg ) {
                if( dlgTitle != null )
                    dlgTitle.setText( R.string.sel_dlg );
                if( edit != null ) {
                    Editable edit_text = edit.getText();
                    if( edit_text.length() == 0 )
                        edit.setText( "*" );
                }
                final CheckBox for_dirs  = dialog.findViewById( R.id.for_dirs );
                final CheckBox for_files = dialog.findViewById( R.id.for_files );
                View.OnClickListener cl = new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        String file_name = edit != null ? edit.getText().toString() : "";
                        if( !Utils.str( file_name ) ) return;
                        owner.panels.checkItems( v.getId() == R.id.action_select, file_name,
                                for_dirs.isChecked(), for_files.isChecked() );
                        Dialogs.this.dialogObj.cancel();
                    }
                };
                dialog.findViewById( R.id.action_select ).setOnClickListener( cl );
                dialog.findViewById( R.id.action_unselect ).setOnClickListener( cl );
                return;
            }
            if( id == LOGIN_DIALOG ) {
                EditText n_v = dialog.findViewById( R.id.username_edit );
                EditText p_v = dialog.findViewById( R.id.password_edit );
                if( crd != null ) {
                    String un = crd.getUserName();
                    n_v.setText( un );
                    p_v.setText( crd.getPassword() != null ? crd.getPassword() : "" );
                    crd = null;
                }
                if( pw_only ) {
                    dialog.findViewById( R.id.username_prompt ).setVisibility( View.GONE );
                    n_v.setVisibility( View.GONE );
                }
                String title = Utils.str( toShowInAlertDialog ) ? toShowInAlertDialog : owner.getString( R.string.login_title );
                int nl_pos = title.indexOf( '\n' );
                if( nl_pos > 0 && prompt != null ) {
                    prompt.setText( title.substring( nl_pos + 1 ) );
                    title = title.substring( 0, nl_pos );
                } else
                    prompt.setText( "" );
                if( dlgTitle != null )
                    dlgTitle.setText( title );
                toShowInAlertDialog = null;
                return;
            }
            if( id == R.id.F8 || id == R.id.F8t ) {
                AlertDialog ad = (AlertDialog)dialog;
                if( dlgTitle != null )
                    dlgTitle.setText( R.string.delete_title );
                ArrayList<String> names = new ArrayList<String>();
                String str, summ = owner.panels.getActiveItemsSummary( R.id.F8t == id, names );
                if( summ == null ) {
                    str = owner.getString( R.string.no_items );
                    dialog.cancel();
                } else
                    str = owner.getString( R.string.delete_q, summ );
                ( (TextView)ad.findViewById( R.id.summary ) ).setText( str );
                int n = names.size();
                boolean show_preview = n > 1;
                View pvb = ad.findViewById( R.id.preview_btn );
                pvb.setVisibility( show_preview ? View.VISIBLE : View.GONE );
                final View pvc = ad.findViewById( R.id.preview_ctr );
                pvc.setVisibility( View.GONE );
                pvb.setOnClickListener( v -> {
                    pvc.setVisibility( View.VISIBLE );
                    StringBuilder sb = new StringBuilder( n * 20 );
                    for( String name : names ) {
                        boolean dir = name.indexOf( "/" ) > 0;
                        if( dir ) sb.append( "<b>" );
                        int l = name.length();
                        sb.append( l > 32 ? "…" + name.substring( l - 32 ) : name );
                        if( dir ) sb.append( "</b>" );
                        sb.append( "<br/>" );
                    }
                    ( (TextView)ad.findViewById( R.id.preview ) ).setText( Html.fromHtml( sb.toString() ) );
                } );
                int tc_vis = View.GONE;
                CheckBox cb_to_trash = ad.findViewById( R.id.to_trash );
                File[] tcs = owner.getExternalFilesDirs( "trashcan" );
                if( tcs != null ) {
                    for( File tc : tcs ) {
                        String tc_path = tc.getAbsolutePath();
                        int p = tc_path.indexOf( "/Android/data" );
                        if( p > 0 ) {
                            String base_path = tc_path.substring( 0, p );
                            CommanderAdapter ca = owner.panels.getListAdapter( true );
                            if( ca.hasFeature( Feature.LOCAL ) ) {
                                String cur_path = ca.getUri().getPath();
                                if( cur_path != null && cur_path.indexOf( base_path ) == 0 && !cur_path.contains( tc_path ) ) {
                                    SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( owner );
                                    cb_to_trash.setChecked( shared_pref.getBoolean( SP_TRASH, false ) );
                                    tc_vis = View.VISIBLE;
                                    break;
                                }
                            }
                        }
                    }
                }
                cb_to_trash.setVisibility( tc_vis );
                return;
            }
            if( id == R.id.donate ) {
                ( (AlertDialog)dialog ).setMessage( owner.getString( R.string.donation ) );
                return;
            }
            if( id == R.id.about ) {
                PackageInfo pi = null;
                try {
                    pi = owner.getPackageManager().getPackageInfo( owner.getPackageName(), 0 );
                } catch( NameNotFoundException e ) {
                    Log.e( TAG, "Package name not found", e );
                }
                String s2, s3;
                if( false && "play".equals( BuildConfig.FLAVOR ) ) {
                    s2 = "";
                    s3 = "";
                } else {
                    s2 = "<!--";
                    s3 = "-->";
                }
                String txt = owner.getString( R.string.about_text, pi != null ? pi.versionName : "", s2, s3 );
                TextView tv = (TextView)dialog.findViewById( R.id.text_view );
                //if( id == R.id.about ) tv.setAutoLinkMask( Linkify.EMAIL_ADDRESSES );
                tv.setMovementMethod( LinkMovementMethod.getInstance() );
                tv.setText( Html.fromHtml( txt + ( toAppend != null ? toAppend : "" ) ) );
                toAppend = null;
                return;
            }
            if( id == INFO_DIALOG ) {
                if( toShowInAlertDialog != null ) {
                    TextView tv = (TextView)dialog.findViewById( R.id.text_view );
                    if( tv != null ) {
                        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( owner );
                        int fnt_sz = Integer.parseInt( shared_pref != null ? shared_pref.getString( "font_size", "14" ) : "14" );
                        boolean reduce_size = toShowInAlertDialog.length() > 128;
                        if( !reduce_size ) {
                            String[] ss = toShowInAlertDialog.split( "\n" );
                            for( String s : ss ) {
                                if( s.length() > 31 ) {
                                    reduce_size = true;
                                    break;
                                }
                            }
                        }
                        int fs = ( reduce_size ? 15 : 17 ) + ( fnt_sz - 14 );
                        tv.setTextSize( fs > 12 ? fs : 12 );
                        if( Utils.isHTML( toShowInAlertDialog ) ) {
                            if( toShowInAlertDialog.indexOf( "</a>", 3 ) > 0 )
                                tv.setMovementMethod( LinkMovementMethod.getInstance() );
                            tv.setText( Html.fromHtml( toShowInAlertDialog.indexOf( "<p>" ) > 0 ? toShowInAlertDialog : toShowInAlertDialog.replaceAll( "\\n", "<br/>" ) ) );
                        } else
                            tv.setText( toShowInAlertDialog );
                    } else
                        ( (AlertDialog)dialog ).setMessage( toShowInAlertDialog );
                    toShowInAlertDialog = null;
                }
                return;
            }
            if( id == FILE_EXIST_DIALOG ) {
                TextView ms_tv = dialog.findViewById( R.id.msg_text );
                ms_tv.setText( Html.fromHtml( toShowInAlertDialog ) );
                TextView cd_tv = dialog.findViewById( R.id.conflict_details );
                boolean dates_provided = src_date != null && dst_date != null;
                if( dates_provided && src_size != -1 && dst_size != -1 ) {
                    cd_tv.setVisibility( View.VISIBLE );
                    StringBuilder sb = new StringBuilder();
                    sb.append( "<b>" ).append( owner.getString( R.string.source ) ).append( ":</b><br/>" )
                            .append( "&#xA0;&#xA0;" ).append( owner.getString( R.string.sz_lastmod ) ).append( "&#xA0;" ).append( Utils.formatDate( src_date, owner ) ).append( "<br/>" )
                            .append( "&#xA0;&#xA0;" ).append( owner.getString( R.string.sz_Nbytes, String.valueOf( src_size ) ) ).append( "<br/>" );
                    sb.append( "<b>" ).append( owner.getString( R.string.destination ) ).append( ":</b><br/>" )
                            .append( "&#xA0;&#xA0;" ).append( owner.getString( R.string.sz_lastmod ) ).append( "&#xA0;" ).append( Utils.formatDate( dst_date, owner ) ).append( "<br/>" )
                            .append( "&#xA0;&#xA0;" ).append( owner.getString( R.string.sz_Nbytes, String.valueOf( dst_size ) ) ).append( "<br/>" );
                    cd_tv.setText( Html.fromHtml( sb.toString() ) );
                } else
                    cd_tv.setVisibility( View.GONE );
                View.OnClickListener click_listener = new View.OnClickListener() {
                    @Override
                    public void onClick( View v ) {
                        owner.setResolution( getResolution( v.getId() ) );
                        Dialogs.this.dialogObj.cancel();
                    }
                    private int getResolution( int id ) {
                        if( id == R.id.no_skip )     return Commander.SKIP;
                        if( id == R.id.yes_replace ) return Commander.REPLACE;
                        if( id == R.id.skip_all )    return Commander.SKIP_ALL;
                        if( id == R.id.replace_all ) return Commander.REPLACE_ALL;
                        if( id == R.id.replace_old ) return Commander.REPLACE_OLD | Commander.APPLY_ALL;
                        return Commander.UNKNOWN;
                    }
                };
                dialog.findViewById( R.id.yes_replace ).setOnClickListener( click_listener );
                dialog.findViewById( R.id.no_skip ).setOnClickListener( click_listener );
                dialog.findViewById( R.id.skip_all ).setOnClickListener( click_listener );
                dialog.findViewById( R.id.replace_all ).setOnClickListener( click_listener );
                Button replace_old = dialog.findViewById( R.id.replace_old );
                if( dates_provided && ( allow_options & Commander.REPLACE_OLD ) != 0 ) {
                    replace_old.setVisibility( View.VISIBLE );
                    replace_old.setOnClickListener( click_listener );
                } else
                    replace_old.setVisibility( View.GONE );
                return;
            }
            if( id == ALERT_DIALOG ) {
                if( toShowInAlertDialog != null ) {
                    AlertDialog ad = (AlertDialog)dialog;
                    ad.setMessage( toShowInAlertDialog );
                    toShowInAlertDialog = null;
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    public void setProgress( String string, int progress, int progressSec, int speed ) {
        if( dialogObj == null )
            return;
        try {
            if( string != null ) {
                TextView t = (TextView)dialogObj.findViewById( R.id.text );
                if( t != null ) {
                    t.setSingleLine( string.indexOf( '\u2026' ) < 0 );
                    t.setText( string );
                }
            }
            ProgressBar p_bar = (ProgressBar)dialogObj.findViewById( R.id.progress_bar );
            TextView perc_t = (TextView)dialogObj.findViewById( R.id.percent );

            if( progress >= 0 )
                p_bar.setProgress( progress );
            if( progressSec >= 0 )
                p_bar.setSecondaryProgress( progressSec );
            if( perc_t != null ) {
                perc_t.setTextSize( 14 );
                perc_t.setText( "" + ( progressSec > 0 ? progressSec : progress ) + "%" );
            }
            TextView speed_t = (TextView)dialogObj.findViewById( R.id.speed );
            if( speed > 0 ) {
                progressCounter++;
                progressAcSpeed += speed;
                long avgsp = progressAcSpeed / progressCounter;
                // Utils.getHumanSize( speed )
                String str = Formatter.formatFileSize( owner, speed ).trim() + "/" + owner.getString( R.string.second ) +
                      " (" + Formatter.formatFileSize( owner, avgsp ).trim() + "/" + owner.getString( R.string.second ) + ")";
                speed_t.setText( str );
            }
            else if( speed < 0 || progress == 0 ) {
                speed_t.setText( "" );
                progressCounter = 0;
                progressAcSpeed = 0;
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    public void setActiveFile( String fn ) {
        this.activeFileName = fn;
    }
    public void setAppendedMessage( String string ) {
        toAppend = string;
    }
    public void setMessageToBeShown( String string, String cookie_ ) {
        toShowInAlertDialog = string;
        cookie = cookie_;
    }
    public void setTimestamp( long t ) {
        this.timestamp = t;
    }

    private void doFind( String file_name ) {
        try {
            SearchProps sp = new SearchProps( file_name );
            sp.dirs = ( (CheckBox)dialogObj.findViewById( R.id.for_dirs ) ).isChecked();
            sp.files = ( (CheckBox)dialogObj.findViewById( R.id.for_files ) ).isChecked();
            sp.olo = !( (CheckBox)dialogObj.findViewById( R.id.in_subf ) ).isChecked();
            String cs = ( (EditText)dialogObj.findViewById( R.id.edit_content ) ).getText().toString();
            if( Utils.str( cs ) )
                sp.content = cs;
            String bts = ( (EditText)dialogObj.findViewById( R.id.edit_bigger ) ).getText().toString();
            if( bts.length() > 0 )
                sp.larger_than = Long.parseLong( bts ) * getScale( R.id.scale_bigger );
            String sts = ( (EditText)dialogObj.findViewById( R.id.edit_smaller ) ).getText().toString();
            if( sts.length() > 0 )
                sp.smaller_than = Long.parseLong( sts ) * getScale( R.id.scale_smaller );
            java.text.DateFormat df = DateFormat.getDateFormat( owner.getContext() );
            if( ( (CheckBox)dialogObj.findViewById( R.id.mod_after ) ).isChecked() ) {
                CharSequence macs = ( (Button)dialogObj.findViewById( R.id.mod_after_date ) ).getText();
                if( macs.length() > 0 )
                    sp.mod_after = df.parse( macs.toString() );
            }
            if( ( (CheckBox)dialogObj.findViewById( R.id.mod_before ) ).isChecked() ) {
                CharSequence mbcs = ( (Button)dialogObj.findViewById( R.id.mod_before_date ) ).getText();
                if( mbcs.length() > 0 )
                    sp.mod_before = df.parse( mbcs.toString() );
            }
            if( sp.isValid() )
                owner.Search( sp );
        } catch( Exception e ) {
            Log.e( TAG, file_name, e );
        }
    }

    private void doFilter( String mask ) {
         try {
            FilterProps filter = new FilterProps();
            filter.file_mask = mask;
            filter.dirs = ( (CheckBox)dialogObj.findViewById( R.id.for_dirs ) ).isChecked();
            filter.files = ( (CheckBox)dialogObj.findViewById( R.id.for_files ) ).isChecked();

            String bts = ( (EditText)dialogObj.findViewById( R.id.edit_bigger ) ).getText().toString();
            if( bts.length() > 0 )
                filter.larger_than = Long.parseLong( bts ) * getScale( R.id.scale_bigger );
            String sts = ( (EditText)dialogObj.findViewById( R.id.edit_smaller ) ).getText().toString();
            if( sts.length() > 0 )
                filter.smaller_than = Long.parseLong( sts ) * getScale( R.id.scale_smaller );

            java.text.DateFormat df = DateFormat.getDateFormat( owner );
            if( ( (CheckBox)dialogObj.findViewById( R.id.mod_after ) ).isChecked() ) {
                CharSequence macs = ( (Button)dialogObj.findViewById( R.id.mod_after_date ) ).getText();
                if( macs.length() > 0 )
                    filter.mod_after = df.parse( macs.toString() );
            }
            if( ( (CheckBox)dialogObj.findViewById( R.id.mod_before ) ).isChecked() ) {
                CharSequence mbcs = ( (Button)dialogObj.findViewById( R.id.mod_before_date ) ).getText();
                if( mbcs.length() > 0 )
                    filter.mod_before = df.parse( mbcs.toString() );
            }
            RadioButton rb = (RadioButton)dialogObj.findViewById( R.id.show_matched );
            filter.include_matched = rb.isChecked();

            owner.panels.setFilter( filter );
        } catch( Exception e ) {
            Log.e( TAG, mask, e );
        }
    }

    private void doEditable( int id ) {
        EditText edit = (EditText)dialogObj.findViewById( R.id.edit_field );
        if( edit != null ) {
            String file_name = edit.getText().toString();
            if( !Utils.str( file_name ) ) {
                if( dialogId != R.id.find )
                    return;
            }
            if( id == R.id.F2
                    || id == R.id.F2t ) {
                owner.panels.renameItem( file_name, R.id.F2t == dialogId );
                return;
            }
            if( id == R.id.open_via_SAF ) {
                Uri saf_uri = SAFAdapter.getBestUri( owner, file_name );
                owner.Navigate( saf_uri, null, null );
                return;
            }
            if( id == R.id.new_zip
                    || id == R.id.new_zipt ) {
                String password = null;
                CheckBox encrypr_cb = (CheckBox)dialogObj.findViewById( R.id.encrypt );
                if( encrypr_cb.isChecked() ) {
                    EditText pw_edit = (EditText)dialogObj.findViewById( R.id.password_edit );
                    password = pw_edit.getText().toString();
                }
                String encoding = null;
                Spinner encoding_spin = (Spinner)dialogObj.findViewById( R.id.encoding );
                int i = encoding_spin.getSelectedItemPosition();
                if( i > 0 )
                    encoding = owner.getResources().getStringArray( R.array.encoding_vals )[i];
                owner.panels.createZip( file_name.trim(), R.id.new_zipt == dialogId, password, encoding );
                return;
            }
            if( id == R.id.filter ) {
                if( file_name.length() == 0 ) return;
                doFilter( file_name );
                return;
            }
            if( id == R.id.find ) {
                doFind( file_name );
                return;
            }
        }
    }

    private void doOpenZip() {
        Uri.Builder ub = Uri.parse( this.activeFileName ).buildUpon().scheme( "zip" );
        Credentials crd = null;
        CheckBox encrypr_cb = (CheckBox)dialogObj.findViewById( R.id.encrypt );
        if( encrypr_cb.isChecked() ) {
            EditText pw_edit = (EditText)dialogObj.findViewById( R.id.password_edit );
            crd = new Credentials( null, pw_edit.getText().toString() );
        }
        Spinner encoding_spin = (Spinner)dialogObj.findViewById( R.id.encoding );
        int i = encoding_spin.getSelectedItemPosition();
        if( i > 0 )
            ub.encodedQuery( "e=" + owner.getResources().getStringArray( R.array.encoding_vals )[i] );
        owner.Navigate( ub.build(), crd, null );
    }

    private void doSetDate() {
        java.text.DateFormat df = DateFormat.getDateFormat( owner );
        EditText edit_date = dialogObj.findViewById( R.id.edit_date );
        EditText edit_time = dialogObj.findViewById( R.id.edit_time );
        String s_t = edit_time.getText().toString();
        String time_pattern;
        if( s_t.matches( "\\d{1,2}:\\d{1,2}:\\d{1,2}" ) )
            time_pattern = "HH:mm:ss";
        else
        if( s_t.matches( "\\d{1,2}:\\d{1,2}" ) )
            time_pattern = "HH:mm";
        else
            time_pattern = null;
        try {
            Date d = df.parse( edit_date.getText().toString() );
            if( d == null ) {
                Log.e( TAG, "Cannot parse date " + edit_date.getText().toString() );
                return;
            }
            if( time_pattern != null ) {
                try {
                    @SuppressLint("SimpleDateFormat")
                    SimpleDateFormat tf = new SimpleDateFormat( time_pattern );
                    Date t = tf.parse( s_t );
                    if( t != null ) {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime( d );
                        Calendar calt = Calendar.getInstance();
                        calt.setTime( t );
                        cal.set( Calendar.HOUR_OF_DAY, calt.get( Calendar.HOUR_OF_DAY ) );
                        cal.set( Calendar.MINUTE, calt.get( Calendar.MINUTE ) );
                        cal.set( Calendar.SECOND, calt.get( Calendar.SECOND ) );
                        owner.panels.setItemsDate( cal.getTime().getTime() );
                        return;
                    }
                } catch( ParseException e ) {
                    Log.w( TAG, s_t, e );
                }
            }
            owner.panels.setItemsDate( d.getTime() );
        } catch( ParseException e ) {
            Log.e( TAG, edit_time.getText().toString(), e );
        }
    }

    private void onPositiveButton( int id ) {
        if( id == R.id.F2
         || id == R.id.F2t
         || id == R.id.find
         || id == R.id.new_zip
         || id == R.id.new_zipt
         || id == R.id.filter
         || id == R.id.open_via_SAF ) {
        doEditable( id );
        return;
        }
        if( id == R.id.open_zip ) {
            doOpenZip();
            return;
        }
        if( id == R.id.F8
         || id == R.id.F8t ) {
            boolean to_trash = false;
            CheckBox cb_to_trash = dialogObj.findViewById( R.id.to_trash );
            if( cb_to_trash != null && cb_to_trash.getVisibility() == View.VISIBLE ) {
                to_trash = cb_to_trash.isChecked();
                SharedPreferences.Editor sp_edit = PreferenceManager.getDefaultSharedPreferences( owner ).edit();
                sp_edit.putBoolean( SP_TRASH, to_trash );
                sp_edit.apply();
            }
            owner.panels.deleteItems( R.id.F8t == dialogId, to_trash );
            return;
        }
        if( id == R.id.set_date ) {
            doSetDate();
            return;
        }
        if( id == LOGIN_DIALOG ) {
            EditText name_edit = (EditText)dialogObj.findViewById( R.id.username_edit );
            EditText pass_edit = (EditText)dialogObj.findViewById( R.id.password_edit );
            if( name_edit != null && pass_edit != null )
                owner.panels.login( new Credentials( name_edit.getText().toString(), pass_edit.getText().toString() ), which_panel );
            which_panel = -1;
            return;
        }
        if( id == R.id.donate ) {
            owner.startViewURIActivity( R.string.donate_uri );
            return;
        }
        if( id == FILE_EXIST_DIALOG ) {
            owner.setResolution( Commander.REPLACE_ALL );
            return;
        }
        if( id == PROGRESS_DIALOG ) {   // hide
            owner.addBgNotifId( taskId );
            taskId = 0L;
            cancelDialog();
            return;
        }
   }

    @Override
    public void onClick( DialogInterface idialog, int whichButton ) {
        if( dialogObj == null )
            return;
        try {
            if( valid && whichButton == DialogInterface.BUTTON_POSITIVE ) {
                onPositiveButton( dialogId );
            } else if( whichButton == DialogInterface.BUTTON_NEGATIVE ) {
                if( dialogId == PROGRESS_DIALOG )
                    owner.stopEngine( taskId );
                else if( dialogId == FILE_EXIST_DIALOG )
                    owner.setResolution( Commander.ABORT );
            } else if( whichButton == DialogInterface.BUTTON_NEUTRAL ) {
                if( dialogId == FILE_EXIST_DIALOG )
                    owner.setResolution( Commander.SKIP_ALL );
            }
            owner.panels.focus();
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }
}
