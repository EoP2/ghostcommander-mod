package com.ghostsq.commander.smb;

import android.content.Context;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

class MkDirEngine extends Engine {
    private Context ctx; 
    private String full_url; 
    private CIFSContext cifs_context;
    public MkDirEngine( Context ctx_, String full_url_, CIFSContext cifs_context ) {
        ctx = ctx_;
        full_url = full_url_;
        this.cifs_context = cifs_context;
    }
    @Override
    public void run() {
        SmbFile new_folder;
        try {
            new_folder = new SmbFile( full_url, cifs_context );
            new_folder.mkdir();
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.cant_md.r(), full_url ) + "\n - " + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        }
    }
}
