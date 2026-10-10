package com.ghostsq.commander.sftp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Html;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ghostsq.commander.R;
import com.ghostsq.commander.utils.Utils;

import java.util.ArrayList;

class UserInteractHandler extends Handler {
    private final static String TAG = "UserInteractHandler";
    public final static int SHOW = 2692, CONFIRM = 3964, PASS = 0122, MULT = 7795;
    private final static String PROMPT = "1prompt", PROMPTS = "prompts";
    private Context ctx;
    private Resources sftp_res;
    private boolean   fail;
    private Boolean   bResult;
    private String    sResult;
    private String[]  ssResult;

    UserInteractHandler( Context ctx ) {
        super( ctx.getMainLooper() );
        this.ctx = ctx;
        try {
            PackageManager pm = ctx.getPackageManager();
            sftp_res = pm.getResourcesForApplication( sftp.getPackageName() );
        } catch( PackageManager.NameNotFoundException e ) {
            Log.e( TAG, "", e );
        }
        Utils.changeLanguage( ctx, sftp_res );
    }

    public boolean isOKtoUse() {
        return ctx != null && ctx.getApplicationContext() != ctx;
    }

    public final void show( String string ) {
        sendMessage( SHOW, string );
    }

    public final boolean confirm( String prompt ) {
        sendMessage( CONFIRM, prompt );
        return bResult != null ? bResult : false;
    }

    public final String askString( String prompt ) {
        sResult = null;
        sendMessage( PASS, prompt );
        return bResult != null && bResult ? sResult : null;
    }

    public final String[] askStrings( String title, String prompts[] ) {
        ssResult = null;
        sendMessage( MULT, title, prompts );
        return bResult != null && bResult ? ssResult : null;
    }

    private void sendMessage( int what, String prompt ) {
        Message msg = Message.obtain( this, what );
        Bundle b = msg.getData();
        b.putString( PROMPT, prompt );
        send( msg );
    }

    private void sendMessage( int what, String title, String prompts[] ) {
        Message msg = Message.obtain( this, what );
        Bundle b = msg.getData();
        b.putString( PROMPT, title );
        b.putStringArray( PROMPTS, prompts );
        send( msg );
    }

    private boolean send( Message msg ) {
        try {
            bResult = null;
            fail = false;
            if( !sendMessage( msg ) ) {
                Log.e( TAG, "Can't send message" );
                return false;
            }
            for( int i = 1; i < 9; i++ ) {
                synchronized( this ) {
                    wait( 10000 );
                    if( bResult != null )
                        break;
                    if( fail ) {
                        Log.e( TAG, "Failed" );
                        return false;
                    }
                }
                Log.d( TAG, "Waiting... " + i );
            }
            Log.d( TAG, "Wait is over." );
            return true;
        } catch( InterruptedException e ) {
            Log.e( TAG, "", e );
        }
        return false;
    }

    // --- UI thread ---

    @Override
    public void handleMessage( Message msg ) {
            dispatch( msg.what, msg.getData() );
    }

    private synchronized void dispatch( int what, Bundle data ) {
        try {
            Log.d( TAG, "What: " + what );
            if( what == SHOW )
                show( data );
            if( what == CONFIRM )
                confirm( data );
            else if( what == PASS )
                ask( data );
            else if( what == MULT )
                mult( data );
            else
                notifyAll();
        } catch( Exception e ) {
            Log.e( TAG, "", e );
            notifyAll();
        }
    }

    private void show( Bundle data ) {
        OnClickListener ocl = new OnClickListener( null );
        new AlertDialog.Builder( ctx )
            .setTitle( "SFTP" )
            .setMessage( data.getString( PROMPT ) )
            .setPositiveButton( sftp_res.getString( R.string.ok ), ocl )
            .show();
    }

    private void confirm( Bundle data ) {
        OnClickListener ocl = new OnClickListener( null );
        new AlertDialog.Builder( ctx )
            .setTitle( "SFTP" )
            .setMessage( data.getString( PROMPT ) )
            .setPositiveButton( sftp_res.getString( R.string.yes ), ocl )
            .setNegativeButton( sftp_res.getString( R.string.no  ), ocl )
            .show();
    }

    private void ask( Bundle data ) {
        LinearLayout ll = new LinearLayout( ctx );
        TextView tv = new TextView( ctx );
        String prompt = data.getString( PROMPT );
        if( prompt != null && prompt.contains( "<small>" ) )
            tv.setText( Html.fromHtml( prompt ) );
        else
            tv.setText( prompt );
        final EditText et = new EditText( ctx );
        et.setSingleLine();
        et.setInputType( InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT );
        et.setTransformationMethod( PasswordTransformationMethod.getInstance() );
        ll.setOrientation( LinearLayout.VERTICAL );
        ll.addView( tv );
        ll.addView( et );
        ll.setPadding( 50, 40, 50, 10 );

        OnClickListener ocl = new OnClickListener( ll );
        new AlertDialog.Builder( ctx )
            .setTitle( "SFTP" )
            .setView( ll )
            .setPositiveButton( sftp_res.getString( R.string.ok ), ocl )
            .setNegativeButton( sftp_res.getString( R.string.cancel ), ocl )
            .show();
    }

    private void mult( Bundle data ) {
        LinearLayout ll = new LinearLayout( ctx );
        TextView tv = new TextView( ctx );
        tv.setText( data.getString( PROMPT ) );
        ll.addView( tv );

        String[] prompts = data.getStringArray( PROMPTS );
        for( String p : prompts ) {
            tv = new TextView( ctx );
            tv.setText( p );
            ll.addView( tv );

            EditText et = new EditText( ctx );
            et.setSingleLine();
            et.setInputType( InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT );
            et.setTransformationMethod( PasswordTransformationMethod.getInstance() );
            ll.setOrientation( LinearLayout.VERTICAL );
            ll.addView( et );
        }
        ll.setPadding( 50, 40, 50, 10 );

        OnClickListener ocl = new OnClickListener( ll, true );
        new AlertDialog.Builder( ctx )
            .setTitle( "SFTP" )
            .setView( ll )
            .setPositiveButton( sftp_res.getString( R.string.ok ), ocl )
            .setNegativeButton( sftp_res.getString( R.string.cancel ), ocl )
            .show();
    }

    private class OnClickListener implements DialogInterface.OnClickListener {
        ViewGroup dialogContent;
        boolean   mult = false;
        public OnClickListener( ViewGroup dialog_content ) {
            this.dialogContent = dialog_content;
        }
        public OnClickListener( ViewGroup dialog_content, boolean mult ) {
            this.dialogContent = dialog_content;
            this.mult = mult;
        }

        public void onClick( DialogInterface dialog, int which ) {
            synchronized( UserInteractHandler.this ) {
                if( dialogContent != null ) {
                    int n = dialogContent.getChildCount();
                    if( mult ) {
                        ArrayList<String> list = new ArrayList<String>( n / 2 );
                        for( int i = 0; i < n; i++ ) {
                            View child = dialogContent.getChildAt( i );
                            if( child instanceof EditText )
                                list.add( ( (EditText)child ).getText().toString() );
                        }
                        UserInteractHandler.this.ssResult = new String[list.size()];
                        list.toArray( UserInteractHandler.this.ssResult );
                    } else {
                        for( int i = 0; i < n; i++ ) {
                            View child = dialogContent.getChildAt( i );
                            if( child instanceof EditText ) {
                                UserInteractHandler.this.sResult = ( (EditText)child ).getText().toString();
                                break;
                            }
                        }
                    }
                }
                UserInteractHandler.this.bResult = new Boolean( which == DialogInterface.BUTTON_POSITIVE );
                UserInteractHandler.this.notifyAll();
            }
        }
    }
};
