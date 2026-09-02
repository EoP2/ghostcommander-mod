package com.ghostsq.commander.https;
import android.content.Context;
import com.ghostsq.commander.adapters.CommanderAdapter;

public class https {
    public final static CommanderAdapter createInstance( Context ctx ) {
        return new com.ghostsq.commander.https.WebDAVAdapter( ctx );
    }
}
