package com.ghostsq.commander.https;

import android.content.Context;

import com.ghostsq.commander.adapters.CommanderAdapter;

public class http {
    public final static CommanderAdapter createInstance( Context ctx ) {
        return new WebDAVAdapter( ctx );
    }
}
