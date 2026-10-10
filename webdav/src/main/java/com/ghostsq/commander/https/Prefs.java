package com.ghostsq.commander.https;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;

import java.util.Locale;

public class Prefs extends PreferenceActivity
{
    private static final String TAG = "WebDAV.Prefs"; 
    
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
            // Load the preferences from an XML resource
            addPreferencesFromResource( R.xml.prefs );
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
}