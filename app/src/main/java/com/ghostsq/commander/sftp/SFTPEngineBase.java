package com.ghostsq.commander.sftp;

import android.content.Context;
import android.util.Log;

import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpProgressMonitor;

import java.util.Vector;

class SFTPEngineBase extends Engine implements SftpProgressMonitor {
    protected Context         ctx; 
    protected SFTPAdapter     adapter;
    protected ChannelSftp     sftp = null;
    protected Item[]          mList;
    
    protected SFTPEngineBase( SFTPAdapter a, Item[] list ) {
/*
        this( a, list );
    }
    protected SFTPEngineBase( SFTPAdapter a, Item[] list, long stackSize ) {
        super( stackSize );
 */
        adapter = a;
        ctx     = adapter.ctx;
        mList   = list;
    }

    protected final boolean skip( Item f ) {
        String fn = f.name;
        if(  "/.".equals( fn ) ||
            "/..".equals( fn ) ||
              ".".equals( fn ) ||
             "..".equals( fn ) ) return true;
        return false;
    }

    protected final Item[] getItems( String dir_path ) {
        try {
            if( sftp == null ) sftp = adapter.getChannel();
            if( sftp == null ) return null;
            Vector<ChannelSftp.LsEntry> dir_entries = sftp.ls( dir_path );
            if( dir_entries == null ) throw new Exception( "ls() returned null" );
            int num_entries = dir_entries.size();
            Item[] subItems = new Item[num_entries];
            if( num_entries > 0 )
                for( int j = 0; j < num_entries; j++ ) {
                    ChannelSftp.LsEntry e = dir_entries.get(j);
                    Item item = adapter.makeItem( e.getFilename(), e.getAttrs() );
                    subItems[j] = item;
                }
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, dir_path, e );
        }
        error( "No valid entries in " + dir_path );
        return null;
    }

    // --- SftpProgressMonitor implementation ---

    protected boolean operationSuccess;
    private   long    curCount, maxCount,  startTime, countSinceLastTime;
    private   double  conv = 0.;
    private   int     operationSpeed;
    private   String  operationTitle, maxStr;

    @Override
    public void init( int op, String src, String dst, long max ) {
        this.maxCount = max;
        this.curCount = 0;
        this.maxStr = Utils.getHumanSize( max, false );
        this.conv = max > 0 ? 100. / max : 0;
        this.startTime = System.currentTimeMillis();
        this.countSinceLastTime = 0;
        int op_res_id = op == PUT ? Utils.RR.uploading.r() : op == GET ? Utils.RR.retrieving.r() : Utils.RR.copy_err.r();
        int lsp = src.lastIndexOf( '/' );
        this.operationTitle = ctx.getString( op_res_id, lsp < 0 ? src : src.substring( lsp+1 ) );
        this.operationSuccess = false;
        super.sendProgress( operationTitle + sizeOfsize( 0, maxStr ), progress, 0, 0 );
    }

    @Override
    public boolean count( long chunk ) {
        curCount += chunk;
        if( curCount == maxCount )
            operationSuccess = true;
        long time_delta = System.currentTimeMillis() - startTime;
        if( operationSuccess || time_delta > DELAY ) {
            operationSpeed = (int)( MILLI * ( curCount - countSinceLastTime ) / time_delta );
            countSinceLastTime = curCount;
            startTime = System.currentTimeMillis();
            super.sendProgress( operationTitle + sizeOfsize( curCount, maxStr ), progress, (int)(curCount * conv), operationSpeed );
        }
        return !(stop || isInterrupted());
    }

    @Override
    public void end() {
        this.maxCount = 0;
        this.maxStr = null;
        this.operationTitle = null;
        this.curCount = 0;
        this.conv = 0;
        this.startTime = 0;
        this.countSinceLastTime = 0;
    }

    // --- Object override ---

    @Override
    public void finalize() {
        if( sftp != null && !sftp.isConnected() )
            sftp.disconnect();
        sftp = null;
    }

}
