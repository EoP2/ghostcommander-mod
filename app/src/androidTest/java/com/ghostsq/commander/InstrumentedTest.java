package com.ghostsq.commander;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.Message;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Credentials;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class InstrumentedTest implements Commander {
    private final String TAG = getClass().getName();
    private CommanderAdapter ca;

    @Test
    public void useAppContext() {
        assertEquals( "com.ghostsq.commander", getContext().getPackageName() );
        Looper.prepare();
        //testFSAdapter();
        testFTPAdapter();
        Looper.loop();
    }

    public void nextTest() {
        Looper ml = Looper.myLooper();
        if( ml != null ) ml.quit();
    }

    public void testFSAdapter() {
        Uri u = Uri.parse( "/sdcard" );
        ca = CA.CreateAdapterInstance( u, getContext() );
        assertNotNull( "Adapter is null!", ca );
        ca.Init( this );
        ca.setUri( u );
        //assertEquals( "set/get URIs do not match!", u, ca.getUri() );
        ca.readSource( u, null );
    }

    public void testFTPAdapter() {
        Uri u = Uri.parse( "ftp://speedtest.tele2.net/" );
        ca = CA.CreateAdapterInstance( u, getContext() );
        assertNotNull( "Adapter is null!", ca );
        ca.Init( this );
        ca.setUri( u );
        assertEquals( "set/get URIs do not match!", u, ca.getUri() );
        ca.readSource( u, null );
    }


    @Override
    public Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Override
    public boolean notifyMe( Message m ) {
        assertNotNull( "Message is null!", m );
        Log.d( TAG, "notifyMe() called. " + m.toString() );
        assertNotNull( "Adapter is null!", ca );
        switch( m.what ) {
            case OPERATION_COMPLETED:
            case OPERATION_COMPLETED_REFRESH_REQUIRED:
                Log.d( TAG, "Completed!" );
                Uri iu = ca.getItemUri( 1 );
                assertNotNull( "Adapter does not return an item!", iu );
                Log.d( TAG, "Item at pos 1 URI=" + iu.toString() );
                InstrumentedTest.this.nextTest();
                return true;
            case OPERATION_FAILED:
            case OPERATION_FAILED_LOGIN_REQUIRED:
            case OPERATION_FAILED_REFRESH_REQUIRED:
                fail( "Operation has failed!" );
                return true;
        }
        return false;
    }

    @Override
    public boolean startEngine( Engine e ) {
        e.start();
        return true;
    }

    @Override
    public boolean stopEngine( long task_id ) {
        return false;
    }

    @Override
    public void issue( Intent in, int ret ) {
    }
    @Override
    public void showError( String msg ) {
    }
    @Override
    public void showInfo( String msg ) {
    }
    @Override
    public void showDialog( int dialog_id ) {
    }
    @Override
    public void Navigate( Uri uri, Credentials crd, String positionTo ) {
    }
    @Override
    public void dispatchCommand( int id ) {
    }
    @Override
    public void Open( Uri uri, Credentials crd ) {
    }
    @Override
    public int getResolution() {
        return 0;
    }


}
