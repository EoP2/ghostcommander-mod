package com.ghostsq.commander.smb;

import android.content.Context;
import android.util.Log;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Replacer;
import com.ghostsq.commander.utils.Utils;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

class MultRenEngine extends Engine {
    private final Context ctx;
    private final CIFSContext cifs_context;
    private final String pattern_str;
    private final String replace_to;
    private final SMBReplacer r;

    public MultRenEngine( Context ctx_, CIFSContext cifs_context, SmbFile[] list, String pattern_str, String replace_to ) {
        ctx = ctx_;
        this.cifs_context = cifs_context;
        this.pattern_str = pattern_str;
        this.replace_to = replace_to;
        this.r = new SMBReplacer( list );
    }
    @Override
    public void run() {
        try {
            r.replace( pattern_str, replace_to );
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.rename_err.r() ) + "\n - " + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        }
    }

    class SMBReplacer extends Replacer {
        public  String last_file_name = null;
        private SmbFile[] origList;
        SMBReplacer( SmbFile[] origList ) {
            this.origList = origList;
        }
        @Override
        protected int getNumberOfOriginalStrings() {
            return origList.length;
        }
        @Override
        protected String getOriginalString( int i ) {
            return origList[i].getName();
        }
        @Override
        protected void setReplacedString( int i, String replaced ) {
            try {
                SmbFile f = origList[i];
                String new_path = f.getParent() + replaced;
                f.renameTo( new SmbFile( new_path, cifs_context ) );
            } catch( Exception e ) {
                Log.e( TAG, "Can't rename item " + i + " to " + replaced );
            }
        }
    }
}
