package com.ghostsq.commander.utils;

import android.content.Context;
import android.util.Base64;

import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class Crypt {
    private static final String  TAG = "Crypt";
    private static String seed   = null;
    private static byte[] rawKey = null;

    public static byte[] getRawKey() throws Exception {
        if( Crypt.rawKey != null )
            return Crypt.rawKey;
        if( seed == null  )
            seed = String.valueOf( Math.random() );
        Crypt.rawKey = getRawKey( seed );
        return Crypt.rawKey;
    }
    private static byte[] getRawKey( String seed ) throws Exception {
        KeyGenerator kgen = KeyGenerator.getInstance( "AES" );
        SecureRandom sr = SecureRandom.getInstance( "SHA1PRNG" );
        sr.setSeed( seed.getBytes() );
        kgen.init( 128, sr ); // 192 and 256 bits may not be available
        SecretKey skey = kgen.generateKey();
        byte[] raw = skey.getEncoded();
        return raw;
    }

    // symmetric
    public static byte[] encrypt( byte[/*128*/] raw_key, byte[] clear ) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec( raw_key, "AES" );
        Cipher cipher = Cipher.getInstance( "AES" );
        cipher.init( Cipher.ENCRYPT_MODE, skeySpec );
        byte[] encrypted = cipher.doFinal( clear );
        return encrypted;
    }
    public static byte[] decrypt( byte[/*128*/] raw_key, byte[] encrypted ) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec( raw_key, "AES" );
        Cipher cipher = Cipher.getInstance( "AES" );
        cipher.init( Cipher.DECRYPT_MODE, skeySpec );
        byte[] decrypted = cipher.doFinal( encrypted );
        return decrypted;
    }

    // asymmetric, key pair stored in Android storage

    public static byte[] encrypt( Key k, byte[] clear ) throws Exception {
        Cipher cipher = Cipher.getInstance( "RSA/ECB/PKCS1Padding" );

        if( !(k instanceof PublicKey) )
            return null;
        cipher.init( Cipher.ENCRYPT_MODE, k );
        return cipher.doFinal( clear );
    }

    public static byte[] decrypt( Key k, byte[] encb ) throws Exception {
        // don't use the "AndroidOpenSSL" provider, it's not able to cast
        // "AndroidKeyStoreRSAPrivateKey" to "RSAPrivateKey"
        Cipher cipher = Cipher.getInstance( "RSA/ECB/PKCS1Padding" );
        if( !(k instanceof PrivateKey) )
            return null;
        cipher.init( Cipher.DECRYPT_MODE, k );
        return cipher.doFinal( encb );
    }

    public static String encrypt( Context ctx, String cleartext, boolean base64out ) throws Exception {
        Key k = KeyStorage.provideRSAKey( ctx, TAG, false );
        byte[] encrypted = encrypt( k, cleartext.getBytes() );
        if( encrypted == null )
            return null;
        if( base64out )
            return Base64.encodeToString( encrypted, Base64.DEFAULT );
        else
            return Utils.toHexString( encrypted, null );
    }
    
    public static String decrypt( Context ctx, String encrypted, boolean base64in ) throws Exception {
        byte[] encb = base64in ? Base64.decode( encrypted, Base64.DEFAULT ) : Utils.hexStringToBytes( encrypted );
        if( encb == null )
            return null;
        Key k = KeyStorage.provideRSAKey( ctx, TAG, true );
        byte[] decb = decrypt( k, encb );
        if( decb == null )
            return null;
        return new String( decb );
    }
}
