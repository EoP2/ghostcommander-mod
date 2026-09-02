package com.ghostsq.commander.toolbuttons;

import android.content.Context;
import android.content.SharedPreferences;

import com.ghostsq.commander.R;
import com.ghostsq.commander.Tools;

import java.util.ArrayList;

public class ToolButtons extends ArrayList<ToolButton> 
{
    private static final long serialVersionUID = 1L;
    private static final String pref_key = "tool_buttons"; 
    public final String TAG = getClass().getName();
    
    public final void restore( SharedPreferences shared_pref, Context context, boolean ab ) {
        String bcns = shared_pref.getString( pref_key, null );
        if( bcns != null && !bcns.isEmpty() ) {
            // add new introduced buttons here like below:
            if( !bcns.contains( "send" ) ) bcns += ",send";

            String[] bcna = bcns.split( "," );
            for( String bcn : bcna ) {
                int bi = Tools.getId( bcn );
                if( bi == 0 ) continue;
                ToolButton tb = new ToolButton( bi );
                tb.restore( shared_pref, context );
                add( tb );
            }
        }
        else {
            int[] bia = Tools.getIds();
            for( int bi : bia ) {
                if( Tools.getCodeName( bi ) == null ) continue;
                ToolButton tb = new ToolButton( bi );
                if( ab ) {
                    if( bi == R.id.action_back
                     || bi == R.id.menu
                     || bi == R.id.filter
                     || bi == R.id.search
                     || bi == R.id.compare
                     || bi == R.id.favs
                     || bi == R.id.hidden )
                        tb.setVisible( false );
                }
                tb.restore( shared_pref, context );
                add( tb );
            }
        }
    }
    public final void store( SharedPreferences.Editor editor ) {
        StringBuilder bicsb = new StringBuilder();
        for( int i = 0; i < size(); i++ ) {
            ToolButton tb = get( i );
            if( i > 0 ) bicsb.append( "," );
            bicsb.append( tb.getCodeName() );
            tb.store( editor );
        }
        editor.putString( pref_key, bicsb.toString() );
    }
}
