package com.ghostsq.commander.utils;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.UrlQuerySanitizer;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.ViewConfiguration;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.Adapter;

import com.ghostsq.commander.ColorsKeeper;
import com.ghostsq.commander.FileProvider;
import com.ghostsq.commander.R;
import com.ghostsq.commander.StreamProvider;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.SAFAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Utils {
    public static String C_AUDIO = "a", C_VIDEO = "v", C_TEXT = "t", C_ZIP = "z", C_OFFICE = "o",
             C_DROID = "d", C_BOOK = "b", C_IMAGE = "i", C_MARKUP = "m", C_APP = "x", C_PDF = "p", C_UNKNOWN = "u";
    public static String MIME_ALL = "*/*";

    private static final String[][] mimes = { // should be sorted!
            { ".3gp",  "audio/3gpp", C_AUDIO },
            { ".3gpp", "audio/3gpp", C_AUDIO },
            { ".7z",   "application/x-7z-compressed", C_ZIP },
            { ".aif",  "audio/x-aiff", C_AUDIO }, 
            { ".aiff", "audio/x-aiff", C_AUDIO },
            { ".amr",  "audio/amr", C_AUDIO },
            { ".apk",  "application/vnd.android.package-archive", C_DROID },
            { ".apks", "application/x-apks", C_DROID },
            { ".arc",  "application/x-freearc", C_ZIP },
            { ".arj",  "application/x-arj", C_ZIP },
            { ".arw",  "image/x-sony-arw", C_IMAGE },
            { ".au",   "audio/basic", C_AUDIO },
            { ".avi",  "video/x-msvideo", C_VIDEO }, 
            { ".avif", "image/avif", C_IMAGE },
            { ".b1",   "application/x-b1", C_APP },
            { ".bin",  "application/octet-stream", C_APP },
            { ".bmp",  "image/bmp", C_IMAGE },
            { ".bz",   "application/x-bzip2", C_ZIP },
            { ".bz2",  "application/x-bzip2", C_ZIP },
            { ".cab",  "application/x-compressed", C_ZIP },
            { ".cer",  "application/pkix-cert", C_APP },
            { ".chm",  "application/vnd.ms-htmlhelp", C_OFFICE },
            { ".conf", "application/x-conf", C_APP },
            { ".css",  "text/css", C_MARKUP },
            { ".csv",  "text/csv", C_TEXT },
            { ".db",   "application/x-sqlite3", C_APP },
            { ".deb",  "application/x-debian-package", C_APP },
            { ".dex",  "application/octet-stream", C_DROID },
            { ".djv",  "image/vnd.djvu", C_IMAGE },
            { ".djvu", "image/vnd.djvu", C_IMAGE },
            { ".doc",  "application/msword", C_OFFICE },
            { ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", C_OFFICE },
            { ".epub", "application/epub+zip", C_BOOK }, 
            { ".fb2",  "application/fb2", C_BOOK },
            { ".flac", "audio/flac", C_AUDIO },            
            { ".flv",  "video/x-flv", C_VIDEO },            
            { ".gif",  "image/gif", C_IMAGE }, 
            { ".gpx",  "application/gpx+xml", C_MARKUP },
            { ".gtar", "application/x-gtar", C_ZIP },
            { ".gz",   "application/x-gzip", C_ZIP },
            { ".htm",  "text/html", C_MARKUP }, 
            { ".html", "text/html", C_MARKUP }, 
            { ".ico",  "image/vnd.microsoft.icon", C_IMAGE },
            { ".ics",  "text/calendar", C_OFFICE },
            { ".img",  "application/x-compressed", C_ZIP },
            { ".jar",  "application/java-archive", C_ZIP },
            { ".java", "text/java", C_TEXT }, 
            { ".jpeg", "image/jpeg", C_IMAGE }, 
            { ".jpg",  "image/jpeg", C_IMAGE },
            { ".js",   "text/javascript", C_TEXT },
            { ".kml",  "application/vnd.google-earth.kml+xml", C_MARKUP },
            { ".kmz",  "application/vnd.google-earth.kmz", C_MARKUP },
            { ".log",  "text/plain", C_TEXT },
            { ".lst",  "text/plain", C_TEXT },
            { ".lzh",  "application/x-lzh", C_ZIP },
            { ".m3u",  "application/x-mpegurl", C_AUDIO }, 
            { ".md5",  "application/x-md5", C_APP },
            { ".mid",  "audio/midi", C_AUDIO }, 
            { ".midi", "audio/midi", C_AUDIO }, 
            { ".mkv",  "video/x-matroska", C_VIDEO },
            { ".mobi", "application/x-mobipocket", C_BOOK },
            { ".mov",  "video/quicktime", C_VIDEO },
            { ".mp2",  "video/mpeg", C_VIDEO },
            { ".mp3",  "audio/mpeg", C_AUDIO },
            { ".mp4",  "video/mp4", C_VIDEO },
            { ".mpeg", "video/mpeg", C_VIDEO }, 
            { ".mpg",  "video/mpeg", C_VIDEO }, 
            { ".mpv",  "video/x-matroska", C_VIDEO },
            { ".odex", "application/octet-stream", C_DROID },
            { ".odg",  "application/vnd.oasis.opendocument.graphics", C_OFFICE },
            { ".odi",  "application/vnd.oasis.opendocument.image", C_OFFICE },
            { ".odp",  "application/vnd.oasis.opendocument.presentation", C_OFFICE },
            { ".ods",  "application/vnd.oasis.opendocument.spreadsheet", C_OFFICE },
            { ".odt",  "application/vnd.oasis.opendocument.text", C_OFFICE },
            { ".oga",  "audio/ogg", C_AUDIO }, 
            { ".ogg",  "audio/ogg", C_AUDIO },    // RFC 5334 
            { ".ogv",  "video/ogg", C_VIDEO },    // RFC 5334 
            { ".ogx",  "application/ogg", C_APP },
            { ".opml", "text/xml", C_MARKUP },
            { ".otf",  "font/otf", C_APP },
            { ".ovpn", "application/x-openvpn-profile", C_APP },
            { ".pdf",  "application/pdf", C_PDF },
            { ".pfx",  "application/x-pkcs12", C_APP },
            { ".php",  "text/php", C_MARKUP },
            { ".pmd",  "application/x-pmd", C_OFFICE },   //      PlanMaker Spreadsheet
            { ".png",  "image/png", C_IMAGE }, 
            { ".ppt",  "application/vnd.ms-powerpoint", C_OFFICE }, 
            { ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", C_OFFICE },
            { ".prd",  "application/x-prd", C_OFFICE },   //      SoftMaker Presentations Document
            { ".ra",   "audio/x-pn-realaudio", C_AUDIO }, 
            { ".ram",  "audio/x-pn-realaudio", C_AUDIO },
            { ".rar",  "application/x-rar-compressed", C_ZIP }, 
            { ".rtf",  "application/rtf", C_OFFICE }, 
            { ".sh",   "application/x-sh", C_APP },
            { ".so",   "application/octet-stream", C_APP },
            { ".sqlite","application/x-sqlite3", C_APP },
            { ".svg",  "image/svg+xml", C_IMAGE },
            { ".swf",  "application/x-shockwave-flash", C_VIDEO }, 
            { ".sxw",  "application/vnd.sun.xml.writer", C_OFFICE }, 
            { ".tar",  "application/x-tar", C_ZIP }, 
            { ".tcl",  "application/x-tcl", C_APP },
            { ".tcx",  "text/troff", C_MARKUP },        // Training Center XML
            { ".tgz",  "application/x-gzip", C_ZIP },
            { ".tif",  "image/tiff", C_IMAGE }, 
            { ".tiff", "image/tiff", C_IMAGE }, 
            { ".tmd",  "application/x-tmd", C_OFFICE }, // TextMaker Document
            { ".txt",  "text/plain", C_TEXT },
            { ".vcf",  "text/x-vcard", C_OFFICE }, 
            { ".wav",  "audio/x-wav", C_AUDIO },
            { ".weba", "audio/webm", C_AUDIO },
            { ".webm", "video/webm", C_VIDEO },
            { ".webp", "image/webp", C_IMAGE },
            { ".wma",  "audio/x-ms-wma", C_AUDIO },
            { ".wmv",  "video/x-ms-wmv", C_VIDEO }, 
            { ".xapk", "application/xapk", C_DROID },
            { ".xhtml","application/xhtml+xml", C_MARKUP },
            { ".xls",  "application/vnd.ms-excel", C_OFFICE },
            { ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", C_OFFICE },
            { ".xml",  "text/xml", C_MARKUP },
            { ".xsl",  "text/xml", C_MARKUP },
            { ".zip",  "application/zip", C_ZIP } 
        };

    private static String lastExt, lastMime;

    public static String getMimeByExt( String ext ) {
        if( !str( ext ) )
            return MIME_ALL;
        synchronized( Utils.class ) {
            if( ext.equals( lastExt ) )
                return lastMime;
        }
        String[] descr = getTypeDescrByExt( ext );
        if( descr != null ) return descr[1];
        // ask the system
        MimeTypeMap mime_map = MimeTypeMap.getSingleton();
        if( mime_map != null ) {
            String mime = mime_map.getMimeTypeFromExtension( ext.substring( 1 ) );
            if( str( mime ) ) {
                synchronized( Utils.class ) {
                    lastExt = ext;
                    lastMime = mime;
                }
                return mime;
            }
        }
        return MIME_ALL;
    }

    public static String getCategoryByExt( String ext ) {
        if( !str( ext ) ) return C_UNKNOWN;
        String[] descr = getTypeDescrByExt( ext );
        if( descr != null ) return descr[2];
        // ask the system
        MimeTypeMap mime_map = MimeTypeMap.getSingleton();
        if( mime_map == null ) return C_UNKNOWN;
        String mime = mime_map.getMimeTypeFromExtension( ext.substring( 1 ) );
        if( !str( mime ) ) return C_UNKNOWN;
        int sl_pos = mime.indexOf( '/' );
        if( sl_pos <= 0 ) return C_UNKNOWN;
        String type = mime.substring( 0, sl_pos );
        if( type.compareTo( "text" ) == 0 )
            return C_TEXT;
        if( type.compareTo( "image" ) == 0 )
            return C_IMAGE;
        if( type.compareTo( "audio" ) == 0 )
            return C_AUDIO;
        if( type.compareTo( "video" ) == 0 )
            return C_VIDEO;
        if( type.compareTo( "application" ) == 0 )
            return C_APP;
        return C_UNKNOWN;
    }

    public static String[] getExtsByCategory( String c ) {
        if( c == null ) return null;
        ArrayList<String> exts = new ArrayList<String>();
        for( int l = 0; l < mimes.length; l++ ) {
            if( c.equals( mimes[l][2] ) )
                exts.add( mimes[l][0] );
        }
        String[] ret = new String[exts.size()];
        return exts.toArray( ret );
    }
    
    public static String[] getTypeDescrByExt( String ext ) {
        ext = ext.toLowerCase();
        int from = 0, to = mimes.length;
        for( int l = 0; l < mimes.length; l++ ) {
            int idx = ( to - from ) / 2 + from;
            String tmp = mimes[idx][0];
            if( tmp.compareTo( ext ) == 0 )
                return mimes[idx];
            int cp;
            for( cp = 1;; cp++ ) {
                if( cp >= ext.length() ) {
                    to = idx;
                    break;
                }
                if( cp >= tmp.length() ) {
                    from = idx;
                    break;
                }
                char c0 = ext.charAt( cp );
                char ct = tmp.charAt( cp );
                if( c0 < ct ) {
                    to = idx;
                    break;
                }
                if( c0 > ct ) {
                    from = idx;
                    break;
                }
            }
        }
        return null;
    }

    public static String getFileExt( String file_name ) {
        if( file_name == null )
            return "";
        int dot = file_name.lastIndexOf( "." );
        return dot >= 0 ? file_name.substring( dot ) : "";
    }

    public static int deleteDirContent( File d ) {
        int cnt = 0;
        File[] fl = d.listFiles();
        if( fl != null ) {
            for( File f : fl ) {
                if( f.isDirectory() )
                    cnt += deleteDirContent( f );
                if( f.delete() )
                    cnt++;
            }
        }
        return cnt;
    }

    public static File[] getListOfFiles( String[] uris ) {
        File[] list = new File[uris.length];
        for( int i = 0; i < uris.length; i++ ) {
            if( uris[i] == null )
                return null;
            list[i] = new File( uris[i] );
        }
        return list;
    }

    public static String getSecondaryStorage() {
        try {
            Map<String, String> env = System.getenv();
            String sec_storage = env.get( "SECONDARY_STORAGE" );
            if( !Utils.str( sec_storage ) ) return null;
            String[] ss = sec_storage.split( ":" );
            for( int i = 0; i < ss.length; i++ )
                if( ss[i].toLowerCase().indexOf( "sd" ) > 0 )
                    return ss[i];
            return "";
        } catch( Exception e ) {
        }
        return null;
    }

    public static boolean hasPermanentMenuKey( Context ctx ) {
        return ViewConfiguration.get( ctx ).hasPermanentMenuKey();
    }

    public enum PubPathType {
        DOWNLOADS,
        DCIM,
        PICTURES,
        MUSIC,
        MOVIES          
    }
    
    public static String getPath( PubPathType ppt ) {
        String pps = null;
        switch( ppt ) {
        case DOWNLOADS: pps =  Environment.DIRECTORY_DOWNLOADS; break;
        case DCIM:      pps =  Environment.DIRECTORY_DCIM; break;
        case PICTURES:  pps =  Environment.DIRECTORY_PICTURES; break;
        case MUSIC:     pps =  Environment.DIRECTORY_MUSIC; break;
        case MOVIES:    pps =  Environment.DIRECTORY_MOVIES;
        }
        File dir_file = Environment.getExternalStoragePublicDirectory( pps );
        return dir_file.canRead() ? dir_file.getAbsolutePath() : null;
    }

    public static void setFullPermissions( File file ) {
        file.setWritable( true, false );
        file.setReadable( true, false );
    }
    
    public static String getOpReport_( Context ctx, int total, int verb_id ) {
        String items = null;
        if( total > 1 ) {
            if( total < 5 )
                items = ctx.getString( R.string.items24 );
            if( items == null || items.length() == 0 )
                items = ctx.getString( R.string.items );
            if( items == null || items.length() == 0 )
                items = ctx.getString( R.string.item );
        }
        String verb = ctx.getString( verb_id );
        String report = ( total > 0 ? "" + total + " " + ( total > 1 ? items : ctx.getString( R.string.item ) ) : ctx
                .getString( R.string.nothing ) )
                + " "
                + ( total > 1 ? ctx.getString( R.string.were ) : ctx.getString( R.string.was ) )
                + " "
                + verb
                + ( total > 1 ? ctx.getString( R.string.verb_plural_sfx ) : "" ) + ".";
        return report;
    }

    @SuppressLint("StringFormatInvalid")
    public static String getOpReport( Context ctx, int total, int verb_id ) {
        String verb = " " + ctx.getString( verb_id );
        if( total == 0 )
            return ctx.getString( R.string.report_0 ) + verb;
        if( total == 1 )
            return ctx.getString( R.string.report_1 ) + verb;
        if( total > 1 ) {
            verb += ctx.getString( R.string.verb_plural_sfx );
            if( total < 5 ) {
                String rep24 = ctx.getString( R.string.report_24, total );
                if( str( rep24 ) && !"\u00A0".equals( rep24 ) ) 
                    return rep24 + verb;
            }
            return ctx.getString( R.string.report_m, total ) + verb;
        }
        return null;
    }

    public static String getNItems( Context ctx, int counter ) {
        String items = null;
        if( counter < 5 && counter > 1 )
            items = ctx.getString( R.string.items24 );
        if( items == null || items.length() == 0 || "\u00A0".equals( items ) )
            items = ctx.getString( R.string.items );
        return  "" + counter + " " + items;
    }

    static final char[] spaces = { '\u00A0', '\u00A0', '\u00A0', '\u00A0', '\u00A0', '\u00A0', '\u00A0', '\u00A0' }; 
    
    public static String getHumanSize( long sz ) {
        return getHumanSize( sz, true );
    }

    public static String getHumanSize( long sz, boolean prepend_nbsp ) {
        try {
            String s;
            if( sz > 1073741824 )
                s = Math.round( sz * 10 / 1073741824. ) / 10. + "G";
            else if( sz > 1048576 )
                s = Math.round( sz * 10 / 1048576. ) / 10. + "M";
            else if( sz > 1024 )
                s = Math.round( sz * 10 / 1024. ) / 10. + "K";
            else
                s = "" + sz + " ";
            if( prepend_nbsp )
                return new String( spaces, 0, 8 - s.length() ) + s;
            else
                return s;
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return "" + sz + " ";
    }

    public static long parseHumanSize( String s ) {
        if( s == null )
            return 0;
        final char[] sxs = { 'K', 'M', 'G', 'T' };
        long m = 1024;
        int s_pos;
        s = s.toUpperCase();
        try {
            for( int i = 0; i < 4; i++ ) {
                s_pos = s.indexOf( sxs[i] );
                if( s_pos > 0 ) {
                    float v = Float.parseFloat( s.substring( 0, s_pos ) );
                    return (long)( v * m );
                }
                m *= 1024;
            }
            s_pos = s.indexOf( 'B' );
            return Long.parseLong( s_pos > 0 ? s.substring( 0, s_pos ) : s );
        } catch( NumberFormatException e ) {
            e.printStackTrace();
        }
        return 0;
    }

    public static String encodeToAuthority( String serv ) {
        String auth = null;
        int cp = serv.lastIndexOf( ':' );
        if( cp > 0 ) {
            String ps = serv.substring( cp + 1 );
            try {
                int port = Integer.parseInt( ps );
                if( port > 0 )
                    auth = Uri.encode( serv.substring( 0, cp ) ) + ":" + port;
            } catch( NumberFormatException e ) {
            }
        }
        if( auth == null )
            auth = Uri.encode( serv );
        return auth;
    }

    public static Uri getUriWithAuth( Uri u, Credentials crd ) {
        return crd != null ? getUriWithAuth( u, crd.getUserName(), crd.getPassword() ) : u;
    }

    public static Uri getUriWithAuth( Uri u, String un, String pw ) {
        if( un == null ) return u;
        String ui = Utils.escapeName( un );
        if( pw != null )
            ui += ":" + Utils.escapeName( pw );
        return updateUserInfo( u, ui );
    }
    
    public static Uri updateUserInfo( Uri u, String encoded_ui ) {
        if( u == null ) return null;
        String ea = u.getEncodedAuthority();
        if( ea == null ) return u;
        int at_pos = ea.lastIndexOf( '@' );
        if( encoded_ui == null ) {
            if( at_pos < 0 ) return u;
            ea = ea.substring( at_pos + 1 );
        } else
            ea = encoded_ui + ( at_pos < 0 ? "@" + ea : ea.substring( at_pos ) );
        return u.buildUpon().encodedAuthority( ea ).build();
    }

    public static String mbAddSl( String path ) {
        if( !str( path ) )
            return "/"; // XXX returning a slash here seems more logical, but are there any pitfalls?
        return path.charAt( path.length() - 1 ) == '/' ? path : path + "/";
    }

    public static Uri addTrailingSlash( Uri u ) {
        String alt_path, path = u.getEncodedPath();
        if( path == null )
            alt_path = "/";
        else {
            alt_path = Utils.mbAddSl( path );
            if( alt_path == null || path.equals( alt_path ) ) return u;
        }
        return u.buildUpon().encodedPath( alt_path ).build(); 
    }
    
    public static boolean str( String s ) {
        return s != null && s.length() > 0;
    }
    
    public static boolean equals( String s1, String s2 ) {
        if( s1 == null ) return s2 == null ? true : false;
        return s1.equals( s2 );
    }
    
    public static String join( String[] a, String sep ) {
        return joinEx( a, sep, 0 );
    }

    public static String joinEx( String[] a, String sep, int from ) {
        if( a == null )
            return "";
        StringBuffer buf = new StringBuffer( 256 );
        boolean first = true;
        for( int i = from; i < a.length; i++ ) {
            if( first )
                first = false;
            else if( sep != null )
                buf.append( sep );
            buf.append( a[i] );
        }
        return buf.toString();
    }

    private static String systemLocaleLang = Locale.getDefault().getLanguage();

    public static boolean isLanguageChanged() {
        return systemLocaleLang == null || !systemLocaleLang.equalsIgnoreCase( Locale.getDefault().getLanguage() );
    }

    public static void changeLanguage( Context c ) {
        changeLanguage( c, c.getResources() );
    }

    public static void changeLanguage( Context c, Resources r ) {
        try {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( c );
            String lang = sharedPref.getString( "language", "" );

            Locale locale;
            if( lang.length() == 0 ) {
                locale = new Locale( systemLocaleLang );
            } else {
                String country = lang.length() > 3 ? lang.substring( 3 ) : null;
                if( country != null )
                    locale = new Locale( lang.substring( 0, 2 ), country );
                else
                    locale = new Locale( lang );
            }
            Locale.setDefault( locale );
            Configuration config = new Configuration();
            config.locale = locale;
            r.updateConfiguration( config, null );
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }
    
    public static int getThemeId( String code ) {
        return "l".equals( code ) ? R.style.GCLight : R.style.GCDark;
    }

    @SuppressLint("InlinedApi")
    public static void setTheme( Context ctx, String code ) {
        ctx.setTheme( getThemeId( code ) );
    }

    public static void setDialogTheme( Context ctx, String code ) {
        int t_id = 0;
        if( "l".equals( code ) ) {
            t_id = R.style.AsDialogLight;
        } else {
            t_id = R.style.AsDialog;
        }
        ctx.setTheme( t_id );
    }
    
    public static CharSequence readStreamToBuffer( InputStream is, String encoding ) {
        if( is != null ) {
            try {
                int bytes = is.available();
                if( bytes < 1024  )
                    bytes = 1024;
                if( bytes > 1048576 )
                    bytes = 1048576;
                char[] chars = new char[bytes];
                InputStreamReader isr = null;
                if( str( encoding ) ) {
                    try {
                        isr = new InputStreamReader( is, encoding );
                    } catch( UnsupportedEncodingException e ) {
                        Log.w( "GC", encoding, e );
                        isr = new InputStreamReader( is );
                    }
                } else
                    isr = new InputStreamReader( is );
                StringBuffer sb = new StringBuffer( bytes );
                int n = -1;
                boolean available_supported = is.available() > 0;
                while( true ) {
                    n = isr.read( chars, 0, bytes );
                    // Log.v( "readStreamToBuffer", "Have read " + n + " chars" );
                    if( n < 0 )
                        break;
                    for( int i = 0; i < n; i++ ) {
                        if( chars[i] == 0x0D )
                            chars[i] = ' ';
                    }
                    sb.append( chars, 0, n );
                    if( available_supported ) {
                        for( int i = 1; i < 5; i++ ) {
                            if( is.available() > 0 )
                                break;
                            // Log.v( "readStreamToBuffer", "Waiting the rest " + i );
                            Thread.sleep( 10 * i );
                        }
                        if( is.available() == 0 ) {
                            // Log.v( "readStreamToBuffer", "No more data!" );
                            break;
                        }
                    }
                }
                return sb;
            } catch( Throwable e ) {
                Log.e( "Utils", "Error on reading a stream", e );
            }
        }
        return null;
    }

    public static boolean copyBytes( InputStream is, OutputStream os ) {
        try {
            byte[] buf = new byte[65536];
            int n;
            while( ( n = is.read( buf ) ) != -1 ) {
                os.write( buf, 0, n );
                Thread.sleep( 1 );
            }
            return true;
        } catch( Exception e ) {
            Log.e( "Utils.copyBytes", "Exception: " + e );
        }
        return false;
    }

    public static File getTempDir( Context ctx ) {
        File parent_dir = ctx.getExternalFilesDir( null );
        if( parent_dir == null )
            parent_dir = new File( Environment.getExternalStorageDirectory().getAbsolutePath() );
        File temp_dir = new File( parent_dir, "/temp/" );
        temp_dir.mkdirs();
        return temp_dir;
    }
    
    public static File createTempDir( Context ctx ) {
        Date d = new Date();
        File parent_dir = null;
        parent_dir = ctx.getExternalFilesDir( null );
        if( parent_dir == null )
             parent_dir = new File( Environment.getExternalStorageDirectory().getAbsolutePath() );
        File temp_dir = new File( parent_dir, "/temp/gc_" + d.getHours() + d.getMinutes() + d.getSeconds() + "/" );
        temp_dir.mkdirs();
        return temp_dir;
    }

    public static void cleanTempDirs( Context ctx ) {
        try {
            File temp_dir = getTempDir( ctx );
            File[] ff = temp_dir.listFiles();
            for( File f : ff ) {
                if( "gc_".equals( f.getName().substring( 0, 3 ) ) ) {
                    deleteDirContent( f );
                    f.deleteOnExit();
                }
            }
        } catch( Exception e ) {
            Log.e( "GC", "", e );
        }
    }

    public static Pair<Uri, String> getOpenableUri( Context ctx, CommanderAdapter ca, int pos, boolean to_open, boolean immediate ) {
        if( ca == null ) return null;
        CommanderAdapter.Item item = (CommanderAdapter.Item)((Adapter)ca).getItem( pos );
        if( item.dir ) return null;

        SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( ctx );
        boolean use_content = shared_pref.getBoolean( to_open ? "open_content" : "open_content",
                android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.M );

        String fn = item.name;
        Uri uri = null;
        if( ca instanceof SAFAdapter ) {
            SAFAdapter safa = (SAFAdapter)ca;
            uri = safa.getItemOpenableUri( pos, !use_content );
            return new Pair<Uri, String>( uri, fn );
        }
        if( ca.hasFeature( CommanderAdapter.Feature.FS ) ) {
            String full_name = ca.getItemName( pos, true );
            if( full_name == null )
                return null;
            uri = use_content ? FileProvider.makeURI( full_name ) :
                                Uri.parse( "file://" + Utils.escapePath( full_name ) );
            return new Pair<Uri, String>( uri, fn );
        }
        Uri ca_uri = ca.getUri();
        if( ca_uri != null && ContentResolver.SCHEME_CONTENT.equals( ca_uri.getScheme() ) ) {
            return new Pair<Uri, String>( ca.getItemUri( pos ), fn );
        }
        if( item.uri == null )
            item.uri = ca.getItemUri( pos );
        Credentials crd = ca.getCredentials();
        if( crd != null ) {
            if( !Utils.str( item.uri.getUserInfo() ) )
                item.uri = Utils.getUriWithAuth( item.uri, crd.getUserName(), null );
            StreamProvider.storeCredentials( ctx, crd, item.uri );
        }
        if( item.mime == null )
            item.mime = Utils.getMimeByExt( Utils.getFileExt( fn ) );
        Uri content_uri = immediate ? StreamProvider.put( item.uri, item ) :
                                      StreamProvider.getUri( item.uri, item );
        return new Pair<Uri, String>( content_uri, fn );
    }
    
    
    
    public static int ENC_DESC_MODE_NUMB  = 0;
    public static int ENC_DESC_MODE_BRIEF = 1;
    public static int ENC_DESC_MODE_FULL  = 2;

    public static String getEncodingDescr( Context ctx, String enc_name, int mode ) {
        if( enc_name == null )
            enc_name = "";
        Resources res = ctx.getResources();
        if( res == null )
            return null;
        String[] enc_dsc_arr = res.getStringArray( R.array.encoding );
        String[] enc_nms_arr = res.getStringArray( R.array.encoding_vals );
        try {
            for( int i = 0; i < enc_nms_arr.length; i++ ) {
                if( enc_name.equalsIgnoreCase( enc_nms_arr[i] ) ) {
                    if( mode == ENC_DESC_MODE_NUMB )
                        return String.valueOf( i );
                    String enc_desc = enc_dsc_arr[i];
                    if( mode == ENC_DESC_MODE_FULL )
                        return enc_desc;
                    else {
                        int nlp = enc_desc.indexOf( '\n' );
                        if( nlp < 0 )
                            return enc_desc;
                        return enc_desc.substring( 0, nlp );
                    }

                }
            }
        } catch( Exception e ) {
        }
        return null;
    }

    public static String escapeRest( String s ) {
        if( !str( s ) )
            return s;
        return s.replaceAll( "%", "%25" )
                .replaceAll( "#", "%23" )
                .replaceAll( "\\?", "%3F" )
                .replaceAll( ":", "%3A" );
    }

    public static String escapePath( String s ) {
        if( !str( s ) )
            return s;
        return escapeRest( s ).replaceAll( "@", "%40" );
    }

    public static String escapeName( String s ) {
        if( !str( s ) )
            return s;
        return escapePath( s ).replaceAll( "/", "%2F" );
    }

    public static String unEscape( String s ) {
        UrlQuerySanitizer urlqs = new UrlQuerySanitizer();
        return urlqs.unescape( s.replaceAll( "\\+", "`2B`" ) ).replaceAll( "`2B`", "+" );
    }

    public static boolean isHTML( String s ) {
        return s.indexOf( "</", 3 ) > 0 ||
               s.indexOf( "/>", 3 ) > 0 ||
               s.indexOf( "&#x" ) >= 0;
    }
    
    public static byte[] hexStringToBytes( String hexString ) {
        int len = hexString.length() / 2;
        byte[] result = new byte[len];
        for( int i = 0; i < len; i++ )
            result[i] = Integer.valueOf( hexString.substring( 2 * i, 2 * i + 2 ), 16 ).byteValue();
        return result;
    }

    private static String HEX = "0123456789abcdef";

    public static String toHexString( byte[] buf, String delim ) {
        if( buf == null )
            return "";
        StringBuffer result = new StringBuffer( 2 * buf.length );
        for( int i = 0; i < buf.length; i++ ) {
            if( i > 0 && str( delim ) )
                result.append( delim );
            result.append( HEX.charAt( ( buf[i] >> 4 ) & 0x0f ) ).append( HEX.charAt( buf[i] & 0x0f ) );
        }
        return result.toString();
    }

    public static String[] getHash( File f, String[] algorithms ) {
        FileInputStream in = null;
        try {
            in = new FileInputStream( f );
            String[] hashes = getHash( in, algorithms );
            in.close();
            return hashes;
        } catch( Throwable e ) {
            Log.e( "getHash", f != null ? f.getAbsolutePath() : "?", e );
        }
        return null;
    }

    public static String[] getHash( InputStream in, String[] algorithms ) {
        try {
            MessageDigest[] digesters = new MessageDigest[algorithms.length];
            for( int i = 0; i < algorithms.length; i++ )
                digesters[i] = MessageDigest.getInstance( algorithms[i] );
            byte[] bytes = new byte[8192];
            int byteCount;
            while((byteCount = in.read(bytes)) > 0) {
                for( int i = 0; i < digesters.length; i++ )
                    digesters[i].update( bytes, 0, byteCount );
            }
            String[] hashes = new String[digesters.length];
            for( int i = 0; i < digesters.length; i++ ) {
                byte[] digest = digesters[i].digest();
                hashes[i] = toHexString( digest, null ); 
            }
            return hashes;
        } catch( Exception e ) {
            Log.e( "getHash", "", e );
            return null;
        }
    }    
    
    public static float getBrightness( int color ) {
        float[] hsv = new float[3];
        Color.colorToHSV( color, hsv );
        return hsv[2];
    }

    public static int setBrightness( int color, float new_brightness ) {
        float[] hsv = new float[3];
        Color.colorToHSV( color, hsv );
        hsv[2] = new_brightness;
        return Color.HSVToColor( hsv );
    }

    public static int shiftBrightness( int color, float drop_to ) {
        float[] hsv = new float[3];
        Color.colorToHSV( color, hsv );
        hsv[2] *= drop_to;
        return Color.HSVToColor( hsv );
    }

    public static int altBrightness( int color, float by ) {
        float[] hsv = new float[3];
        Color.colorToHSV( color, hsv );
        int sig = hsv[2] < 0.3 ? 1 : -1;
        hsv[2] += sig * by;
        return Color.HSVToColor( hsv );
    }

    // Text color for a panel header (path/description/status/sorting) depending
    // on whether that panel currently has focus. Shared by Panels.highlightTitle()
    // and ListHelper.setFocused() so both sides of the merged path+status bar
    // always agree on the same color.
    public static int getFocusTextColor( ColorsKeeper ck, boolean focused ) {
        return focused ? ck.sfgColor : ck.ufgColor;
    }

    public static GradientDrawable getShadingEx( int color, float drop ) {
        try {
            int[] cc = new int[2];
            cc[0] = color;
            cc[1] = shiftBrightness( color, drop );
            return new GradientDrawable( GradientDrawable.Orientation.TOP_BOTTOM, cc );
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }

    public static int getCount( SparseBooleanArray cis ) {
        int counter = 0;
        for( int i = 0; i < cis.size(); i++ )
            if( cis.valueAt( i ) ) {
                counter++;
            }
        return counter;
    }

    public static int getPosition( SparseBooleanArray cis, int of_item ) {
        int counter = 0;
        for( int i = 0; i < cis.size(); i++ )
            if( cis.valueAt( i ) ) {
                if( counter++ == of_item )
                    return cis.keyAt( i );
            }
        return -1;
    }
   
    public static String getCause( Throwable e ) {
        Throwable c = e.getCause();
        if( c != null ) {
            String s = c.getLocalizedMessage();
            String cs = getCause( c );
            if( cs != null )
                return s + "\n" + cs;
            else
                return s;
        }
        return null;        
    }
 
    public static String formatDate( Date date, Context ctx ) {
        if( date == null ) return null;
        boolean no_time = date.getHours() == 0 && date.getMinutes() == 0 && date.getSeconds() == 0;
        if( Locale.getDefault().getLanguage().compareTo( "en" ) != 0 ) {
            java.text.DateFormat locale_date_format = DateFormat.getDateFormat( ctx );
            String ret = locale_date_format.format( date );
            if( !no_time ) {
                java.text.DateFormat locale_time_format = DateFormat.getTimeFormat( ctx );
                ret += " " + locale_time_format.format( date ); 
            }
            return ret;
        } else {
            String fmt = "MMM dd yyyy";
            if( !no_time ) fmt += " HH:mm:ss";
            return (String)DateFormat.format( fmt, date );
        }
    }

    /*
     hasSoftKeys() is not in use anymore.
     Soft bar won't show the menu dots since 
     the target SDK in the manifest is raised over 13 
     to favor the android N's context menu's bug
     */
    // http://stackoverflow.com/questions/14853039/how-to-tell-whether-an-android-device-has-hard-keys
    /*
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static boolean hasSoftKeys( Activity c ) {
        boolean hasSoftwareKeys = true;
    
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ){
            Display d = c.getWindowManager().getDefaultDisplay();
    
            DisplayMetrics realDisplayMetrics = new DisplayMetrics();
            d.getRealMetrics( realDisplayMetrics );
    
            int realHeight = realDisplayMetrics.heightPixels;
            int realWidth = realDisplayMetrics.widthPixels;
    
            DisplayMetrics displayMetrics = new DisplayMetrics();
            d.getMetrics(displayMetrics);
    
            int displayHeight = displayMetrics.heightPixels;
            int displayWidth  = displayMetrics.widthPixels;
    
            hasSoftwareKeys = (realWidth - displayWidth) > 0 || (realHeight - displayHeight) > 0;
        } else {
            boolean hasMenuKey = ViewConfiguration.get(c).hasPermanentMenuKey();
            boolean hasBackKey = KeyCharacterMap.deviceHasKey( KeyEvent.KEYCODE_BACK );
            hasSoftwareKeys = !hasMenuKey && !hasBackKey;
        }
        return hasSoftwareKeys;
    }    
*/
    
    public static boolean needActionBar( Activity a ) {
        /*
         hasSoftKeys( this ) is not in use anymore.
         Soft bar won't show the menu dots since 
         the target SDK in the manifest is raised over 13 
         to favor the android N's context menu's bug
         */
        boolean ab = false;
        try {
            final int size_class = ( a.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK );
            SharedPreferences shared_pref = PreferenceManager.getDefaultSharedPreferences( a );
            String show_actionbar = shared_pref.getString( "show_actionbar", "a" );
            if( "a".equals( show_actionbar ) ) {
                if( size_class >= Configuration.SCREENLAYOUT_SIZE_NORMAL || !hasPermanentMenuKey( a ) )
                    ab = true;
            } else
                ab = "y".equals( show_actionbar );
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return ab;
    }    
    
    public static boolean setActionBar( Activity a, boolean no_title_on_small, boolean back ) {
        boolean nab = false;
        try {
            nab = needActionBar( a );
            if( nab ) {
                nab = a.getWindow().requestFeature( Window.FEATURE_ACTION_BAR );
                ActionBar ab = a.getActionBar();
                if( ab == null ) return false;
                ab.setDisplayHomeAsUpEnabled( back );
                ab.setElevation( 1 );
                if( no_title_on_small ) {
                    final int size_class = ( a.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK );
                    if( size_class <= Configuration.SCREENLAYOUT_SIZE_LARGE )
                        ab.setDisplayShowTitleEnabled( false );
                }
            }
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return nab;
    }    

    public static void putStringSet( SharedPreferences.Editor ed, String key, Set<String> vs ) {
        ed.putStringSet( key, vs );
    }

    public static Set<String> getStringSet( SharedPreferences sp, String key ) {
        return sp.getStringSet( key, null );
    }

    public enum RR {
        busy(R.string.busy), 
        copy_err(R.string.copy_err), 
        copied(R.string.copied), 
        moved(R.string.moved), 
        interrupted(R.string.interrupted), 
        uploading(R.string.uploading), 
        fail_del(R.string.fail_del), 
        cant_del(R.string.cant_del), 
        retrieving(R.string.retrieving), 
        deleting(R.string.deleting), 
        not_supported(R.string.not_supported), 
        file_exist(R.string.file_exist), 
        cant_md(R.string.cant_md),
        rename_err(R.string.rename_err),
        sz_folder( R.string.sz_folder),
        sz_dirnum( R.string.sz_dirnum),
        sz_dirsfx_p( R.string.sz_dirsfx_p),
        sz_dirsfx_s( R.string.sz_dirsfx_s),
        sz_file( R.string.sz_file),
        sz_files( R.string.sz_files),
        sz_Nbytes( R.string.sz_Nbytes),
        sz_bytes( R.string.sz_bytes),
        sz_lastmod( R.string.sz_lastmod),
        sz_total( R.string.sz_total),
        too_deep_hierarchy(R.string.too_deep_hierarchy),
        
        failed(R.string.failed),
        ftp_connected(R.string.ftp_connected),
        rtexcept(R.string.rtexcept),
        permissions(R.string.permissions),
        cant_cd(R.string.cant_cd)
        ;
        private int r;

        private RR(int r_) {
            r = r_;
        }

        public int r() {
            return r;
        }
    };

    public static float evalFrac( String f ) {
        try {
            if( !str( f ) )
                return 0f;
            if( f.contains( "/" ) ) {
                String[] parts = f.split( "/" );
                int numerator = Integer.parseInt( parts[0] );
                int denominator = Integer.parseInt( parts[1] );
                if( denominator > 0 )
                    return (float)numerator / denominator;
            } else {
                return Float.parseFloat( f );
            }
        } catch( Exception e ) {
            Log.e( "evalFrac()", f, e );
        }
        return 0;
    }

}