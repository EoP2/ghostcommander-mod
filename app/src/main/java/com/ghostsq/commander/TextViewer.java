package com.ghostsq.commander;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import static android.content.DialogInterface.BUTTON_POSITIVE;

public class TextViewer extends TextActivityBase implements View.OnLayoutChangeListener {
    public  final static String TAG = "TextViewerActivity";
    private final static String SP_ENC = "encoding", SP_NOWRAP = "no_wrap";
    public  final static String STRURI = "string:";
    public  final static String STRKEY = "string";
    private ScrollView     scrollView;
    public  TextView       textView;
    public  ProgressDialog progress;
    public  boolean        loaded, noWrap;
    public  Uri uri;
    public  String mime;
    private DataLoadTask   loader;

    @Override
    public void onCreate( Bundle savedInstanceState ) {
        try {
            boolean ct_enabled = false;
            ab = Utils.needActionBar( this );
            SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( this );
            Utils.setTheme( this, ColorsKeeper.getTheme( this ) );
            ck = new ColorsKeeper( this );
            ck.restore();
            if( ab ) {
                Utils.setActionBar( this, false, false );
                ActionBar actionbar = getActionBar();
                if( actionbar != null ) {
                    actionbar.setDisplayOptions( ActionBar.DISPLAY_SHOW_CUSTOM );
                    actionbar.setCustomView( R.layout.atitle );
                }
                ct_enabled = true;
                setTitle( R.string.textvw_label );
            } else {
                ct_enabled = requestWindowFeature( Window.FEATURE_CUSTOM_TITLE );
            }
            super.setContentView( R.layout.textvw );

            if( !ab && !Utils.hasPermanentMenuKey( this ) ) {
                ImageButton mb = (ImageButton)findViewById( R.id.menu );
                if( mb != null ) {
                    mb.setVisibility( View.VISIBLE );
                    mb.setOnClickListener( new OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                            TextViewer.this.openOptionsMenu();
                        }
                    });
                }
            }
            String s_tfs = shared_pref.getString( "text_font_size", null );
            if( s_tfs == null )
                s_tfs = shared_pref.getString( "font_size", "14" );
            int fs = Integer.parseInt( s_tfs  );
            textView = findViewById( R.id.text_view );
            if( textView == null ) {
                Log.e( TAG, "No text view to show the content!" );
                finish();
                return;
            }
            textView.setTextSize( fs );
            textView.setTypeface( Typeface.create( "monospace", Typeface.NORMAL ) );

            textView.setBackgroundColor( ck.bgrColor );
            textView.setTextColor( ck.fgrColor );

            textView.addTextChangedListener( new TextWatcher() {
                @Override
                public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
                }

                @Override
                public void onTextChanged( CharSequence s, int start, int before, int count ) {
                }

                @Override
                public void afterTextChanged( Editable s ) {
                    Log.v( TAG, "afterTextChanged()" );
                    if( loaded )
                        cancelProgress();
                }
            } );

            if( ct_enabled ) {
                if( !ab ) {
                    Window w = getWindow();
                    w.setFeatureInt( Window.FEATURE_CUSTOM_TITLE, R.layout.atitle );
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
                if( act_name_tv != null )
                    act_name_tv.setText( R.string.textvw_label );
            }
            scrollView = (ScrollView)findViewById( R.id.scroll_view );
            scrollView.setBackgroundColor( ck.bgrColor );
            scrollView.addOnLayoutChangeListener( this );

            super.onCreate( savedInstanceState );
        }
        catch( Exception e ) {
            Toast.makeText( TextViewer.this, getString( R.string.unkn_err ), Toast.LENGTH_LONG ).show();
            Log.e( TAG, "", e );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
        if( prefs != null ) {
            encoding = prefs.getString( SP_ENC, "" );
            noWrap = prefs.getBoolean( SP_NOWRAP, true );
        }
        Intent in = getIntent();
        uri = in.getData();
        mime = in.getType();
        if( uri == null || !loadData() ) {
            Log.e( TAG, "No URI " + getIntent().toString() );
            Toast.makeText( TextViewer.this, getString( R.string.nothing_to_open ), Toast.LENGTH_LONG ).show();
            finish();
            return;
        }
        String fn = in.getStringExtra( "filename" );
        if( !Utils.str( fn ) )
            fn = uri.getLastPathSegment();
        if( Utils.str( fn ) ) {
            TextView file_name_tv = (TextView)findViewById( R.id.file_name );
            if( file_name_tv != null ) {
                if( fn.length() > 12 ) {
                    file_name_tv.setTextSize( 32.0f / ( fn.length() + 8 ) + 10 );
                }
                file_name_tv.setText( fn );
            }
            else
                setTitle( fn );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor editor = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE ).edit();
        editor.putString( SP_ENC, encoding == null ? "" : encoding );
        editor.putBoolean( SP_NOWRAP, noWrap );
        editor.apply();
    }

    @Override
    protected void onStop() {
        if( BuildConfig.DEBUG ) Log.d( TAG, "Stopping\n" );
        super.onStop();
        if( loader != null )
            loader.cancel( true );
    }

    @Override
    protected void onSaveInstanceState( Bundle toSaveState ) {
        Log.i( TAG, "Saving State: " + encoding );
        toSaveState.putString( SP_ENC, encoding == null ? "" : encoding );
        toSaveState.putBoolean( SP_NOWRAP, noWrap );
        super.onSaveInstanceState( toSaveState );
    }

    @Override
    protected void onRestoreInstanceState( Bundle savedInstanceState ) {
        if( savedInstanceState != null ) {
            encoding = savedInstanceState.getString( SP_ENC );
            noWrap   = savedInstanceState.getBoolean( SP_NOWRAP );
        }
        Log.i( TAG, "Restored State " + encoding );
        super.onRestoreInstanceState( savedInstanceState );
    }

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event ) {
        int c_id = -1;
        char c = (char)event.getUnicodeChar();
        switch( c ) {
        case 'q':
        case '0':
            finish();
            return true;
        case 'g':
            return dispatchCommand( R.id.top );
        case 'G':
            return dispatchCommand( R.id.bot );
        case '/':
            c_id = R.id.search;
            break;
        case 'n':
            c_id = R.id.search_down;
            break;
        case 'p':
            c_id = R.id.search_up;
            break;
        }
        if( c_id != -1 ) {
            final int _c_id = c_id;
            textView.post( new Runnable() {
                @Override
                public void run() {
                    dispatchCommand( _c_id );
                }
            } );
            return true;
        }
        return super.onKeyDown( keyCode, event );
    }
    
    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        try {
            Utils.changeLanguage( this );
            MenuInflater inflater = getMenuInflater();
            inflater.inflate( R.menu.text_menu, menu );
            MenuItem mi_sd = menu.findItem( R.id.search_down );
            MenuItem mi_su = menu.findItem( R.id.search_up );
            boolean vis = ts != null;
            mi_sd.setVisible( vis );
            mi_su.setVisible( vis );
            return true;
        } catch( Error e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu ) {
        MenuItem mi_en = menu.findItem( R.id.encoding );
        mi_en.setTitle( getString( R.string.encoding ) + " '" + Utils.getEncodingDescr( this, encoding, Utils.ENC_DESC_MODE_BRIEF ) + "'" );
        MenuItem mi_wr = menu.findItem( R.id.wrap );
        mi_wr.setChecked( !noWrap );
        return super.onPrepareOptionsMenu( menu );
    }

    @Override
    public boolean onMenuItemSelected( int featureId, MenuItem item ) {
        if( dispatchCommand( item.getItemId() ) ) return true; 
        return super.onMenuItemSelected( featureId, item );
    }

    public boolean dispatchCommand( int id ) {
        if( id == R.id.bot ) {
            scrollView.fullScroll( View.FOCUS_DOWN );
            return true;
        }
        if( id == R.id.top ) {
            scrollView.fullScroll( View.FOCUS_UP );
            return true;
        }
        if( id == R.id.search ) {
            showDialog( R.id.search );
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
        if( id == R.id.encoding ) {
            int cen = Integer.parseInt( Utils.getEncodingDescr( this, encoding, Utils.ENC_DESC_MODE_NUMB ) );
            new AlertDialog.Builder( this )
                    .setTitle( R.string.encoding )
                    .setSingleChoiceItems( R.array.encoding, cen, new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dialog, int i ) {
                            encoding = getResources().getStringArray( R.array.encoding_vals )[i];
                            Log.i( TAG, "Chosen encoding ) { " + encoding );
                            dialog.dismiss();
                            loadData();
                        }
                    } ).show();
            return true;
            }
        if( id == R.id.wrap ) {
            try {
                noWrap = !noWrap;
                invalidateOptionsMenu();
                if( noWrap ) {
                    textView.setMaxWidth( Integer.MAX_VALUE );
                } else {
                    int sw = scrollView.getWidth();
                    Log.d( TAG, "scroll width: " + sw );
                    textView.setMaxWidth( sw );
                }
            } catch( Exception e ) {
                Log.e( TAG, "", e );
            }
            return true;
        }
        if( id == R.id.exit ) {
            finish();
        }
        return false;
    }

    @Override
    public void onLayoutChange( View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom ) {
         if( noWrap )
             return;
         int maxw = textView.getMaxWidth();
         int sw = scrollView.getWidth();
         if( maxw != sw ) {
             Log.d( TAG, "layout has changed. New scroll width: " + sw );
             textView.setMaxWidth( sw );
         }
    }

    @Override
    protected CharSequence getText() {
        return textView.getText();
    }

    protected void scrollTo( int pos ) {
        int line = textView.getLayout().getLineForOffset( pos );
        int y = (int)((line + 0.5) * textView.getLineHeight());
        y -= scrollView.getHeight() / 2;
        scrollView.smoothScrollTo( 0, y );
        textView.requestFocus();
    }

    // --- TextSearcher.SearchSink implementation ---

    @Override
    public void found( int beg, int end ) {
        Log.d( TAG, "found: " + beg + "-" + end );
        CharSequence cs = getText();
        if( !(cs instanceof Spannable ) ) {
            Log.e( TAG, "Text is not spannable!" );
            return;
        }
        Spannable sp = (Spannable)cs;
        clearFound( sp );
        sp.setSpan( new BackgroundColorSpan( ck.selColor ), beg, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
        sp.setSpan( new ForegroundColorSpan( ck.sfgColor ), beg, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
        scrollTo( end );
    }

    private static class DataLoadTask extends AsyncTask<Void, CharSequence, CharSequence> {
        private TextViewer owner;
        private Uri uri;
        public DataLoadTask( TextViewer owner ) {
            this.owner = owner;
            this.uri = owner.uri;
        }

        @Override
        protected CharSequence doInBackground( Void... v ) {

            try {
                final String scheme = uri.getScheme();
                CommanderAdapter ca = null;
                InputStream is = null;
                if( ContentResolver.SCHEME_CONTENT.equals( scheme ) ) {
                    is = owner.getContentResolver().openInputStream( uri );
                } else {
                    ca = CA.CreateAdapterInstance( uri, owner );
                    if( ca != null ) {
                        Credentials crd = null; 
                        try {
                            crd = (Credentials)owner.getIntent().getParcelableExtra( Credentials.KEY );
                        } catch( Exception e ) {
                            Log.e( TAG, "on taking credentials from parcel", e );
                        }
                        ca.setCredentials( crd );
                        is = ca.getContent( uri );
                    }
                }
                if( is == null ) {
                    publishProgress( owner.getString( R.string.nothing_to_open ) + ( ca != null ? ca.getLatestError() : "" ) );
                    return null;
                }

                int buf_sz, avail = is.available();
                if( avail == 0 ) {
                    for( int i = 1; i < 5; i++ ) {
                        Thread.sleep( 50 * i );
                        avail = is.available();
                        if( avail > 0 )
                            break;
                        Log.v( TAG, "Waiting for the stream " + i );
                    }
                }
                if( avail < 32768 )
                    buf_sz = 32768;
                else
                if( avail > 131072 )
                    buf_sz = 131072;
                else
                    buf_sz = avail;
                Log.v( TAG, "Initially available " + avail );
                char[] chars = new char[buf_sz];
                InputStreamReader isr = null;
                if( Utils.str( owner.encoding ) ) {
                    try {
                        isr = new InputStreamReader( is, owner.encoding );
                    } catch( UnsupportedEncodingException e ) {
                        Log.w( TAG, owner.encoding, e );
                        isr = new InputStreamReader( is );
                    }
                } else
                    isr = new InputStreamReader( is );
                StringBuffer sb = new StringBuffer( buf_sz );
                int n, last_len = 0;
                while( true ) {
                    if( isCancelled() )
                        break;
                    int to_read = last_len == 0 ? 4096 : buf_sz;
                    if( BuildConfig.DEBUG )
                        Log.v( TAG, "Trying to read " + to_read + " chars" );
                    n = isr.read( chars, 0, to_read );
                    if( BuildConfig.DEBUG )
                        Log.v( TAG, "Have read " + n + " chars" );
                    if( n < 0 )
                        break;
                    for( int i = 0; i < n; i++ ) {
                        if( chars[i] == 0x0D )
                            chars[i] = ' ';
                    }
                    sb.append( chars, 0, n );
                    int len = sb.length();
                    if( last_len == 0 ) {
                        last_len = len;
                        Log.v( TAG, "Passing the current buffer of " + len + " chars" );
                        publishProgress( sb.toString() );
                        Thread.yield();
                    }

                    if( avail > 0 ) {
                        for( int i = 1; i < 5; i++ ) {
                            if( isr.ready() )
                                break;
                            if( BuildConfig.DEBUG )
                                Log.v( TAG, "Waiting for the rest " + i );
                            Thread.sleep( 50 * i );
                        }
                    }
                }
                if( ca != null ) {
                    ca.closeStream( is );
                    ca.prepareToDestroy();
                }
                else
                    is.close();
                int len = sb.length();
                Log.d( TAG, "Final size: " + len );
                return sb;
            } catch( OutOfMemoryError e ) {
                Log.e( TAG, uri.toString(), e );
                publishProgress( owner.getString( R.string.too_big_file, uri.getPath() ) );
            } catch( Throwable e ) {
                Log.e( TAG, uri.toString(), e );
                publishProgress( owner.getString( R.string.failed ) + e.getLocalizedMessage() );
            }
            owner.cancelProgress();
            return null;
        }

        int prev_len = 0;

        @Override
        protected void onProgressUpdate( CharSequence... css ) {
            try {
                Log.v( TAG, "onProgressUpdate: " + css[0].length() );
                if( owner.textView == null ) {
                    Log.e( TAG, "No TextView!" );
                    return;
                }
                if( css == null || css.length == 0 ) {
                    Log.e( TAG, "Nothing loaded!" );
                    return;
                }
                if( !this.isCancelled() )
                    saveToTextView( css[0] );
            } catch( Throwable e ) {
                onProgressUpdate( owner.getString( R.string.failed ) + e.getLocalizedMessage() );
                Log.e( TAG, "", e );
            }
        }

        @Override
        protected void onCancelled( CharSequence cs ) {

        }

        @Override
        protected void onPostExecute( CharSequence cs ) {
            if( cs == null ) {
                Log.e( TAG, "No characters were loaded!" );
                return;
            }
            Log.v( TAG, "postexecute, size: " + cs.length() );
            if( this.isCancelled() )
                return;
            if( cs.length() > 524288 ) {
                final AsyncTask<Void, CharSequence, CharSequence> _this = this;
                DialogInterface.OnClickListener ocl = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        dialog.dismiss();
                        if( which == BUTTON_POSITIVE ) {
                            owner.loaded = true;
                            saveToTextView( cs );
                        }
                        owner.cancelProgress();
                    }
                };
                new AlertDialog.Builder( owner )
                    .setTitle( android.R.string.dialog_alert_title )
                    .setIcon( android.R.drawable.ic_dialog_alert )
                    .setMessage( owner.getString( R.string.too_big_file, uri.toString() ) )
                    .setPositiveButton( android.R.string.ok, ocl )
                    .setNegativeButton( android.R.string.cancel, ocl )
                    .show();
                return;
            }
            Log.v( TAG, "loaded = true;" );
            owner.loaded = true;
            saveToTextView( cs );
            owner.cancelProgress();
        }

        private boolean saveToTextView( CharSequence cs ) {
            try {
                if( cs == null ) {
                    Log.w( TAG, "No CS!" );
                    return false;
                }
                int len = cs.length();
                Log.d( TAG, "Loaded characters: " + len );
                if( prev_len >= len ) return false;
                owner.textView.setText( cs );
                prev_len = len;
                Log.d( TAG, "Saved to textview " );
                return true;
            } catch( Throwable e ) {
                Log.e( TAG, "", e );
            }
            return false;
        }
     }
     
     private boolean loadData() {
        if( uri != null ) { 
            try {
                final String   scheme = uri.getScheme();
                if( STRKEY.equals( scheme ) ) {
                    Intent i = getIntent();
                    String str = i.getStringExtra( STRKEY );
                    if( str != null ) {
                        textView.setText( str );
                        return true;
                    }
                    return false;
                }
                progress = new ProgressDialog( this );
                progress.setMessage( getString( R.string.loading ) );
                progress.show();
                if( loader != null ) {
                    Log.w( TAG, "Previous loader has not finished!!!" );
                    loader.cancel( true );
                }
                loader = new DataLoadTask( this );
                loader.execute();
                return true;
            } catch( OutOfMemoryError e ) {
                Log.e( TAG, uri.toString(), e );
                Toast.makeText(this, getString( R.string.too_big_file, uri.getPath() ), Toast.LENGTH_LONG).show();
            } catch( Throwable e ) {
                Log.e( TAG, uri.toString(), e );
                Toast.makeText(this, getString( R.string.failed ) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            }
        }
        return false;
    }

    public void cancelProgress() {
        if( progress == null ) return;
        progress.dismiss();
        progress = null;
        loaded = false;
    }
}
