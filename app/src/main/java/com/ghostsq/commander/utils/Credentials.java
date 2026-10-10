package com.ghostsq.commander.utils;


import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

public class Credentials implements Parcelable {
    private static String  TAG  = "GC.Credentials";
    public  static String  pwScreen = "***";
    public  static String  KEY  = "CRD";
    private String username, password;

    public Credentials( String usernamePassword ) { // ':' - separated
        if( usernamePassword == null ) {
            Log.e( TAG, "No credentials!" );
            return;
        }
        int cp = usernamePassword.indexOf( ':' );
        if( cp < 0 ) {
            this.username = usernamePassword;
            return;
        }
        this.username = usernamePassword.substring( 0, cp );
        this.password = usernamePassword.substring( cp + 1 );
    }
    public Credentials( String userName, String password ) {
        this.username = userName;
        this.password = password;
    }
    public Credentials( Credentials c ) {
        this( c.getUserName(), c.getPassword() );
    }
    public String getUserName() {
        return this.username;
    }
    public String getPassword() {
        return this.password;
    }

     public static final Parcelable.Creator<Credentials> CREATOR = new Parcelable.Creator<Credentials>() {
         public Credentials createFromParcel( Parcel in ) {
             String un = in.readString();
             String pw = "";
             try {
                 pw = new String( Crypt.decrypt( Crypt.getRawKey(), in.createByteArray() ) );
             } catch( Exception e ) {
                 Log.e( TAG, "on password decryption", e );
             }
             return new Credentials( un, pw );
         }

         public Credentials[] newArray( int size ) {
             return new Credentials[size];
         }
     };    
    
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel( Parcel dest, int f ) {
        byte[] enc_pw = null;
        if( password != null )
            try {
                enc_pw = Crypt.encrypt( Crypt.getRawKey(), getPassword().getBytes() );
            } catch( Exception e ) {
                Log.e( TAG, "on password encryption", e );
            }
        dest.writeString( getUserName() );
        dest.writeByteArray( enc_pw );
    }

    public String toEncryptedString( Context ctx ) {
        try {
            String toc = getUserName() + ":" + getPassword();
            return Crypt.encrypt( ctx, toc, false );
        } catch( Exception e ) {
            Log.e( TAG, "Cannot encrypt credentials, username=" + this.username , e );
        }
        return null;
    }
    public static Credentials fromEncryptedString( String s, Context ctx ) {
        try {
            String up = null;
            up = Crypt.decrypt( ctx, s, false );
            if( up != null )
                return new Credentials( up );
        } catch( Exception e ) {
            Log.e( TAG, "Cannot decrypt credentials!", e );
        }
        return null;
    }

    public void storeCredentials( Context ctx, String storage, Uri uri ) {
        int hash = ( getUserName() + uri.getHost() ).hashCode();
        SharedPreferences ssp = ctx.getSharedPreferences( storage, Context.MODE_PRIVATE );
        SharedPreferences.Editor edt = ssp.edit();
        edt.putString( "" + hash, toEncryptedString( ctx ) );
        edt.commit();
    }

    public static Credentials restoreCredentials( Context ctx, String storage, Uri uri ) {
        int hash = ( uri.getUserInfo() + uri.getHost() ).hashCode();
        SharedPreferences ssp = ctx.getSharedPreferences( storage, Context.MODE_PRIVATE );
        String crd_enc_s = ssp.getString( "" + hash, null );
        if( crd_enc_s == null )
            return null;
        return fromEncryptedString( crd_enc_s, ctx );
    }
    
}
