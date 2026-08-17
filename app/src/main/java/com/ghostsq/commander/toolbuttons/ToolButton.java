package com.ghostsq.commander.toolbuttons;

//import com.ghostsq.toolbuttons.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.ghostsq.commander.R;
import com.ghostsq.commander.Tools;

public class ToolButton {
    private final static String TAG = "ToolButton";
    private final int id;
    private final int def_caption_r_id;
    private final String codename;
    private String caption, icon;
    private boolean visible;
    private boolean modified, reverted;

    ToolButton( int id_ ) {
        id = id_;
        codename = Tools.getCodeName( id_ );
        def_caption_r_id = Tools.getCaptionRId( id_ );
        caption = null;
        modified = false;
        visible = Tools.getVisibleDefault( id_ );
        icon = Tools.getIcon( id_ );
    }
    public final int getId() {
        return id;
    }
    public final int getDefCaptionResId() {
        return def_caption_r_id;
    }
    final String getName( Context c ) {
        int id = def_caption_r_id;
        if( id == 0 ) return "";
        if( def_caption_r_id == R.string.F8 ) id = R.string.delete_title;    else
        if( def_caption_r_id == R.string.F9 ) id = R.string.prefs_label;  else
        if( def_caption_r_id == R.string.sz ) id = R.string.info;
        return c.getString( id );
    }
    private final String getVisiblePropertyName() {
        return "show_" + codename; 
    }
    private final String getCaptionPropertyName() {
        return "caption_" + codename; 
    }
    public final void restore( SharedPreferences shared_pref, Context context ) {
        try {
            visible = shared_pref.getBoolean( getVisiblePropertyName(), visible );
            caption = shared_pref.getString( getCaptionPropertyName(), context.getString( def_caption_r_id ) );
        } catch( Exception e ) {
            Log.d( TAG, codename, e );
        }
    }
    public final void store( SharedPreferences.Editor editor ) {
        editor.putBoolean( getVisiblePropertyName(), visible );
        if( modified )
            editor.putString( getCaptionPropertyName(), caption );
        if( reverted )
            editor.remove( getCaptionPropertyName() );
    }
    
    public final String getCaption() {
        return caption;
    }
    final void setCaption( String caption_ ) {
        if( !caption.equals( caption_ ) ) {
            modified = true;
            caption = caption_;
        }
    }
    final void revertCaption() {
        reverted = true;
    }
    public final boolean isVisible() {
        return visible;
    }
    public final void setVisible( boolean v ) {
        visible = v;
    }
    public final String getCodeName() {
        return codename;
    }
    public final String getIcon() {
        return icon;
    }
    public final char getBoundKey() {
        return Tools.getBoundKey( id );
    }
}
