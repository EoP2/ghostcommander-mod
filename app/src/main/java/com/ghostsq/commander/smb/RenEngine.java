package com.ghostsq.commander.smb;

import android.content.Context;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

class RenEngine extends Engine {
    private Context ctx;
    private SmbFile old_file;
    private String  full_url; 
    private CIFSContext cifs_context;
    
    public RenEngine( Context ctx_, SmbFile old_file_, String full_url_, CIFSContext cifs_context ) {
        ctx = ctx_;
        old_file = old_file_;
        full_url = full_url_;
        this.cifs_context = cifs_context;
    }
    @Override
    public void run() {
        try {
            synchronized( old_file ) {
                old_file.renameTo( new SmbFile( full_url, cifs_context ) );
            }
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.rename_err.r(), full_url ) + "\n - " + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        }
    }
}
