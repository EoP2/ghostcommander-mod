package com.ghostsq.commander.https;

import android.util.Log;

import shaded.org.apache.http.conn.ssl.DefaultHostnameVerifier;

import java.security.cert.Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

class AllowSelfSignedHostnameVerifier implements HostnameVerifier {
    private final static String TAG = AllowSelfSignedHostnameVerifier.class.getSimpleName();

    DefaultHostnameVerifier dhnv = new DefaultHostnameVerifier();

    @Override
    public boolean verify( String hostname, SSLSession session ) {
        try {
            final Certificate[] certs = session.getPeerCertificates();
            if( certs.length == 1 )
                return true;
        } catch( SSLPeerUnverifiedException e ) {
            Log.w( TAG, hostname, e );
        }
        return dhnv.verify( hostname, session );
    }
}
