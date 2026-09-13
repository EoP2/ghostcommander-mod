package com.ghostsq.commander.sftp;

import android.util.Log;

import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.File;

class UserProxy implements UserInfo, UIKeyboardInteractive {
    private final static String TAG = "UserProxy";
    private SFTPAdapter a;
    private UserInteractHandler uih;
    private File pkf;
    private int pwAttCount = 0, ppAttCount = 0;
    private static String lastMessage;

    public UserProxy( SFTPAdapter a, UserInteractHandler uih ) {
        this.a = a;
        this.uih = uih;
    }

    public void setPrivateKeyFileUsed( File pkf ) {
        this.pkf = pkf;
    }

    @Override
    public boolean promptPassword( String message ) {
        Log.d( TAG, message );
        Credentials crd = a.getCredentials();
        if( pwAttCount++ == 0 && crd != null && crd.getPassword() != null )
            return true;
        String fp = a.getFingerPrint();
        if( Utils.str( fp ) )
            message = "<small>" + a.getAlgorithm() + " " + fp + "</small><br/><br/>" + message;
        String pass = uih.askString( message );
        updateCredentials( pass );
        return pass != null;
    }

    @Override
    public boolean promptPassphrase( String message ) {
        Log.d( TAG, message );
        Credentials crd = a.getCredentials();
        if( ppAttCount++ == 0 && crd != null && crd.getPassword() != null )
            return true;
        if( pkf != null ) {
            String pp = pkf.getParent();
            if( pp != null )
                message = message.replace( Utils.mbAddSl( pp ), "" );
        }
        String pass = uih.askString( message );
        updateCredentials( pass );
        return pass != null;
    }

    @Override
    public String getPassphrase() {
        Log.d( TAG, "getPassphrase()" );
        Credentials crd = a.getCredentials();
        return crd != null ? crd.getPassword() : null;
    }

    @Override
    public String getPassword() {
        Log.d( TAG, "getPassword()" );
        Credentials crd = a.getCredentials();
        return crd != null ? crd.getPassword() : null;
    }

    @Override
    public boolean promptYesNo( String message ) {
        Log.d( TAG, message );
        if( !uih.isOKtoUse() )
            return true;
        return uih.confirm( message );    // note, true will make JSch add to known hosts!
    }

    @Override
    public void showMessage( String message ) {
        Log.d( TAG, message );
        if( lastMessage == null || !lastMessage.equals( message ) ) {
            uih.show( message );
            lastMessage = message;
        }
    }

    @Override
    public String[] promptKeyboardInteractive( String destination, String name, String instruction, String[] prompt, boolean[] echo ) {
        if( pwAttCount++ == 0 && prompt.length == 1 && prompt[0].startsWith( "Password" ) ) {
            Credentials crd = a.getCredentials();
            if( crd != null ) {
                String pass = crd.getPassword();
                if( pass != null && pass.length() > 0 )
                    return new String[] { pass };
            }
        }
        StringBuilder sb = new StringBuilder();
        String fp = a.getFingerPrint();
        if( Utils.str( fp ) ) {
            sb.append( "<small>" );
            sb.append( a.getAlgorithm() );
            sb.append( " " );
            sb.append( fp );
            sb.append( "</small><br/><br/>" );
        }
        if( Utils.str( destination ) ) sb.append( destination ).append( "\n" );
        if( Utils.str( name ) ) sb.append( name ).append( "\n" );
        if( Utils.str( instruction ) ) sb.append( instruction ).append( "\n" );
        String[] response = uih.askStrings( sb.toString(), prompt );
        if( response == null )
            return null;
        if( response.length == 1 && prompt.length == 1 && prompt[0].startsWith( "Password" ) ) {
            updateCredentials( response[0] );
        }
        return response;
    }

    private void updateCredentials( String pass ) {
        Credentials crd_old = a.getCredentials();
        if( crd_old != null ) {
            Credentials crd_new = new Credentials( crd_old.getUserName(), pass );
            a.setCredentials( crd_new );
            pwAttCount = 0;
        }
    }
}
