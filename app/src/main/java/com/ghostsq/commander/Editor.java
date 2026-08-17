package com.ghostsq.commander;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewParent;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Scroller;
import android.widget.TextView;
import android.widget.Toast;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public class Editor extends TextActivityBase implements TextWatcher, OnTouchListener, OnGestureListener, TextEditor.onSelectionChangedListener {
    private final static String TAG = "EditorActivity";
    private final static String SP_ENC = "encoding", SP_NOWRAP = "no_wrap", SP_SS = "sel_start", SP_SE = "sel_end", SP_FN = "file";
//    final static int MENU_SAVE = 214, MENU_SVAS = 212, MENU_RELD = 439, MENU_WRAP = 241, MENU_ENC = 363, MENU_EXIT = 323;
//	final static String URI = "URIfileForEdit";

    private TextEditor te;
    private boolean horScroll = true;
    public  Uri uri;
    public  String fileName;
    public  CommanderAdapter ca;
    public  boolean dirty = false;
    public  String encoding;
    private DataLoadTask loader = null;
    private GestureDetector gd = null;
    private Scroller sc = null;
    private boolean selectionBySearch = false, autoReplace = false, withCR = false;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        try {
            uri = null;
            fileName = null;
            SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
            if( prefs != null ) {
                horScroll = prefs.getBoolean( SP_NOWRAP, true );
                encoding = prefs.getString( SP_ENC, null );
                try {
                    if( !Utils.str( encoding ) || !Charset.isSupported( encoding ) )
                        encoding = null;
                } catch( Exception e ) {
                    Log.e( TAG, encoding, e );
                    encoding = null;
                }
            }
            boolean ct_enabled = false, ab;
            ab = Utils.needActionBar( this );
            SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( this );
            Utils.setTheme( this, ColorsKeeper.getTheme( this ) );
            ck = new ColorsKeeper( this );
            ck.restore();
            if( ab ) {
                ab = Utils.setActionBar( this, false, false );
                ActionBar actionbar = getActionBar();
                actionbar.setDisplayOptions( ActionBar.DISPLAY_SHOW_CUSTOM );
                actionbar.setCustomView( R.layout.atitle );
                ct_enabled = true;
                setTitle( R.string.editor_label );
            } else {
                ct_enabled = requestWindowFeature( Window.FEATURE_CUSTOM_TITLE );
            }
            setContentView( R.layout.editor );
            if( !ab && !Utils.hasPermanentMenuKey( this ) ) {
                ImageButton mb = (ImageButton) findViewById( R.id.menu );
                if( mb != null ) {
                    mb.setVisibility( View.VISIBLE );
                    mb.setOnClickListener( new View.OnClickListener() {
                        @SuppressLint("NewApi")
                        @Override
                        public void onClick( View v ) {
                            try {
                                Log.d( TAG, "hasFeature(OP) " + Editor.this.getWindow().hasFeature( Window.FEATURE_OPTIONS_PANEL ) );
                                Log.d( TAG, "getActionBar() " + Editor.this.getActionBar() );
                                new Handler().postDelayed( new Runnable() {
                                    public void run() {
                                        Editor.this.openOptionsMenu();
                                    }
                                }, 100 );
                            } catch( Exception e ) {
                                Log.e( TAG, "Exception onclick", e );
                            }
                        }
                    } );
                }
            }
            te = (TextEditor)findViewById( R.id.editor );
            te.addTextChangedListener( this );
            te.setOnTouchListener( this );
            sc = new Scroller( this );
            te.setScroller( sc );
            te.setVerticalScrollBarEnabled( true );
            gd = new GestureDetector( this, this );

            // experimental!
            te.setFilters( new InputFilter[] { new InputFilter.LengthFilter( 0x7FFFFFFF ) } );

            String s_tfs = shared_pref.getString( "text_font_size", null );
            if( s_tfs == null )
                s_tfs = shared_pref.getString( "font_size", "14" );
            int fs = Integer.parseInt( s_tfs  );
            te.setTextSize( fs );

            te.setBackgroundColor( ck.bgrColor );
            te.setTextColor( ck.fgrColor );

            if( ct_enabled ) {
                if( !ab ) {
                    getWindow().setFeatureInt( Window.FEATURE_CUSTOM_TITLE, R.layout.atitle );
                    View at = findViewById( R.id.act_title );
                    if( at != null ) {
                        ViewParent vp = at.getParent();
                        if( vp instanceof FrameLayout ) {
                            FrameLayout flp = (FrameLayout) vp;
                            flp.setBackgroundColor( ck.ttlColor );
                            flp.setPadding( 0, 0, 0, 0 );
                        }
                        at.setBackgroundColor( ck.ttlColor );
                    }
                }
                TextView act_name_tv = (TextView)findViewById( R.id.act_name );
                if( act_name_tv != null ) {
                    act_name_tv.setText( R.string.editor_label );
                }
            }
            te.setHorizontallyScrolling( horScroll );
            te.setHighlightColor( ck.selColor );
            te.addOnSelectionChangedListener( this );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if( uri != null ) return;
        Intent in = getIntent();
        uri = in.getData();
        if( uri == null ) {
            Log.e( TAG, "No URI " + getIntent().toString() );
            Toast.makeText( this, getString( R.string.nothing_to_open ), Toast.LENGTH_LONG ).show();
            finish();
            return;
        }
        long size = in.getLongExtra( "size", -1 );
        fileName = in.getStringExtra( "filename" );
        if( !Utils.str( fileName ) )
            fileName = uri.getLastPathSegment();
        if( size > 100000 ) {
            Toast.makeText( this, getString( R.string.too_big_file, fileName ), Toast.LENGTH_LONG ).show();
            finish();
            return;
        }
        if( Utils.str( fileName ) ) {
            if( ".txt".equalsIgnoreCase( Utils.getFileExt( fileName ) ) )
                te.setInputType( InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES );
            setTitle();
        }
        loader = new DataLoadTask();
        loader.execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor editor = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE ).edit();
        if( encoding != null )
            editor.putString( SP_ENC, encoding );
        else
            editor.remove( SP_ENC );
        editor.putBoolean( SP_NOWRAP, horScroll );
        editor.putInt( SP_SS, te.getSelectionStart() );
        editor.putInt( SP_SE, te.getSelectionEnd() );
        editor.putString( SP_FN, fileName );
        editor.commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if( fileName != null )
            restoreSelection();
    }

    @Override
    protected void onStop() {
        //Log.d( TAG, "onStop" );
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        //Log.d( TAG, "onDestroy" );
        super.onDestroy();
        if( loader != null )
            loader.cancel( true );
        if( ca != null ) {
            //Log.d( TAG, "Prep destroy the CA" );
            ca.prepareToDestroy();
            ca = null;
        }
    }

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event ) {
        switch( keyCode ) {
            case KeyEvent.KEYCODE_BACK:
                if( dirty ) {
                    askToSave();
                    return true;
                }
        }
        return super.onKeyDown( keyCode, event );
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        try {
            Utils.changeLanguage( this );
            MenuInflater inflater = getMenuInflater();
            inflater.inflate( R.menu.edit_menu, menu );
            boolean sm = false, rm = false;
            if( ts != null ) {
                sm = true;
                rm = isReplaceMode() && selectionBySearch;
            }
            menu.findItem( R.id.search_down ).setVisible( sm );
            menu.findItem( R.id.search_up ).setVisible( sm );
            menu.findItem( R.id.replace_this ).setVisible( sm && rm );
            menu.findItem( R.id.replace_all ).setVisible( sm && rm );
            return true;
        } catch( Error e ) {
            Log.e( TAG, "", e );
            Log.e( TAG, "", e );
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu ) {
        MenuItem mi_en = menu.findItem( R.id.encoding );
        mi_en.setTitle( getString( R.string.encoding ) + " '" + Utils.getEncodingDescr( this, encoding, Utils.ENC_DESC_MODE_BRIEF ) + "'" );
        MenuItem mi_wr = menu.findItem( R.id.wrap );
        mi_wr.setChecked( !horScroll );
        MenuItem mi_cr = menu.findItem( R.id.crlf );
        mi_cr.setChecked( withCR );
        return super.onPrepareOptionsMenu( menu );
    }

    @Override
    public boolean onMenuItemSelected( int featureId, MenuItem item ) {
        final int id = item.getItemId();
        if( id == R.id.search || id == R.id.replace ) {
            showDialog( id, null );
            return true;
        }
        if( id == R.id.search_up ) {
            searchAgain( false );
            return true;
        }
        if( id == R.id.search_down ) {
            searchAgain( true );
            return true;
        }
        if( id == R.id.replace_this ) {
            replace();
            return true;
        }
        if( id == R.id.replace_all ) {
            replaceAll();
            return true;
        }
        if( id == R.id.save ) {
            new DataSaveTask( te.getText(), false ).execute( uri );
            return true;
        }
        if( id == R.id.save_as ) {
            try {
                LayoutInflater factory = LayoutInflater.from( this );
                View iv = factory.inflate( R.layout.input, null );
                if( iv != null ) {
                    TextView prompt = (TextView)iv.findViewById( R.id.prompt );
                    final EditText edit = (EditText)iv.findViewById( R.id.edit_field );
                    prompt.setText( R.string.new_item_prompt );
                    String path = uri.getPath();
                    if( path == null ) return false;
                    final int _alsp = path.lastIndexOf( '/' ) + 1;
                    edit.setText( path.substring( _alsp ) );
                    new AlertDialog.Builder( this )
                            .setTitle( R.string.save_as )
                            .setView( iv )
                            .setPositiveButton( R.string.save, new DialogInterface.OnClickListener() {
                                public void onClick( DialogInterface dialog, int i ) {
                                    String fn = edit.getText().toString();
                                    if( !Utils.str( fn ) ) return;
                                    uri = Editor.this.uri.buildUpon().path( uri.getPath().substring( 0, _alsp ) + fn ).build();
                                    new DataSaveTask( te.getText(), false ).execute( uri );
                                }
                            } ).setNegativeButton( R.string.dialog_cancel, null ).show();
                }
            } catch( Throwable e ) {
                Log.e( TAG, "", e );
            }
            return true;
        }
        if( id == R.id.reload ) {
            loader = new DataLoadTask();
            loader.execute();
            return true;
        }
        if( id == R.id.encoding ) {
            String enc_num = getEncodingDescr( Utils.ENC_DESC_MODE_NUMB );
            if( enc_num == null ) enc_num = "0";
            int cen = Integer.parseInt( enc_num );
            new AlertDialog.Builder( this )
                    .setTitle( R.string.encoding )
                    .setSingleChoiceItems( R.array.encoding, cen, new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dialog, int i ) {
                            dialog.dismiss();
                            Editor.this.encoding = getResources().getStringArray( R.array.encoding_vals )[i];
                            Log.i( TAG, "Chosen encoding: " + Editor.this.encoding );
                            Editor.this.showMessage( getString( R.string.encoding_set, Editor.this.getEncodingDescr( Utils.ENC_DESC_MODE_BRIEF ) ) );
                        }
                    } ).show();
            return true;
        }
        if( id == R.id.wrap ) {
            try {
                EditText te = (EditText)findViewById( R.id.editor );
                horScroll = item.isChecked();
                invalidateOptionsMenu();
                te.setHorizontallyScrolling( horScroll );
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
            return true;
        }
        if( id == R.id.crlf ) {
            withCR = !item.isChecked();
            invalidateOptionsMenu();
            return true;
        }
        if( id == R.id.exit ) {
            if( dirty )
                askToSave();
            else {
                //Log.d( TAG, "finishing" );
                finish();
            }
        }
        return super.onMenuItemSelected( featureId, item );
    }

    private void setTitle() {
        TextView file_name_tv = (TextView)findViewById( R.id.file_name );
        if( file_name_tv != null ) {
            if( !fileName.isEmpty() ) {
                file_name_tv.setTextSize( 32.0f / ( fileName.length() + 8 ) + 10 );
            }
            file_name_tv.setText( fileName );
        }
        else
            setTitle( fileName );
    }

    private final String getEncodingDescr( int mode ) {
        String ed = Utils.getEncodingDescr( Editor.this, encoding, mode );
        if( ed != null ) return ed;
        if( mode == Utils.ENC_DESC_MODE_NUMB ) return "0";
        return "";
    }

    private final void askToSave() {
        DialogInterface.OnClickListener ocl = new DialogInterface.OnClickListener() {
            public void onClick( DialogInterface dialog, int which_button ) {
                if( which_button == DialogInterface.BUTTON_POSITIVE ) {
                    new DataSaveTask( te.getText(), true ).execute( uri );
                } else if( which_button == DialogInterface.BUTTON_NEGATIVE ) {
                    //Log.d( TAG, "finishing" );
                    Editor.this.finish();
                }
            }
        };
        new AlertDialog.Builder( this )
                .setIcon( android.R.drawable.ic_dialog_alert )
                .setTitle( R.string.save )
                .setMessage( R.string.not_saved )
                .setPositiveButton( R.string.save, ocl )
                .setNegativeButton( R.string.dialog_cancel, ocl )
                .show();
    }


    public final void showMessage( String s ) {
        Toast.makeText( this, s, Toast.LENGTH_LONG ).show();
    }

    private void restoreSelection() {
        SharedPreferences sp = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
        if( !fileName.equals( sp.getString( SP_FN, null ) ) ) return;
        int ss = sp.getInt( SP_SS, 0 ), se = sp.getInt( SP_SE, 0 );
        int len = te.getText().length();
        if( len < ss || len < se ) return;
        Log.d( TAG, "Restoring selection from SP: " + ss + "-" + se );
        te.setSelection( ss, se );
        te.requestFocus();
    }

    // --- TextActivityBase ---

    @Override
    protected CharSequence getText() {
        return te != null ? te.getText() : null;
    }

    @Override
    protected boolean searchAgain( boolean down ) {
        if( ts == null ) {
            showDialog( R.id.search );
            return false;
        }
        int beg = te.getSelectionStart();
        int end = te.getSelectionEnd();
        if( beg < 0 || end < 0 ) {
            showDialog( R.id.replace );
            return false;
        }
        boolean found = ts.search( down ? end : beg, down );
        if( !found ) {
            Toast.makeText( this, getString( R.string.not_found ), Toast.LENGTH_SHORT ).show();
            autoReplace = false;
        }
        return found;
    }

    protected void replace() {
        if( ts == null || replaceTo == null ) {
            showDialog( R.id.replace );
            return;
        }
        CharSequence cs = getText();
        if( !(cs instanceof Editable) ) {
            Log.e( TAG, "Text is not editable!" );
            return;
        }
        Editable ed = (Editable)cs;
        int beg = te.getSelectionStart();
        int end = te.getSelectionEnd();
        if( beg < 0 || end < 0 ) {
            showDialog( R.id.replace );
            return;
        }
        ed.replace( beg, end, replaceTo );
        ts.invalidateCurrentString();
        searchAgain( ts.lastDirectionWasDown() );
    }

    protected void replaceAll() {
        autoReplace = true;
        replace();
    }


    // --- TextSearcher.SearchSink implementation ---

    @Override
    public void found( int beg, int end ) {
        te.setSelection( beg, end );
        if( !selectionBySearch ) {
            selectionBySearch = true;
            invalidateOptionsMenu();
        }
        if( autoReplace )
            replace();
    }

    // --- TextEditor.onSelectionChangedListener implementation ---

    @Override
    public void onSelectionChanged( int selStart, int selEnd ) {
        if( selectionBySearch ) {
            selectionBySearch = false;
            invalidateOptionsMenu();
        }
    }

    // --- file I/O ---

    private class DataLoadTask extends AsyncTask<Void, String, CharSequence> {
        private ProgressDialog pd;

        @Override
        protected void onPreExecute() {
            pd = ProgressDialog.show( Editor.this, "", getString( R.string.loading ), true, true );
        }

        @Override
        protected CharSequence doInBackground( Void... v ) {
            Uri uri = Editor.this.uri;
            try {
                //Log.d( TAG, "loading file from " + uri.toString() );
                final String scheme = uri.getScheme();
                InputStream is = null;
                if( Editor.this.ca == null ) {
                    Editor.this.ca = CA.CreateAdapterInstance( uri, Editor.this );
                }
                if( Editor.this.ca != null ) {
                    Credentials crd = null;
                    try {
                        crd = (Credentials) Editor.this.getIntent().getParcelableExtra( Credentials.KEY );
                    } catch( Exception e ) {
                        Log.e( TAG, "on taking credentials from parcel", e );
                    }
                    Editor.this.ca.setCredentials( crd );
                    is = Editor.this.ca.getContent( uri );
                }
                if( is != null ) {
                    CharSequence cs = Editor.this.readStreamToBuffer( is, encoding );
                    if( Editor.this.ca != null ) {
                        Editor.this.ca.closeStream( is );
                    } else
                        is.close();
                    return cs;
                }
                publishProgress( getString( R.string.rtexcept, uri.toString(), "" ) );
            } catch( OutOfMemoryError e ) {
                Log.e( TAG, uri.toString(), e );
                publishProgress( getString( R.string.too_big_file, uri.getPath() ) );
            } catch( Throwable e ) {
                Log.e( TAG, uri.toString(), e );
                publishProgress( getString( R.string.failed ) + e.getLocalizedMessage() );
            }
            return null;
        }


        @Override
        protected void onProgressUpdate( String... err ) {
            if( err.length > 0 ) Editor.this.showMessage( err[0] );
        }

        @Override
        protected void onPostExecute( CharSequence cs ) {
            pd.cancel();
            Editor.this.te.setText( cs );
            Editor.this.dirty = false;
            Editor.this.loader = null;
            Editor.this.restoreSelection();
            Editor.this.invalidateOptionsMenu();
        }
    }

    public CharSequence readStreamToBuffer( InputStream is, String encoding ) {
        if( is != null ) {
            try {
                int bytes = is.available();
                if( bytes < 1024  )
                    bytes = 1024;
                if( bytes > 1048576 )
                    bytes = 1048576;
                char[] chars = new char[bytes];
                InputStreamReader isr = null;
                if( Utils.str( encoding ) ) {
                    try {
                        isr = new InputStreamReader( is, encoding );
                    } catch( UnsupportedEncodingException e ) {
                        Log.w( "GC", encoding, e );
                        isr = new InputStreamReader( is );
                    }
                } else
                    isr = new InputStreamReader( is );
                withCR = false;
                StringBuffer sb = new StringBuffer( bytes );
                int n = -1;
                boolean available_supported = is.available() > 0;
                while( true ) {
                    n = isr.read( chars, 0, bytes );
                    // Log.v( "readStreamToBuffer", "Have read " + n + " chars" );
                    if( n < 0 )
                        break;
                    int from = 0;
                    for( int i = 0; i < n; i++ ) {
                        if( chars[i] == 0x0D ) {
                            withCR = true;
                            sb.append( chars, from, i - from );
                            from = ++i;
                        }
                    }
                    sb.append( chars, from, n - from );
                    if( available_supported ) {
                        for( int i = 1; i < 5; i++ ) {
                            if( is.available() > 0 )
                                break;
                            // Log.v( "readStreamToBuffer", "Waiting the rest " + i );
                            Thread.sleep( 10 * i );
                        }
                        if( is.available() == 0 ) {
                            // Log.v( "readStreamToBuffer", "No more data!" );
                            break;
                        }
                    }
                }
                return sb;
            } catch( Throwable e ) {
                Log.e( "Utils", "Error on reading a stream", e );
            }
        }
        return null;
    }

    private class DataSaveTask extends AsyncTask<Uri, String, Boolean> {
        private ProgressDialog pd;
        private boolean close_on_finish;
        private String saved_as;
        Editable editable;

        DataSaveTask( Editable editable, boolean close_on_finish ) {
            this.editable = editable;
            this.close_on_finish = close_on_finish;
        }

        @Override
        protected void onPreExecute() {
            pd = ProgressDialog.show( Editor.this, "", getString( R.string.wait ), true, true );
        }

        @Override
        protected Boolean doInBackground( Uri... save_uri_ ) {
            Uri save_uri = save_uri_.length > 0 ? save_uri_[0] : null;
            if( Editor.this.ca == null ) {
                Log.e( TAG, "Adapter is null " );
                return false;
            }
            if( save_uri == null ) {
                Log.e( TAG, "No URI to save" );
                return false;
            }
            if( this.editable == null ) {
                Log.e( TAG, "No editable" );
                return false;
            }
            //Log.d( TAG, "saving file to " + save_uri.toString() );
            Credentials crd = null;
            try {
                crd = (Credentials) Editor.this.getIntent().getParcelableExtra( Credentials.KEY );
            } catch( Exception e ) {
                Log.e( TAG, "on taking credentials from parcel", e );
            }
            Editor.this.ca.setCredentials( crd );
            OutputStream os = Editor.this.ca.saveContent( save_uri );
            if( os == null ) {
                Log.e( TAG, "No output stream" );
                return false;
            }
            Log.d( TAG, "Gout output stream: " + os.toString() );
            try {
                OutputStreamWriter osw = Editor.this.encoding != null && !Editor.this.encoding.isEmpty() ?
                        new OutputStreamWriter( os, Editor.this.encoding ) :
                        new OutputStreamWriter( os );
                int len = editable.length();
                Log.d( TAG, "length (chars): " + len );
                int buf_size = 1024 * 16;
                if( len < buf_size )
                    buf_size = len;
                char[] chars = new char[buf_size];
                int start = 0, end = buf_size, cnt = 0;
                while( start < len - 1 ) {
                    editable.getChars( start, end, chars, 0 );
                    int n = end - start;
                    if( withCR ) {
                        int from = 0;
                        for( int i = 0; i < n; i++ ) {
                            if( chars[i] == 0x0A ) {
                                osw.write( chars, from, i - from );
                                osw.write( 0x0D );
                                from = i;
                            }
                        }
                        osw.write( chars, from, n - from );
                    } else
                        osw.write( chars, 0, n );
                    start = end;
                    end += buf_size;
                    if( end > len )
                        end = len;
                    cnt++;
                }

                //Log.d( TAG, "IO iterations: " + cnt );
                osw.flush();
                Editor.this.ca.closeStream( os );
                saved_as = save_uri.getLastPathSegment();
                publishProgress( getString( R.string.saved, saved_as ) );
                return true;
            } catch( Throwable e ) {
                Log.e( TAG, Favorite.screenPwd( save_uri ), e );
            }
            return false;
        }

        @Override
        protected void onProgressUpdate( String... err ) {
            if( err.length > 0 ) Editor.this.showMessage( err[0] );
        }

        @Override
        protected void onPostExecute( Boolean succeeded ) {
            pd.cancel();
            if( succeeded ) {
                Editor.this.dirty = false;
                if( saved_as != null && !saved_as.equals( Editor.this.fileName ) ) {
                    Editor.this.fileName = saved_as;
                    Editor.this.setTitle();
                }
            } else
                Editor.this.showMessage( Editor.this.getString( R.string.cant_save ) );
            if( close_on_finish ) {
                //Log.d( TAG, "finishing" );
                Editor.this.finish();
            }
        }
    }

    // --- TextWatcher methods --- 

    @Override
    public void afterTextChanged( Editable s ) {
        dirty = true;
    }

    @Override
    public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
    }

    @Override
    public void onTextChanged( CharSequence s, int start, int before, int count ) {
    }

    // --- OnTouchListener method ---

    @Override
    public boolean onTouch( View view, MotionEvent ev ) {
        //Log.d( TAG, "onTouch: " + ev.toString() );
        sc.abortAnimation();
        return gd.onTouchEvent( ev );
    }

    // --- OnGestureListener methods ---

    @Override
    public boolean onDown( MotionEvent ev ) {
        //Log.d( TAG, "onDown: " + ev.toString() );
        return false;
    }

    @Override
    public void onShowPress( MotionEvent ev ) {
        //Log.d( TAG, "onShowPress: " + ev.toString() );
    }

    @Override
    public boolean onSingleTapUp( MotionEvent ev ) {
        //Log.d( TAG, "onSingleTapUp: " + ev.toString() );
        return false;
    }

    @Override
    public boolean onScroll( MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY ) {
        //Log.d( TAG, "onScroll " + distanceY + ": " + ev1.toString() + "\n" + ev1.toString()  );
        return false;
    }

    @Override
    public void onLongPress( MotionEvent ev ) {
        //Log.d( TAG, "onLongPress: " + ev.toString() );        
    }

    @Override
    public boolean onFling( MotionEvent ev1, MotionEvent ev2, float velocityX, float velocityY ) {
        if( Math.abs( velocityY ) < 1000 ) return false;
        //Log.d( TAG, "onFling " + velocityY + ": " + ev1.toString() + "\n" + ev1.toString()  );
        float maxY = te.getLineCount() * te.getLineHeight();
        //Log.d( TAG, "maxY=" + maxY );
        sc.fling( (int) te.getScrollX(), (int) te.getScrollY(), (int) velocityX, -(int) velocityY, 0, 0, 0, (int) maxY );
        return true;
    }
}
