package com.ghostsq.commander;


import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

public class TextEditor extends EditText {
    public interface onSelectionChangedListener {
         public void onSelectionChanged( int selStart, int selEnd );
    }

    private List<onSelectionChangedListener> listeners;

    public TextEditor( Context context) {
        super( context );
    }

    public TextEditor( Context context, AttributeSet attrs ) {
        super( context, attrs );
    }

    public TextEditor(Context context, AttributeSet attrs, int defStyleAttr) {
        super( context, attrs, defStyleAttr );
    }

    public void addOnSelectionChangedListener( onSelectionChangedListener listener ) {
        if( listeners == null )
            listeners = new ArrayList<onSelectionChangedListener>();
        listeners.add( listener );
    }

    @Override
    protected void onSelectionChanged( int selStart, int selEnd ) {
        super.onSelectionChanged( selStart, selEnd );
        if( listeners == null ) return;
        for( onSelectionChangedListener l : listeners )
            l.onSelectionChanged( selStart, selEnd );
    }
}
