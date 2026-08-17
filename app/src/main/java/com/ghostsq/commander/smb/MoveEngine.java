package com.ghostsq.commander.smb;

import android.content.Context;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

import jcifs.smb.SmbFile;

class MoveEngine extends Engine {
    private SmbFile[] mList;
    private SmbFile dest;
    private int so_far = 0;
    private Context ctx; 
    public MoveEngine( Context ctx_, SmbFile[] list, SmbFile dest_ ) {
        ctx = ctx_;
        mList = list;
        dest = dest_;
    }
    @Override
    public void run() {
        try {
            int total;
            total = moveFiles( mList, dest );
            sendResult( total > 0 ? "Deleted files/folders: " + total : "Nothing was deleted" );
            super.run();
        } catch( Exception e ) {
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        }
    }
    private final int moveFiles( SmbFile[] l, SmbFile df ) throws Exception {
        if( l == null ) return 0;
        int cnt = 0;
        int num = l.length;
        double conv = 100./(double)num;
        for( int i = 0; i < num; i++ ) {
            if( stop || isInterrupted() )
                throw new Exception( ctx.getString( Utils.RR.interrupted.r() ) );
            SmbFile f = l[i];
            SmbFile dst_f = new SmbFile( df, f.getName() );
            if( f.isDirectory() ) {
                if( !dst_f.exists() )
                    dst_f.mkdir();
                cnt += moveFiles( f.listFiles(), dst_f );
                SmbFile[] after_list = f.listFiles();
                if( after_list.length == 0 )
                    f.delete();
            } else 
                f.renameTo( dst_f );
            sendProgress( f.getName() + " " + ctx.getString( Utils.RR.moved.r() ), so_far, (int)(i * conv) );
            cnt++;
        }
        so_far += cnt; 
        return cnt;
    }
}
