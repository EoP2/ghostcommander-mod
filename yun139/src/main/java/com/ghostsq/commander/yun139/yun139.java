package com.ghostsq.commander.yun139;

import android.content.Context;

import com.ghostsq.commander.adapters.CommanderAdapter;

/**
 * Entry point GhostCommander's plugin loader (CA.java / CreateExternalAdapter) looks for:
 * a class named exactly like the URI scheme, in a package that contains that scheme name,
 * exposing a static createInstance(Context) factory. See the webdav plugin's own
 * com.ghostsq.commander.https.https class for the (nearly identical) reference.
 */
public class yun139 {
    public final static CommanderAdapter createInstance(Context ctx) {
        return new Yun139Adapter(ctx);
    }
}
