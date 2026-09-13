package com.ghostsq.commander;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.ghostsq.commander.utils.TextSearcher;

public abstract class TextActivityBase extends Activity implements DialogInterface.OnClickListener, TextSearcher.SearchSink {
    public  final static String TAG = TextActivityBase.class.getSimpleName();
    protected String encoding;
    protected boolean ab;
    protected ColorsKeeper ck;
    protected Dialog searchDialog;
    protected TextSearcher ts;
    protected String       replaceTo;
//    protected int begLastFound = -1, endLastFound = -1;

    protected abstract CharSequence getText();

    @Override
    protected Dialog onCreateDialog( int id ) {
        if( searchDialog != null )
            return searchDialog;
        LayoutInflater factory = LayoutInflater.from( this );
        View view = factory.inflate( R.layout.text_search, null );
        AlertDialog ad = new AlertDialog.Builder( this )
                .setView( view )
                .setTitle( R.string.search_title )
                .setPositiveButton( R.string.dialog_ok, this )
                .setNegativeButton( R.string.dialog_cancel, this )
                .create();
        ad.getWindow().setSoftInputMode( WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE );
        return ad;
    }

    @Override
    protected void onPrepareDialog( int id, Dialog d ) {
        super.onPrepareDialog( id, d );
        EditText pattern = d.findViewById( R.id.pattern );
        CheckBox regex   = d.findViewById( R.id.regex );
        CheckBox cases   = d.findViewById( R.id.case_sensitive );
        CheckBox words   = d.findViewById( R.id.whole_words );
        CheckBox backw   = d.findViewById( R.id.backwards );
        SharedPreferences prefs = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE );
        pattern.setText(  prefs.getString( "pattern", "" ) );
        pattern.setSelection( 0, pattern.getText().length() );
        regex.setChecked( prefs.getBoolean( "regex", false ) );
        cases.setChecked( prefs.getBoolean( "cases", false ) );
        words.setChecked( prefs.getBoolean( "words", false ) );
        backw.setChecked( prefs.getBoolean( "backw", false ) );

        LinearLayout rb = d.findViewById( R.id.replace_block );
        boolean rm = id == R.id.replace;
        rb.setVisibility( rm ? View.VISIBLE : View.GONE );
        backw.setVisibility( rm ? View.GONE : View.VISIBLE );
        if( rm )
            ( (EditText) rb.findViewById( R.id.replace_to ) ).setText( prefs.getString( "replace", "" ) );
    }

    // --- DialogInterface.OnClickListener implementation ---

    @Override
    public void onClick( DialogInterface di, int which ) {
        if( which != DialogInterface.BUTTON_POSITIVE ) {
            di.cancel();
            return;
        }
        Dialog d = (Dialog)di;
        String  finds = ((EditText)d.findViewById( R.id.pattern )).getText().toString();
        boolean regex = ((CheckBox)d.findViewById( R.id.regex )).isChecked();
        boolean backw = ((CheckBox)d.findViewById( R.id.backwards )).isChecked();
        boolean cases = ((CheckBox)d.findViewById( R.id.case_sensitive )).isChecked();
        boolean words = ((CheckBox)d.findViewById( R.id.whole_words )).isChecked();
        LinearLayout rb = d.findViewById( R.id.replace_block );
        if( rb.getVisibility() == View.VISIBLE )
            replaceTo = ((EditText)rb.findViewById( R.id.replace_to )).getText().toString();
        else
            replaceTo = null;

        SharedPreferences.Editor editor = getSharedPreferences( getClass().getSimpleName(), MODE_PRIVATE ).edit();
        editor.putString( "pattern", finds );
        editor.putBoolean( "regex", regex );
        editor.putBoolean( "cases", cases );
        editor.putBoolean( "words", words );
        editor.putBoolean( "backw", backw );
        if( replaceTo != null )
            editor.putString( "replace", replaceTo );
        editor.commit();
        startSearch( finds, !cases, regex, words, !backw );
    }

    protected boolean startSearch( String search_for, boolean ignore_case, boolean regex, boolean words, boolean down ) {
        if( ts == null ) ts = new TextSearcher( this );
        CharSequence cs = getText();
        if( !(cs instanceof Spannable ) ) return false;
        clearFound( (Spannable)cs );
        ts.setTextToSearchIn( cs );
        if( regex )
            ts.setSearchRegEx( search_for, ignore_case, words );
        else
            ts.setSearchString( search_for, ignore_case, words );
        boolean found = ts.search( false, down );
        if( !found )
            Toast.makeText( this, getString( R.string.not_found ), Toast.LENGTH_SHORT ).show();
        invalidateOptionsMenu();
        return found;
    }

    protected boolean searchAgain( boolean down ) {
        if( ts == null ) {
            showDialog( R.id.search );
            return false;
        }
        boolean found = ts.search( true, down );
        if( !found ) {
            Toast.makeText( this, getString( R.string.not_found ), Toast.LENGTH_SHORT ).show();
        }
        return found;
    }

    protected boolean isReplaceMode() {
        return replaceTo != null;
    }

    protected void clearFound( Spannable sp ) {
        BackgroundColorSpan[] bspans = sp.getSpans( 0, sp.length(), BackgroundColorSpan.class );
        for( BackgroundColorSpan bcs : bspans )
            sp.removeSpan( bcs );
        ForegroundColorSpan[] fspans = sp.getSpans( 0, sp.length(), ForegroundColorSpan.class );
        for( ForegroundColorSpan fcs : fspans )
            sp.removeSpan( fcs );
    }

    // --- TextSearcher.SearchSink ---
    
    @Override
    public void error( String s ) {
        Toast.makeText( this, getString( R.string.failed ) + s, Toast.LENGTH_LONG ).show();
        Log.e( TAG, s );
    }
}
