package com.ghostsq.commander.sftp;

import android.content.Context;
import android.util.Log;

import com.ghostsq.commander.utils.Utils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class KeysKeeper {
    private static final String TAG = "KeysKeeper";
    private static final String KEYS_SUBDIR = "keys";
    private JSch jsch = new JSch();
    private File fKeysDir;

    private  Context ctx;
    private ArrayList<HostKey> alHostKeys;

    class HostKey {
        private String  host;
        private File    file;
        private KeyPair keyPair;

        public HostKey( String h, File f ) {
            this.host = h;
            this.file = f;
        }

        public final String getHost() {
            return host;
        }

        public final boolean isHostSet() {
            return host != null && host.length() > 0;
        }

        public final boolean isKeyFileExists() {
            return file != null && file.exists();
        }

        public final boolean hasKeyFile() {
            return file != null;
        }

        public final boolean setHost( String h ) {
            try {
                if( host.equals( h ) )
                    return true;
                if( file != null ) {
                    File newf = new File( file.getParentFile(), h );
                    if( newf.exists() )
                        return false;
                    if( !file.renameTo( newf ) )
                        return false;
                    file = newf;
                }
                this.host = h;
                return true;
            } catch( Exception e ) {
                Log.e( TAG, h, e );
            }
            return false;
        }

        public final boolean importFile( File orig_file ) {
            if( orig_file == null || !orig_file.exists() )
                return false;
            keyPair = null;
            try( InputStream fis = new FileInputStream( orig_file ) ) {
                return importStream( fis, orig_file.getName() );
            } catch( Exception e ) {
                Log.e( TAG, orig_file.getAbsolutePath(), e );
            }
            return false;
        }

        public final boolean importStream( InputStream is, String name ) {
            if( is == null ) return false;
            keyPair = null;
            boolean new_key = host == null || host.isEmpty();
            if( new_key ) {
                if( name == null || name.isEmpty() )
                    name = String.valueOf( Math.ceil( Math.random() * 1000000 ) );
                host = name.replace( "/", "." );
            }
            File dest_file = null;
            for( int i = 1; i < 99; i++ ) {
                dest_file = new File( KeysKeeper.this.fKeysDir, host );
                if( dest_file.exists() ) {
                    if( !new_key ) {
                        dest_file.delete();
                        break;
                    }
                    host = name + " " + i;
                }
            }
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream( dest_file );
                if( Utils.copyBytes( is, fos ) ) {
                    file = dest_file;
                    return true;
                }
            } catch( Exception e ) {
                Log.e( TAG, name, e );
            } finally {
                try {
                    if( fos != null ) fos.close();
                } catch( IOException e ) {
                    Log.e( TAG, name, e );
                }
            }
            return false;
        }

        public final void delete() {
            if( file != null )
                file.delete();
        }

        public final boolean isReencryptable() {
            try( BufferedReader br = new BufferedReader(new FileReader( file.getAbsolutePath() ) ) )
            {
                String s;
                while( ( s = br.readLine() ) != null ) {
                    if( s.length() == 0 ) return false;
                    if( s.indexOf( "OPENSSH" )> 0 ) return false;
                    if( s.indexOf( "DEK-Info: DES-EDE3-CBC" ) == 0 ) return true;
                }
            }
            catch( IOException e ) {
                Log.e( TAG, file.getName(), e );
            }
            return false;
        }

        public final Boolean isEncrypted() {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair != null )
                    return keyPair.isEncrypted();
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return null;
        }

        public final String getType() {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair == null )
                    return null;
                switch( keyPair.getKeyType() ) {
                    case KeyPair.DSA:
                        return "DSA";
                    case KeyPair.RSA:
                        return "RSA";
                    case KeyPair.ECDSA:
                        return "ECDSA";
                    case KeyPair.ERROR:
                    case KeyPair.UNKNOWN:
                        return null;
                }
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return null;
        }

        public final String getFingerprint() {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair == null )
                    return null;
                return keyPair.getFingerPrint();
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return null;
        }

        public final String getComment() {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair == null )
                    return null;
                return keyPair.getPublicKeyComment();
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return null;
        }

        public final boolean check( String passphrase ) {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair == null )
                    return false;
                boolean ok = keyPair.decrypt( passphrase );
                if( !ok )
                    keyPair = null;
                return ok;
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return false;
        }

        public final boolean changePassphrase( String old_pp, String new_pp ) {
            try {
                if( keyPair == null )
                    keyPair = KeyPair.load( jsch, file.getAbsolutePath() );
                if( keyPair == null )
                    return false;
                if( !keyPair.decrypt( old_pp ) ) {
                    keyPair = null;
                    return false;
                }
                keyPair.setPassphrase( new_pp );
                file.delete();
                keyPair.writePrivateKey( file.getAbsolutePath() );
                keyPair.dispose();
                keyPair = null;
                return check( new_pp );
            } catch( Exception e ) {
                Log.e( TAG, this.host, e );
            }
            return false;
        }
    }

    public class HostKeyComparator implements Comparator<HostKey> {
        @Override
        public int compare( HostKey k1, HostKey k2 ) {
            if( k1 == null ) return -1;
            if( k2 == null ) return  1;
            return k1.host.compareTo( k2.host );
        }
    }

    public KeysKeeper( Context ctx ) {
        this.ctx = ctx;
        fKeysDir = getKeyDir( ctx );
    }

    private static File getKeyDir( Context ctx ) {
        File f_base_dir = sftp.getDir( ctx );
        return new File( f_base_dir, KEYS_SUBDIR );
    }

    public static File getPrivateKeyFile( Context ctx, String user, String host ) {
        File keys_dir = getKeyDir( ctx );
        File hf = new File( keys_dir, host );
        if( hf.exists() )
            return hf;
        File uhf = new File( keys_dir, user + "@" + host );
        if( uhf.exists() )
            return uhf;
        return null;
    }

    public final int scan() {
        if( !fKeysDir.exists() ) {
            fKeysDir.mkdir();
            alHostKeys = null;
            return 0;
        }
        File[] key_files = fKeysDir.listFiles();
        if( key_files == null || key_files.length == 0 ) {
            alHostKeys = null;
            return 0;
        }
        alHostKeys = new ArrayList<HostKey>( key_files.length );
        for( File f : key_files ) {
            HostKey hk = new HostKey( f.getName(), f );
            alHostKeys.add( hk );
        }
        Collections.sort( alHostKeys, new HostKeyComparator() );
        return alHostKeys.size();
    }

    public final HostKey get( int i ) {
        if( alHostKeys == null)
            return null;
        return alHostKeys.get( i );
    }

    public final KeysKeeper.HostKey addNew() {
        if( alHostKeys == null )
            alHostKeys = new ArrayList<HostKey>( 1 );
        HostKey hk = new HostKey( "", null );
        alHostKeys.add( hk );
        return hk;
    }
    public final void delete( KeysKeeper.HostKey hk ) {
        if( alHostKeys == null ) return;
        alHostKeys.remove( hk );
    }

    public static boolean checkMigrateKeys( Context ctx ) {
        File app_key_dir = getKeyDir( ctx );
        if( !app_key_dir.exists() )
            app_key_dir.mkdir();
        File[] key_files = app_key_dir.listFiles();
        if( key_files != null && key_files.length > 0 )
            return true;
        File plg_key_dir = new File( sftp.getDir( ctx, true ), KEYS_SUBDIR );
        if( !plg_key_dir.exists() )
            return true;
        key_files = plg_key_dir.listFiles();
        if( key_files == null || key_files.length == 0 )
            return true;
        int c = 0;
        for( File f_src : key_files ) {
            try {
                File f_dst = new File( app_key_dir, f_src.getName() );
                FileInputStream fis = new FileInputStream( f_src );
                FileOutputStream fos = new FileOutputStream( f_dst );
                if( Utils.copyBytes( fis, fos ) )
                    c++;
            } catch( FileNotFoundException e ) {
                Log.e( TAG, f_src.getAbsolutePath(), e );
            }
        }
        return c > 0;
    }
}
