package com.ghostsq.commander.sftp;

import android.content.Context;
import android.os.Build;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.adapters.CommanderAdapter;

import java.io.File;

public class sftp {
    public final static CommanderAdapter createInstance( Context ctx ) {
        return new SFTPAdapter( ctx );
    }
    public final static String getPackageName() {
        return BuildConfig.APPLICATION_ID;
    }

    public static File getDir( Context ctx ) {
        return getDir( ctx, false );
    }

    public static File getDir( Context ctx, boolean for_plugin ) {
        File f_base_dir = android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ?
                ctx.getNoBackupFilesDir() : ctx.getFilesDir();
        if( for_plugin ) {
            String s_base_dir = f_base_dir.getAbsolutePath();
            s_base_dir = s_base_dir.replace( ctx.getPackageName(), "com.ghostsq.commander.sftp" );
            f_base_dir = new File( s_base_dir );
        } else if( !f_base_dir.exists() )
            f_base_dir.mkdirs();
        return f_base_dir;
    }

}
