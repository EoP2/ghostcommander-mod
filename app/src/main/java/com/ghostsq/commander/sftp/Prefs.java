package com.ghostsq.commander.sftp;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.util.Log;

import com.ghostsq.commander.R;

import java.util.Locale;

public class Prefs extends PreferenceActivity
                   implements Preference.OnPreferenceClickListener
{
    private static final String TAG = "SFTP.Prefs";
    
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        try {
            Intent in = getIntent();
            if( in != null ) {
                String th = in.getStringExtra( "theme" );
                if( th != null ) {
                    setTheme( "l".equals( th ) ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar );
                }
                String lang = in.getStringExtra( "language" );
                if( lang != null && lang.length() > 0 ) {
                    String country = lang.length() > 3 ? lang.substring( 3 ) : null;
                    Locale locale;
                    if( country != null )
                        locale = new Locale( lang.substring( 0, 2 ), country );
                    else
                        locale = new Locale( lang );
                    Locale.setDefault( locale );
                    Configuration config = new Configuration();
                    config.locale = locale;
                    getResources().updateConfiguration( config, null );
                }
            }
            super.onCreate( savedInstanceState );
            addPreferencesFromResource( R.xml.sftp_prefs );

            Preference tool_buttons_pref = (Preference)findPreference( "keys_manager" );
            if( tool_buttons_pref != null )
                tool_buttons_pref.setOnPreferenceClickListener( this );
        }
        catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }
    @Override
    protected void onStart() {
        super.onStart();
    }
    @Override
    protected void onPause() {
        try {
            super.onPause();
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }
    @Override
    public boolean onPreferenceClick( Preference preference ) {
        try {
            String pref_key = preference.getKey();
            if( "keys_manager".equals( pref_key ) ) {
                Intent intent = new Intent( Intent.ACTION_MAIN );
                intent.setClass( this, KeysActivity.class );
                intent.putExtra( "theme", getIntent().getStringExtra( "theme" ) );
                startActivity( intent );
            }
            return true;
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return false;
    }

}
