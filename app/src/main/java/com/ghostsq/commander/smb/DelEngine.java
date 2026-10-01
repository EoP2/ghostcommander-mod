package com.ghostsq.commander.smb;

import android.content.Context;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import jcifs.smb.SmbFile;

class DelEngine extends Engine {
    private SmbFile[] mList;
    private int so_far = 0;
    private Context ctx; 
    public DelEngine( Context ctx_, SmbFile[] list ) {
        ctx = ctx_;
        mList = list;
    }
    @Override
    public void run() {
        try {
            int total;
            total = deleteFiles( mList );
            sendResult( total > 0 ? "Deleted files/folders: " + total : "Nothing was deleted" );
            super.run();
        } catch( Exception e ) {
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        }
    }
    private final int deleteFiles( SmbFile[] l ) throws Exception {
        if( l == null ) return 0;
        int cnt = 0;
        int num = l.length;
        double conv = 100./(double)num;
        for( int i = 0; i < num; i++ ) {
            if( stop || isInterrupted() )
                throw new Exception( ctx.getString( Utils.RR.interrupted.r() ) );
            SmbFile f = l[i];
            sendProgress( ctx.getString( Utils.RR.deleting.r(), f.getName() ), so_far, (int)(i * conv) );
            if( f.isDirectory() )
                cnt += deleteFiles( f.listFiles() );
            f.delete();
            cnt++;
        }
        so_far += cnt; 
        return cnt;
    }
}
