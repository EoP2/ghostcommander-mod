package com.ghostsq.commander.sftp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewManager;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ghostsq.commander.BuildConfig;
import com.ghostsq.commander.FileCommander;
import com.ghostsq.commander.R;

import java.io.File;
import java.io.InputStream;

public class KeysActivity extends Activity implements OnClickListener, View.OnFocusChangeListener {
    private static final String TAG = "KeysActivity";
    private static final int REQ_CODE = 8362;
    private LayoutInflater infl;
    private LinearLayout ctr;
    private KeysKeeper kk;
    private KeysKeeper.HostKey hkPicking;

    @Override
    public void onCreate( Bundle bundle ) {
        try {
            KeysKeeper.checkMigrateKeys( this );
            Intent in = getIntent();
            if( in != null ) {
                String th = in.getStringExtra( "theme" );
                if( th != null ) {
                    setTheme( "l".equals( th ) ? android.R.style.Theme_Material_Light : android.R.style.Theme_Material );
                }
            }
            super.onCreate( bundle );
            setContentView( R.layout.sftp_keys );
            refresh();
            View ankb = findViewById( R.id.add_new_key );
            ankb.setOnClickListener( this );
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            int n = ctr.getChildCount();
            for( int i = 0; i < n; i++ ) {
                RelativeLayout tl = (RelativeLayout) ctr.getChildAt( i );
                EditText hne = (EditText) tl.findViewById( R.id.host_name );
                if( hne != null ) {
                    KeysKeeper.HostKey hk = kk.get( i );
                    if( hk != null )
                        hk.setHost( hne.getText().toString() );
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    @Override
    protected void onActivityResult( int requestCode, int resultCode, Intent data ) {
        super.onActivityResult( requestCode, resultCode, data );
        try {
            if( BuildConfig.DEBUG )
                Toast.makeText( this,"onActivityResult() " + data, Toast.LENGTH_LONG ).show();
            if( requestCode != REQ_CODE || hkPicking == null ) return;
            if( resultCode != RESULT_OK || data == null ) {
                hkPicking = null;
                return;
            }
            Uri uri = data.getData();
            if( uri == null ) {
                hkPicking = null;
                return;
            }
            boolean was_not_set = hkPicking.isHostSet();
            String scheme = uri.getScheme();
            if( scheme == null || scheme.isEmpty() || "file".equals(scheme) ) {
                File orig_file = new File( uri.getPath() );
                if( orig_file.exists() && hkPicking.importFile( orig_file ) )
                    hasBeenPicked( hkPicking, was_not_set );
                else
                    Toast.makeText( this, getString( R.string.bad_file ), Toast.LENGTH_LONG ).show();
            } else
            if( ContentResolver.SCHEME_CONTENT.equals(scheme) ) {
                Cursor qc = getContentResolver().query( uri, null, null, null, null );
                int nci = qc.getColumnIndex( OpenableColumns.DISPLAY_NAME );
                qc.moveToFirst();
                String name = qc.getString( nci );
                if( name == null || name.length() == 0 ) {
                    name = uri.getLastPathSegment();
                    if( name != null ) {
                        int lsi = name.lastIndexOf( '/' );
                        if( lsi >= 0 )
                            name = name.substring( lsi + 1 );
                    }
                }
                InputStream is = getContentResolver().openInputStream( uri );
                if( hkPicking.importStream( is, name ) )
                    hasBeenPicked( hkPicking, was_not_set );
                else {
                    Toast.makeText( this, getString( R.string.bad_file ), Toast.LENGTH_LONG ).show();
                }
            }
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        hkPicking = null;
    }

    private void hasBeenPicked( KeysKeeper.HostKey hk, boolean was_not_set ) {
        View v = ctr.findViewWithTag( hkPicking );
        setView( hk, v, was_not_set != hk.isHostSet() );
    }

    private void setView( KeysKeeper.HostKey hk, View v, boolean set_host_name ) {
        if( v == null ) return;
        EditText hnet = (EditText)v.findViewById( R.id.host_name );
        if( set_host_name )
            hnet.setText( hk.getHost() );
        ImageButton pick_ib = (ImageButton)v.findViewById( R.id.pick_b );
        View chck_v = v.findViewById( R.id.check_b );
        View edit_v = v.findViewById( R.id.edit_b );
        String s_key_status = null;
        if( hk.hasKeyFile() ) {
            Boolean b_key_status = hk.isEncrypted();
            if( b_key_status != null ) {
                s_key_status = b_key_status ? getString( R.string.key_encrypted ) + ": " + hk.getType() : getString( R.string.not_encrypted );
                pick_ib.clearColorFilter();
                chck_v.setVisibility( View.VISIBLE );
                if( hk.isReencryptable() )
                    edit_v.setVisibility( View.VISIBLE );
            } else {
                s_key_status = getString( R.string.bad_file );
                pick_ib.setColorFilter( Color.RED );
                chck_v.setVisibility( View.GONE );
                edit_v.setVisibility( View.GONE );
            }
        } else {
            chck_v.setVisibility( View.GONE );
            edit_v.setVisibility( View.GONE );
            pick_ib.setColorFilter( Color.RED );
            s_key_status = getString( R.string.pick_file );
        }
        ((TextView)v.findViewById( R.id.status )).setText( s_key_status );
    }

    private void pickFile( KeysKeeper.HostKey hk ) {
        hkPicking = hk;
        Intent in = new Intent( Intent.ACTION_GET_CONTENT );
        in.setClass( this, FileCommander.class );
        in.setType( "*/*" );
        in.putExtra( "title", getString( R.string.pick_file ) );
        in.addCategory( Intent.CATEGORY_OPENABLE );
        this.startActivityForResult( in, REQ_CODE );
    }

    private final int refresh() {
        Log.d( TAG, "Refreshing..." );
        kk = new KeysKeeper( this );
        int n = kk.scan();
        ctr = (LinearLayout)findViewById( R.id.keys_container );
        ctr.removeAllViews();
        infl = getLayoutInflater();
        for( int i = 0; i < n; i++ )
            addView( kk.get( i ), false );
        return n;
    }

    private final boolean addView( KeysKeeper.HostKey hk, boolean focus ) {
        try {
            RelativeLayout kl = (RelativeLayout)infl.inflate( R.layout.sftp_key, ctr, false );
            kl.setTag( hk );
            View chck_v = kl.findViewById( R.id.check_b );
            chck_v.setOnClickListener( this );
            View edit_v = kl.findViewById( R.id.edit_b );
            edit_v.setOnClickListener( this );
            View pick_v = kl.findViewById( R.id.pick_b );
            pick_v.setOnClickListener( this );
            View save_v = kl.findViewById( R.id.save_b );
            save_v.setOnClickListener( this );
            View del_v = kl.findViewById( R.id.del_b );
            del_v.setOnClickListener( this );

            EditText hnet = (EditText)kl.findViewById( R.id.host_name );
            hnet.setOnFocusChangeListener( this );

            setView( hk, kl, true );

            ctr.addView( kl );
            if( focus ) {
                hnet.requestFocus();
                pickFile( hk );
            }
            return true;
        } catch( Exception e ) {
            Log.e( TAG, hk.getHost(), e );
        }
        return false;
    }

    private final KeysKeeper.HostKey getAssociatedHostKey( View v ) {
        if( v == null ) return null;
        ViewParent vp = v.getParent();
        if( !(vp instanceof View) ) return null;
        Object o = ( (View) vp ).getTag();
        if( !( o instanceof KeysKeeper.HostKey ) ) return null;
        return (KeysKeeper.HostKey)o;
    }

    // --- View.OnFocusChangeListener ---

    @Override
    public void onFocusChange( View v, boolean hasFocus ) {
        if( !(v instanceof EditText) ) return;
        final EditText hnet = (EditText)v;
        View pv = (View)v.getParent();
        ImageButton sb = pv.findViewById( R.id.save_b );
        sb.setVisibility( hasFocus ? View.VISIBLE : View.GONE );
        KeysKeeper.HostKey hk = getAssociatedHostKey( v );
        hnet.setText( hk.getHost() );
    }

    // --- OnClickListener ---

    @Override
    public void onClick( View v ) {
        try {
            int bid = v.getId();
            if( bid == R.id.add_new_key ) {
                addView( kk.addNew(), true );
                return;
            }
            KeysKeeper.HostKey hk = getAssociatedHostKey( v );
            if( hk == null ) return;
            if( v.getId() == R.id.pick_b )
                pickFile( hk );
            else if( v.getId() == R.id.del_b )
                onDelete( hk );
            else if( v.getId() == R.id.check_b )
                check( hk );
            else if( v.getId() == R.id.edit_b )
                edit( hk );
            else if( v.getId() == R.id.save_b ) {
                save( hk, v );
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
        }
    }

    // --- utilities ---

    private final void save( final KeysKeeper.HostKey hk, View clicked ) {
        if( hk == null ) return;
        View pv = (View)clicked.getParent();
        final EditText hnet = pv.findViewById( R.id.host_name );
        if( !hk.setHost( hnet.getText().toString() ) ) {
            Toast.makeText( this, getString( R.string.not_unique ), Toast.LENGTH_LONG ).show();
            hnet.setText( hk.getHost() );
            hnet.post( new Runnable() {
                @Override
                public void run() {
                    hnet.requestFocus();
                }
            } );
        }
    }

    private final void check( final KeysKeeper.HostKey hk ) {
        infl = getLayoutInflater();
        View pp_view = infl.inflate( R.layout.sftp_passphrase, ctr, false );
        TextView ppp = (TextView)pp_view.findViewById( R.id.passphrase_prompt );
        ppp.setText( getString( R.string.passphrase_for ) + " " + hk.getHost() );
        final EditText _input = (EditText)pp_view.findViewById( R.id.edit_passphrase );

        new AlertDialog.Builder( this )
                .setTitle( this.getString( R.string.test_passphrase ) )
                .setIcon( android.R.drawable.ic_menu_help )
                .setView( pp_view )
                .setPositiveButton( android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick( DialogInterface dialog, int which ) {
                            boolean ok = hk.check( _input.getText().toString() );
                            String res;
                            if( ok ) {
                                res = getString( android.R.string.ok ) + "\n" +  hk.getFingerprint();
                            } else
                                res = getString( R.string.wrong );
                            Toast toast = Toast.makeText( KeysActivity.this, res, Toast.LENGTH_LONG );
                            toast.setGravity( Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL, 0, 0 );
                            toast.show();
                        }
                    } )
                .setNegativeButton( android.R.string.cancel, null )
                .show();
    }

    private final void edit( final KeysKeeper.HostKey hk ) {
        infl = getLayoutInflater();
        View pp_view = infl.inflate( R.layout.sftp_passphrase, ctr, false );
        pp_view.findViewById( R.id.new_passphrase ).setVisibility( View.VISIBLE );
        TextView ppp = (TextView)pp_view.findViewById( R.id.passphrase_prompt );
        ppp.setText( getString( R.string.passphrase_for ) + hk.getHost() );
        final EditText _input_old = (EditText)pp_view.findViewById( R.id.edit_passphrase );
        final EditText _input_new = (EditText)pp_view.findViewById( R.id.edit_new_passphrase );

        new AlertDialog.Builder( this )
                .setTitle( this.getString( R.string.new_passphrase ) )
                .setIcon( android.R.drawable.ic_menu_edit )
                .setView( pp_view )
                .setPositiveButton( android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick( DialogInterface dialog, int which ) {
                            boolean ok = hk.changePassphrase( _input_old.getText().toString(), _input_new.getText().toString() );
                            String res = getString( ok ? android.R.string.ok : R.string.wrong );
                            Toast toast = Toast.makeText( KeysActivity.this, res, Toast.LENGTH_LONG );
                            toast.setGravity( Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL, 0, 0 );
                            toast.show();
                        }
                    } )
                .setNegativeButton( android.R.string.cancel, null )
                .show();
    }

    private final void onDelete( final KeysKeeper.HostKey hk ) {
        if( !hk.isKeyFileExists() ) {
            delete( hk );
            return;
        }
        new AlertDialog.Builder( this )
                .setTitle( this.getString( R.string.delete ) )
                .setMessage( this.getString( R.string.are_you_sure ) )
                .setPositiveButton( android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick( DialogInterface dialog, int which ) {
                            delete( hk );
                        }
                    } )
                .setNegativeButton( android.R.string.cancel, null )
                .show();
    }

    private final void delete( KeysKeeper.HostKey hk ) {
        hk.delete();
        kk.delete( hk );
        View v = ctr.findViewWithTag( hk );
        if( v != null ) {
            if( ctr instanceof ViewManager )
                ((ViewManager)ctr).removeView( v );
            else {
                v.setVisibility( View.GONE );
                v.setTag( null );
            }
        } else
            refresh();
    }
}
