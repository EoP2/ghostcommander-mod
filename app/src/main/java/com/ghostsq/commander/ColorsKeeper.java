package com.ghostsq.commander;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import com.ghostsq.commander.utils.Utils;

import java.util.ArrayList;

public final class ColorsKeeper {
    private static final String TAG = "ColorsKeeper";
    public  static final String BGR_COLORS = "bgr_color_picker"; 
    public  static final String FGR_COLORS = "fgr_color_picker"; 
    public  static final String SEL_COLORS = "sel_color_picker"; 
    public  static final String SFG_COLORS = "sfg_color_picker"; 
    public  static final String CUR_COLORS = "cur_color_picker";
    public  static final String TTL_COLORS = "ttl_color_picker";
    public  static final String BTN_COLORS = "btn_color_picker";

    private  Context ctx;
    private  SharedPreferences   colorPref = null;
    public   int ttlColor, bgrColor, fgrColor, selColor, sfgColor, curColor, btnColor;
    private  static String theme;
    
    public class FileTypeColor {
        private static final String   TYPES_pref = "types";
        private static final String FGR_COL_pref = "fgr_color";
        public  String  masks;
        public  int     color;
        public  boolean masksDirty = false, colorDirty = false;
        public  FileTypeColor() {
            color = 0;
        }
        public  FileTypeColor( String m, int c ) {
            masks = m;
            color = c;
        }
        public final void setMasks( String m ) {
            masks = m;
            masksDirty = true;
        }
        public final void setColor( int c ) {
            color = c;
            colorDirty = true;
        }
        public final boolean restore( Context ctx, SharedPreferences pref, int i ) {
            try {
                color = colorPref.getInt(  FGR_COL_pref + i, getDefColor( ctx, i ) );
                masks = colorPref.getString( TYPES_pref + i, getDefMasks( i ) );
                return masks != null; 
            }
            catch( Exception e ) {
            }
            return false;
        }
        public final void store( SharedPreferences.Editor editor, int i ) {
            final String col_key = FGR_COL_pref + i;
            final String msk_key = TYPES_pref + i;
            if( !Utils.str( masks ) ) {
                editor.remove( col_key ).remove( msk_key );
                return;
            }
            if( colorDirty )
                editor.putInt( col_key, color );
            if( masksDirty )
                editor.putString( msk_key, masks );
        }

        public final String getDefMasks( int i ) {
            String cat = null;
            switch( i ) {
            case 1: return "/*;*/";     // directories
            case 2:    // "*.gif;*.jpg;*.png;*.bmp";
                cat = Utils.C_IMAGE;
                break;
            case 3: // "*.avi;*.mov;*.mp4;*.mpeg";
                cat = Utils.C_VIDEO;
                break;
            case 4: // "*.mp3;*.wav;*.mid*";
                cat = Utils.C_AUDIO;
                break;
            case 5: return "*.htm*;*.xml;*.pdf;*.csv;*.doc*;*.xls*";
            case 6: return "*.apk;*.zip;*.jar;*.rar;*.7z;*.tar;*.gz";
            }
            if( cat != null ) {
                String[] exts = Utils.getExtsByCategory( cat );
                if( exts != null ) {
                    StringBuffer ret_buf = new StringBuffer();
                    boolean fst = true;
                    for( int k = 0; k < exts.length; k++ ) {
                        if( !fst )
                            ret_buf.append( ";" );
                        ret_buf.append( "*" );
                        ret_buf.append( exts[k] );
                        fst = false;
                    }
                    return ret_buf.toString();
                }
            }            
            return null;
        }
        public int getDefColor( Context ctx, int i ) {
            Resources r = ctx.getResources();
            if( ColorsKeeper.theme == null )
                ColorsKeeper.getTheme( ctx );
            if( "l".equals( ColorsKeeper.theme ) ) {
                switch( i ) {
                case 1: return r.getColor( R.color.fg1_lgt );
                case 2: return r.getColor( R.color.fg2_lgt );
                case 3: return r.getColor( R.color.fg3_lgt );
                case 4: return r.getColor( R.color.fg4_lgt );
                case 5: return r.getColor( R.color.fg5_lgt );
                case 6: return r.getColor( R.color.fg6_lgt );
                default:return r.getColor( R.color.fgr_lgt );
                }
            }
            switch( i ) {
            case 1: return r.getColor( R.color.fg1_drk );
            case 2: return r.getColor( R.color.fg2_drk );
            case 3: return r.getColor( R.color.fg3_drk );
            case 4: return r.getColor( R.color.fg4_drk );
            case 5: return r.getColor( R.color.fg5_drk );
            case 6: return r.getColor( R.color.fg6_drk );
            default:
                    return "n".equals( ColorsKeeper.theme ) ?
                           r.getColor( R.color.fgr_nrt ) :
                           r.getColor( R.color.fgr_drk );
            }
        }
    }
    
    public  ArrayList<FileTypeColor>  ftColors;
    
    public ColorsKeeper( Context ctx_ ) {
        ctx = ctx_;
        setColorsByTheme( getTheme( ctx ) );
    }

    public final static String getTheme( Context ctx ) {
        Resources  r = ctx.getResources();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences( ctx );
        String def_theme = systemSupportsThemeSwitching() ? "a" : "d";
        theme = sp.getString( "color_themes", def_theme );
        if( theme == null || "a".equals( theme ) ) {
            theme = getSystemTheme( ctx );
        }
        return theme;
    }

    public final static boolean systemSupportsThemeSwitching() {
        return android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
             ( android.os.Build.HOST != null && android.os.Build.HOST.indexOf( "lineageos" ) >= 0 );
    }

    public final static String getSystemTheme( Context ctx ) {
        Resources  r = ctx.getResources();
        Configuration conf = r.getConfiguration();
        return (conf.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO ? "l" : "d";
    }

    public static int getDefaultColor( Context ctx, String key ) {
        Resources r = ctx.getResources();
        return 0;
    }

    /**
     * @param ctx   context
     * @param key   color in question
     * @return  color to be used instead of "system default"
     */
    public static int getUndefaultColor( Context ctx, String key ) {
        if( theme == null )
            theme = getTheme( ctx );
        Resources r = ctx.getResources();
        if( "d".equals( theme ) ) {
            if( key.equals( ColorsKeeper.CUR_COLORS ) ) return r.getColor( R.color.cur_drk );
            if( key.equals( ColorsKeeper.BTN_COLORS ) ) return r.getColor( R.color.btn_drk );
        }
        if( "n".equals( theme ) ) {
            if( key.equals( ColorsKeeper.CUR_COLORS ) ) return r.getColor( R.color.cur_nrt );
            if( key.equals( ColorsKeeper.BTN_COLORS ) ) return r.getColor( R.color.btn_nrt );
        }
        if( "l".equals( theme ) ) {
            if( key.equals( ColorsKeeper.CUR_COLORS ) ) return r.getColor( R.color.cur_lgt );
            if( key.equals( ColorsKeeper.BTN_COLORS ) ) return r.getColor( R.color.btn_lgt );
        }
        return 0;
    }

    public static boolean canBeDefault( String key ) {
        if( key == null ) return false;
        return key.equals( ColorsKeeper.CUR_COLORS ) ||
               key.equals( ColorsKeeper.BTN_COLORS );
    }

    public final boolean isButtonsDefault() {
        return btnColor == 0;
    }

    public final boolean isFocusDefault() {
        return curColor == 0;
    }

    public final void setColorsByTheme( String t ) {
        theme = t;
        Resources  r = ctx.getResources();
        if( "d".equals( t ) ) {
            ttlColor = r.getColor( R.color.ttl_drk );
            bgrColor = r.getColor( R.color.bgr_drk );
            fgrColor = r.getColor( R.color.fgr_drk );
            curColor = 0;//r.getColor( R.color.cur_drk );
            selColor = r.getColor( R.color.sel_drk );
            sfgColor = r.getColor( R.color.sfg_drk );
            btnColor = r.getColor( R.color.btn_drk );
        }
        if( "n".equals( t ) ) {
            ttlColor = r.getColor( R.color.ttl_nrt ); 
            bgrColor = r.getColor( R.color.bgr_nrt );
            fgrColor = r.getColor( R.color.fgr_nrt );
            curColor = r.getColor( R.color.cur_nrt );
            selColor = r.getColor( R.color.sel_nrt );
            sfgColor = r.getColor( R.color.sfg_nrt );
            btnColor = r.getColor( R.color.btn_nrt );
        }
        if( "l".equals( t ) ) {
            ttlColor = r.getColor( R.color.ttl_lgt ); 
            bgrColor = r.getColor( R.color.bgr_lgt );
            fgrColor = r.getColor( R.color.fgr_lgt );
            curColor = 0;//r.getColor( R.color.cur_lgt );
            selColor = r.getColor( R.color.sel_lgt );
            sfgColor = r.getColor( R.color.sfg_lgt );
            btnColor = r.getColor( R.color.btn_lgt );
        }
        if( ftColors == null ) restoreTypeColors();
        int n = Math.min( ftColors.size(), 6 );
        for( int i = 0; i < n; i++ ) {
            FileTypeColor ftc = ftColors.get( i );
            ftc.setColor( ftc.getDefColor( ctx, i+1 ) );
        }
    }

    public final int getColor( String key ) {
        if( BGR_COLORS.equals( key ) ) return bgrColor;
        if( FGR_COLORS.equals( key ) ) return fgrColor;
        if( SEL_COLORS.equals( key ) ) return selColor;
        if( SFG_COLORS.equals( key ) ) return sfgColor;
        if( CUR_COLORS.equals( key ) ) return curColor;
        if( TTL_COLORS.equals( key ) ) return ttlColor;
        if( BTN_COLORS.equals( key ) ) return btnColor;
        return 0;
    }

    public final void setColor( String key, int c ) {
        if( BGR_COLORS.equals( key ) ) bgrColor = c;
        if( FGR_COLORS.equals( key ) ) fgrColor = c;
        if( SEL_COLORS.equals( key ) ) selColor = c;
        if( SFG_COLORS.equals( key ) ) sfgColor = c;
        if( CUR_COLORS.equals( key ) ) curColor = c;
        if( TTL_COLORS.equals( key ) ) ttlColor = c;
        if( BTN_COLORS.equals( key ) ) btnColor = c;
    }

    public final void erase() {
        colorPref = ctx.getSharedPreferences( Prefs.COLORS_PREFS, Activity.MODE_PRIVATE );
        SharedPreferences.Editor editor = colorPref.edit();
        editor.clear();
        editor.apply();
    }

    public final void store() {
        colorPref = ctx.getSharedPreferences( Prefs.COLORS_PREFS, Activity.MODE_PRIVATE );
        SharedPreferences.Editor editor = colorPref.edit();
        editor.putInt( BGR_COLORS, bgrColor );
        editor.putInt( FGR_COLORS, fgrColor );
        editor.putInt( CUR_COLORS, curColor );
        editor.putInt( SEL_COLORS, selColor );
        editor.putInt( SFG_COLORS, sfgColor );
        editor.putInt( TTL_COLORS, ttlColor );
        editor.putInt( BTN_COLORS, btnColor );
        if( ftColors != null )
            storeTypeColors( editor );
        editor.commit();
    }

    public final void restore() {
        colorPref = ctx.getSharedPreferences( Prefs.COLORS_PREFS, Activity.MODE_PRIVATE );
        bgrColor = colorPref.getInt( BGR_COLORS, bgrColor );
        fgrColor = colorPref.getInt( FGR_COLORS, fgrColor );
        curColor = colorPref.getInt( CUR_COLORS, curColor );
        selColor = colorPref.getInt( SEL_COLORS, selColor );
        sfgColor = colorPref.getInt( SFG_COLORS, sfgColor );
        ttlColor = colorPref.getInt( TTL_COLORS, ttlColor );
        btnColor = colorPref.getInt( BTN_COLORS, btnColor );
    }

    public final void storeTypeColors() {
        colorPref = ctx.getSharedPreferences( Prefs.COLORS_PREFS, Activity.MODE_PRIVATE );
        SharedPreferences.Editor editor = colorPref.edit();
        storeTypeColors( editor );
        editor.commit();
    }
    
    public final void storeTypeColors( SharedPreferences.Editor editor ) {
        for( int i = 1; i <= ftColors.size(); i++ ) {
            FileTypeColor ftc = ftColors.get( i - 1 );
            ftc.store( editor, i );
        }
    }
    public final int restoreTypeColors() {
        try {
            colorPref = ctx.getSharedPreferences( Prefs.COLORS_PREFS, Activity.MODE_PRIVATE );
            if( ftColors == null  )
                ftColors = new ArrayList<FileTypeColor>( 5 );
            else
                ftColors.clear();
            for( int i = 1; i < 999; i++ ) {
                FileTypeColor ftc = new FileTypeColor();
                if( !ftc.restore( ctx, colorPref, i ) ) break;
                ftColors.add( ftc );
            }
            return ftColors.size();
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return 0;
    }

    public final int addTypeColor() {
        ftColors.add( new FileTypeColor( "", fgrColor ) );
        return ftColors.size();        
    }
}
