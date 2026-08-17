package com.ghostsq.commander;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.widget.SeekBar;
import android.widget.TextView;

public class ThumbSizeDialog extends AlertDialog implements DialogInterface.OnClickListener,
        SeekBar.OnSeekBarChangeListener {

    public interface ResultSink {
        void selZoneChanged( boolean atRight, int width, boolean highlight );
    }

    private final static String TAG = "ThmbSz";
    private final Context context;
    private TextView vv;
    private int size;

    ThumbSizeDialog( Context c ) {
        super( c );
        context = c;
        setTitle( c.getString( R.string.thumbnails_size ) );
        LayoutInflater factory = LayoutInflater.from( c );
        setView( factory.inflate( R.layout.thmbsz, null ) );
        setButton( BUTTON_POSITIVE, c.getString( R.string.dialog_ok ),     this );
        setButton( BUTTON_NEGATIVE, c.getString( R.string.dialog_cancel ), this );
    }
    
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( context );
        try {
            size = Integer.parseInt( sp.getString( "thumbnails_size", "200" ) );
        } catch( NumberFormatException e ) {
            size = 200;
        }
        SeekBar seekBar = findViewById( R.id.sz_seek );
        if( seekBar != null ) {
            seekBar.setOnSeekBarChangeListener( this );
            seekBar.setProgress( size );
        }
        vv = findViewById( R.id.sz_value );
        vv.setText( String.format( "%d%%", size ) );
    }

    // SeekBar.OnSeekBarChangeListener methods
    @Override 
    public void onProgressChanged( SeekBar seekBar, int progress, boolean fromUser ) {
        if( !fromUser ) return;
        size = progress;
        vv.setText( String.format( "%d%%", size ) );
    }

    @Override
    public void onStartTrackingTouch( SeekBar seekBar ) {
    }

    @Override
    public void onStopTrackingTouch( SeekBar seekBar ) {
    }
    
    @Override // DialogInterface.OnClickListener
    public void onClick( DialogInterface dialog, int which ) {
        if( which == BUTTON_POSITIVE ) {
            SharedPreferences.Editor sp_edit = PreferenceManager.getDefaultSharedPreferences( context ).edit();
            sp_edit.putString( "thumbnails_size", String.valueOf( size ) );
            sp_edit.apply();
        }
        dismiss();
    }
}
