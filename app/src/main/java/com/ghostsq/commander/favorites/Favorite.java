package com.ghostsq.commander.favorites;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import java.util.regex.Pattern;

public class Favorite {
    private final static String TAG = "Favorite";
    // store/restore
    private static final String  sep = ",";
    private static final Pattern sep_re = Pattern.compile( sep );
    public static final int FLG_FILE = 1;
    
    // fields
    private String      id;
    private Uri         uri;
    private String      comment;
    private Credentials credentials;
    private int         flags;

    public Favorite( Uri u ) {
        this( u, null, null, 0 );
    }
    public Favorite( Uri u, Credentials c ) {
        this( u, c, null, 0 );
    }
    public Favorite( String uri_str, String comment_ ) {
        this( Uri.parse( uri_str ), null, comment_, 0 );
    }
    public Favorite( Uri u, Credentials c, String comment_, int flg ) {
        if( u == null ) u = (new Uri.Builder()).build();
        if( c == null )
            extractCredentialsFromUri( u );
        else {
            uri = Utils.updateUserInfo( u, null );
            credentials = c;
        }
        this.comment = comment_;
        this.flags = flg;
        this.setID( Integer.toHexString( u.hashCode() ) );
    }

    public final void extractCredentialsFromUri( Uri u ) {
        try {
            uri = u;
            String user_info = uri.getUserInfo();
            if( user_info != null && user_info.length() > 0 ) {
                credentials = new Credentials( user_info );
                String pw = credentials.getPassword();
                if( Credentials.pwScreen.equals( pw ) ) 
                    credentials = new Credentials( credentials.getUserName(), credentials.getPassword() );
                uri = Utils.updateUserInfo( uri, null );
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
        }
    }    

    public final String getID() {
        return id;
    }
    
    private final void setID( String id ) {
        this.id = id;
    }
    
    public boolean isFile() {
        return ( FLG_FILE & flags ) != 0;
    }

    public static Favorite fromString( String raw, Context ctx ) {
        if( raw == null ) return null;
        try {
            String[] flds = sep_re.split( raw );
            if( flds == null ) return null;
            Uri uri = null;
            String comment = null;
            Credentials credentials = null;
            int flg = 0;
            for( int i = 0; i < flds.length; i++ ) {
                String s = flds[i];
                if( s == null || s.length() == 0 ) continue;
                
                String sv = unescape( s.substring( 4 ) );
                if( s.startsWith( "URI=" ) ) uri = Uri.parse( sv ); 
                else if( s.startsWith( "CMT=" ) ) comment = sv;
                else if( s.startsWith( "CRS=" ) ) credentials = Credentials.fromEncryptedString( sv, ctx );
                else if( s.startsWith( "FLG=" ) ) flg = Integer.parseInt( sv );
            }
            return new Favorite( uri, credentials, comment, flg );
        }
        catch( Exception e ) {
            Log.e( TAG, "can't restore " + raw, e );
        }
        return null;
    }
    
    public final void store( Context ctx, SharedPreferences.Editor ed ) {
        ed.putString( "URI_" + id, uri.toString() );
        if( comment != null )
            ed.putString( "CMT_" + id, comment );
        if( credentials != null )
            ed.putString( "CRS_" + id, credentials.toEncryptedString( ctx ) );
        else
            ed.remove( "CRS_" + id );
        ed.putInt( "FLG_" + id, flags );
    }

    public static void erasePrefs( String id, SharedPreferences.Editor ed ) {
        ed.remove( "URI_" + id );
        ed.remove( "CMT_" + id );
        ed.remove( "CRS_" + id );
        ed.remove( "CRD_" + id );
        ed.remove( "FLG_" + id );
    }
    
    public static Favorite restore( Context ctx, String id, SharedPreferences sp ) {
        String sv = sp.getString( "URI_" + id, null );
        if( sv == null ) return null;
        Uri uri = null;
        String comment = null;
        Credentials credentials = null;
        uri = Uri.parse( sv );
        comment = sp.getString( "CMT_" + id, null );
        String crs = null;
        crs = sp.getString( "CRS_" + id, null );
        if( crs != null )
            credentials = Credentials.fromEncryptedString( crs, ctx );
        int flg = sp.getInt( "FLG_" + id, 0 );
        Favorite f = new Favorite( uri, credentials, comment, flg );
        f.setID( id );
        return f;
    }
    
    public final String getComment() {
        return comment;
    }
    public final void setComment( String s ) {
        comment = s;
    }
    public final void setUri( Uri u ) {
        uri = u;
    }
    public final Uri getUri() {
        return uri;
    }
    public final Uri getUriWithAuth() {
        if( credentials == null ) return uri; 
        return Utils.getUriWithAuth( uri, credentials.getUserName(), credentials.getPassword() );
    }
    public final String getUriString( boolean screen_pw ) {
        try {
            if( uri == null ) return null;
            if( credentials == null ) return uri.toString();
            if( screen_pw )
                return Utils.getUriWithAuth( uri, credentials.getUserName(), Credentials.pwScreen ).toString();
            else
                return getUriWithAuth().toString();
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return null;
    }
    public Credentials getCredentials() {
        return credentials;
    }

    public final boolean equals( String test ) {
        String item = getUriString( false );
        if( item != null ) {
            String strip_item = item.trim();
            if( strip_item.length() == 0 || strip_item.charAt( strip_item.length()-1 ) != '/' )
                strip_item += "/";
            if( strip_item.compareTo( test ) == 0 )
                return true;
        }
        return false;
    }
    
    public final String getUserName() {
        return credentials == null ? null : credentials.getUserName();
    }
    public final String getPassword() {
        return credentials == null ? "" : credentials.getPassword();
    }
    public final void setCredentials( String un, String pw ) {
        if( un == null || un.length() == 0 ) {
            credentials = null;
            return;
        }
        credentials = new Credentials( un, pw );
    }
    
    private static String unescape( String s ) {
        return s.replace( "%2C", sep );
    }
    private static String escape( String s ) {
        return s.replace( sep, "%2C" );
    }

    public final static String screenPwd( String uri_str ) {
        if( uri_str == null ) return null;
        return screenPwd( Uri.parse( uri_str ) );
    }
    public final static String screenPwd( Uri u ) {
        if( u == null ) return null;
        String ui = u.getUserInfo();
        if( ui == null || ui.length() == 0 ) return u.toString();
        int pw_pos = ui.indexOf( ':' );
        if( pw_pos < 0 ) return u.toString();
        ui = Uri.encode( ui.substring( 0, pw_pos ) ) + ":" + Credentials.pwScreen;
//        return Uri.decode( Utils.updateUserInfo( u, ui ).toString() );
        return Utils.updateUserInfo( u, ui ).toString();
    }
    public final static boolean isPwdScreened( Uri u ) {
        String user_info = u.getUserInfo();
        if( user_info != null && user_info.length() > 0 ) {
            Credentials crd = new Credentials( user_info );
            if( Credentials.pwScreen.equals( crd.getPassword() ) ) return true;
        }
        return false;
    }
    
    public final Credentials borrowPassword( Uri stranger_uri ) {
        if( credentials == null ) return null;
        String stranger_user_info = stranger_uri.getUserInfo();
        String username = credentials.getUserName(); 
        String password = credentials.getPassword(); 
        if( username != null && password != null && stranger_user_info != null && stranger_user_info.length() > 0 ) {
            Credentials stranger_crd = new Credentials( stranger_user_info );
            String stranger_username = stranger_crd.getUserName();
            if( username.equalsIgnoreCase( stranger_username ) )
                return new Credentials( stranger_username, password );
        }
        return null;
    }
    
    public static Uri borrowPassword( Uri us, Uri fu ) {
        String schm = us.getScheme();
        if( schm != null && schm.equals( fu.getScheme() ) ) {
            String host = us.getHost();
            if( host != null && host.equalsIgnoreCase( fu.getHost() ) ) {
                String uis = us.getUserInfo();
                String fui = fu.getUserInfo();
                if( fui != null && fui.length() > 0 ) {
                    Credentials crds = new Credentials( uis );
                    Credentials fcrd = new Credentials( fui );
                    String un = crds.getUserName();
                    if( un != null && un.equals( fcrd.getUserName() ) )
                        return Utils.getUriWithAuth( us, un, fcrd.getPassword() );
                }
            }
        }
        return null;
    }    
}
