package com.ghostsq.commander.https;

import android.util.Log;

import com.ghostsq.commander.adapters.Engine;

import shaded.org.apache.http.impl.client.CloseableHttpClient;


class WebDAVEngineBase extends Engine {
    protected WebDAVAdapter       owner;
    protected CloseableHttpClient client;
    
    protected WebDAVEngineBase( WebDAVAdapter owner ) {
        this.owner = owner;
        setName( TAG );
    }

    public void setClient( CloseableHttpClient client ) {
        this.client = client;
    }

    public CloseableHttpClient getClient() {
        if( client == null )
            client = owner.getClient( owner.getUri().getHost() );
        return client;
    }
    public CloseableHttpClient createClient() {
        client = owner.createClient( owner.getUri().getHost() );
        return client;
    }
    @Override
    public void finalize() {
        try {
            super.finalize();
            /*
            if( client != null ) {
                client.close();
                client = null;
            }
             */
        } catch( Throwable e ) {
            Log.e( TAG, "", e );
        }
    }
}
