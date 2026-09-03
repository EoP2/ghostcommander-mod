package com.ghostsq.commander;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import com.ghostsq.commander.toolbuttons.ToolButtonsProps;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


public class Prefs extends PreferenceActivity implements Preference.OnPreferenceClickListener,
                                                         RGBPickerDialog.ResultSink,
                                                         SelZoneDialog.ResultSink, 
                                                         OnPreferenceChangeListener {
    private static final String TAG = "GhostCommander.Prefs";
    public static final String COLORS_PREFS = "colors";
    public static final String SEL_ZONE = "selection_zone";
    //    private SharedPreferences color_pref = null;
    private ColorsKeeper ck;
    private static boolean doNotStore = false;
    private String pref_key = null;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( this );
            Utils.setTheme( this, ColorsKeeper.getTheme( this ) );
            Utils.changeLanguage( this );
            boolean ab = Utils.setActionBar( this, false, true );
            super.onCreate( savedInstanceState );
            ck = new ColorsKeeper( this );

            if( !sp.getBoolean( PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, false ) ) {
                SharedPreferences.Editor ed = sp.edit();
                ed.putBoolean( "open_content", android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.M );
                ed.putBoolean( PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, true );
                ed.putString( "color_themes", ColorsKeeper.systemSupportsThemeSwitching() ? "a" : "d" );
                try {
                    ed.apply();
                } catch( AbstractMethodError unused ) {
                    ed.commit();
                }
            }

            // Load the preferences from an XML resource
            addPreferencesFromResource( R.xml.prefs );

            findPreference( COLORS_PREFS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.FGR_COLORS ).setIntent( new Intent( Intent.ACTION_MAIN ).setClass( this, FileTypes.class ) );
            findPreference( ColorsKeeper.BGR_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.SEL_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.SFG_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.UFG_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.CUR_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.TTL_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.DIV_COLORS ).setOnPreferenceClickListener( this );
            findPreference( ColorsKeeper.BTN_COLORS ).setOnPreferenceClickListener( this );
            findPreference( "color_themes" ).setOnPreferenceChangeListener( this );
            findPreference( "language" ).setOnPreferenceChangeListener( this );
            findPreference( "toolbar_preference" ).setOnPreferenceClickListener( this );
            findPreference( SEL_ZONE ).setOnPreferenceClickListener( this );
            findPreference( "text_font_size" ).setTitle( getString( R.string.textvw_label ) + "/" + getString( R.string.editor_label ) );
            findPreference( "thumbnails_size" ).setOnPreferenceClickListener( this );


            Preference system_notifications = (Preference)findPreference( "system_notifications" );
            if( system_notifications != null ) {
                if( android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.O ) {
                    PreferenceCategory cat_adv = (PreferenceCategory)findPreference("category_advanced");
                    if( cat_adv != null )
                        cat_adv.removePreference( system_notifications );
                } else {
                    system_notifications.setIntent( ForwardCompat.getAppNotificationsIntent( this ) );
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ck.restore();
        ck.restoreTypeColors();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setColorsThumb();
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            if( !doNotStore )
                ck.store();
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    @Override
    public boolean onPreferenceChange( Preference p, Object v ) {
        if( "language".equals( p.getKey() ) ) {
            recreate();
        } else
        if( "color_themes".equals( p.getKey() ) ) {
            if( !( v instanceof String ) ) return false;
            String new_theme = (String)v;
            doNotStore = "a".equals( new_theme );
            if( doNotStore ) {
                new_theme = ColorsKeeper.getSystemTheme( this );
                ck.erase();
            }
            recreate();
            ck.setColorsByTheme( new_theme );
            Utils.setTheme( this, new_theme );
        }
        return true;
    }

    @Override
    public boolean onPreferenceClick( Preference preference ) {
        try {
            pref_key = preference.getKey();
            if( "toolbar_preference".equals( pref_key ) ) {
                Intent intent = new Intent( Intent.ACTION_MAIN );
                intent.setClass( this, ToolButtonsProps.class );
                startActivity( intent );
            } else if( SEL_ZONE.equals( pref_key ) ) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( this );
                new SelZoneDialog( this, this, sp.getBoolean( SEL_ZONE + "_right", false ),
                        sp.getInt( SEL_ZONE + "_width", 20 ),
                        ck.selColor, ck.bgrColor, sp.getBoolean( SEL_ZONE + "_ht", false ) ).show();
            } else if( "thumbnails_size".equals( pref_key ) ) {
                new ThumbSizeDialog( this ).show();
            } else if( COLORS_PREFS.equals( pref_key ) ) {
                setColorsThumb();
            } else {
                String title = preference.getTitle().toString();
                new RGBPickerDialog( this, this, ck.getColor( pref_key ),
                        getDefaultColor( pref_key ), title ).show();
            }
            return true;
        } catch( Exception e ) {
            Log.e( TAG, preference.toString(), e );
        }
        return false;
    }

    public final int getDefaultColor( String key ) {
        return ColorsKeeper.canBeDefault( key ) ? ColorsKeeper.getUndefaultColor( this, key ) : 0;
    }

    private void setColorsThumb() {
        try {
            Log.d( TAG, "Setting colors thumbnails..." );
            findPreference( ColorsKeeper.BGR_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.BGR_COLORS ) );
            findPreference( ColorsKeeper.SEL_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.SEL_COLORS ) );
            findPreference( ColorsKeeper.SFG_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.SFG_COLORS ) );
            findPreference( ColorsKeeper.UFG_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.UFG_COLORS ) );
            findPreference( ColorsKeeper.CUR_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.CUR_COLORS ) );
            findPreference( ColorsKeeper.TTL_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.TTL_COLORS ) );
            findPreference( ColorsKeeper.DIV_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.DIV_COLORS ) );
            findPreference( ColorsKeeper.BTN_COLORS ).getIcon().setTint( ck.getColor( ColorsKeeper.BTN_COLORS ) );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public void selZoneChanged( boolean atRight, int width, boolean highlight ) {
        SharedPreferences.Editor sp_edit = PreferenceManager.getDefaultSharedPreferences( this ).edit();
        sp_edit.putBoolean( SEL_ZONE + "_right", atRight );
        sp_edit.putInt( SEL_ZONE + "_width", width );
        sp_edit.putBoolean( SEL_ZONE + "_ht", highlight );
        sp_edit.commit();
    }

    @Override
    public void colorChanged( int color ) {
        if( pref_key != null ) {
            doNotStore = false;
            ck.setColor( pref_key, color );
            Preference cpp = (Preference)findPreference( pref_key );
            if( cpp != null )
                cpp.getIcon().setTint( color );
            pref_key = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        try {
            Utils.changeLanguage( this );
            // Inflate the currently selected menu XML resource.
            MenuInflater inflater = getMenuInflater();
            inflater.inflate( R.menu.pref_menu, menu );
            return true;
        } catch( Throwable e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    @Override
    public boolean onMenuItemSelected( int featureId, MenuItem item ) {
        super.onMenuItemSelected( featureId, item );
        try {
            File save_dir = new File( Panels.DEFAULT_LOC, ".GhostCommander" );
            File f = new File( save_dir, "gc_prefs.zip" );
            File sp_dir = getPrefsDirFile( this );
            final int id = item.getItemId();
                if( id == R.id.save_prefs ) {
                    if( save_dir.exists() || save_dir.mkdirs() )
                        savePrefs( sp_dir, f );
                    return true;
                }
                if( id == R.id.rest_prefs ) {
                    if( !f.exists() ) {
                        Toast.makeText( this, getString( R.string.not_accs, f.getAbsolutePath() ), Toast.LENGTH_LONG ).show();
                        return false;
                    }
                    restPrefs( f, sp_dir );
                    ck.restore();
                    setResult( RESULT_OK, new Intent( FileCommander.PREF_RESTORE_ACTION ) );
                    finish();
                    return true;
                }
                if( id == R.id.save_log ) {
                    if( !save_dir.exists() && !save_dir.mkdirs() ) {
                        Toast.makeText( this, getString( R.string.fail ), Toast.LENGTH_LONG ).show();
                        return true;
                    }
                    final String f_save_dir = save_dir.getAbsolutePath();
                    final Context ctx = this;
                    new Thread( new Runnable() {
                        @Override
                        public void run() {
                            String fn = f_save_dir + "/logcat.txt";
                            try {
                                File f = new File( fn );
                                if( f.exists() ) f.delete();
                                Process prc = Runtime.getRuntime().exec( "logcat -d -v time -f " + fn + " *:v\n" );
                                Toast.makeText( ctx, prc.waitFor() == 0 ? ctx.getString( R.string.saved, fn ) :
                                        ctx.getString( R.string.fail ), Toast.LENGTH_LONG ).show();
                            } catch( Exception e ) {
                                Log.e( TAG, fn, e );
                            }
                        }
                    } ).run();
                    return true;
                }
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return true;
    }

    public final void showMessage( String s ) {
        Toast.makeText( this, s, Toast.LENGTH_LONG ).show();
    }

    public static File getPrefsDirFile( Context ctx ) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo( ctx.getPackageName(), 0 );
            return new File( ai.dataDir, "shared_prefs" );
        } catch( PackageManager.NameNotFoundException e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    private final void savePrefs( File sp_dir, File f ) {
        try {
            if( f.exists() ) {
                File pf = f.getParentFile();
                String fn = f.getName();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                fn = fn.substring( 0, fn.length() - 4 ) + "_" + sdf.format( new Date( f.lastModified() ) ) + ".zip";
                f.renameTo( new File( pf, fn ) );
            }
            ZipOutputStream zos = new ZipOutputStream( new FileOutputStream( f ) );
            File[] sp_files = sp_dir.listFiles();
            for( int i = 0; i < sp_files.length; i++ ) {
                InputStream is = null; 
                try {
                    is = new FileInputStream( sp_files[i] );
                } catch( Exception e ) {}
                if( is != null ) {
                    zos.putNextEntry( new ZipEntry( sp_files[i].getName() ) );
                    Utils.copyBytes( is, zos );
                    is.close();
                    zos.closeEntry();
                }
            }
            zos.close();
            showMessage( getString( R.string.prefs_saved ) + "\n" + f.getAbsolutePath() );
        } catch( Throwable e ) {
            Log.e( TAG, f != null ? f.toString() : null, e );
        }
    }
    private void restPrefs( File f, File sp_dir ) {
        try {
            //Favorites.clearPrefs( this );
            File[] ff = sp_dir.listFiles();
            for( File of : ff )
                of.delete();
            ZipFile zf = new ZipFile( f );
            Enumeration<? extends ZipEntry> z_entries = zf.entries();
            while( z_entries.hasMoreElements() ) {
                ZipEntry ze = z_entries.nextElement();
                if( ze == null ) continue;
                String fn = ze.getName();
                if( fn.endsWith( "_preferences.xml" ) )
                    fn = getPackageName() + "_preferences.xml";
                InputStream is = zf.getInputStream( ze );
                OutputStream os = new FileOutputStream( new File( sp_dir, fn ) );
                Utils.copyBytes( is, os );
                is.close();
                os.close();
            }
            showMessage( getString( R.string.prefs_restr ) );
        } catch( Throwable e ) {
            Log.e( TAG, f != null ? f.toString() : null, e );
        }
    }
}