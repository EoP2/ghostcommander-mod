package com.ghostsq.commander.utils;

import android.util.Log;

import java.util.ArrayList;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class TextSearcher {
    public  final static String TAG = TextSearcher.class.getSimpleName();
    private final SearchSink sink;
    private CharSequence cs;
    private int          textLen;
    private Pattern      searchPattern;
    private boolean      wholeWords;
    private int          strBeg = 0, strEnd = 0;
    private Matcher      curMatcher;
    private ArrayList<MatchResult> findings;
    private int          lastFinding;
    private boolean      lastDown = true;

    public interface SearchSink {
        void found( int beg, int end );
        void error( String s );
    }

    public TextSearcher( SearchSink sink ) {
        this.sink = sink;
    }

    private void reset() {
        curMatcher = null;
        findings = null;
        lastFinding = -1;
    }

    public final void setSearchRegEx( String ss, boolean ignore_case, boolean words ) {
        wholeWords = words;
        try {
            searchPattern = Pattern.compile( ss, ignore_case ? CASE_INSENSITIVE : 0 );
        } catch( java.util.regex.PatternSyntaxException e ) {
            sink.error( e.getMessage() );
        }
        reset();
    }

    public final void setSearchString( String ss, boolean ignore_case, boolean words ) {
        wholeWords = words;
        searchPattern = Pattern.compile( Pattern.quote( ss ), ignore_case ? CASE_INSENSITIVE : 0 );
        reset();
    }

    public final void setTextToSearchIn( CharSequence cs ) {
        this.cs = cs;
        textLen = cs.length();
        reset();
    }

    public final boolean search( int from_pos, boolean down ) {
        reset();
        if( down ) {
            strBeg = from_pos;
            strEnd = textLen;
        } else {
            strBeg = 0;
            strEnd = from_pos;
        }
        textLen = cs.length();
        return search( true, down );
    }

    public final boolean search( boolean continue_last, boolean down ) {
        if( searchPattern == null ) return false;
        if( cs == null ) return false;
        if( !continue_last ) {
            reset();
            strBeg = 0;
            strEnd = textLen;
        }
        lastDown = down;
        if( down ) {
            if( curMatcher != null ) {
                if( searchInString( null, true ) )
                    return true;
                strBeg = strEnd + 1;
            }
            for( int pos = strBeg; pos < textLen; pos++ ) {
                char c = cs.charAt( pos );
                if( !( c == '\n' || pos == textLen - 1 ) ) continue;
                strEnd = pos;
                String s = cs.subSequence( strBeg, strEnd ).toString();
                if( searchInString( s, true ) )
                    return true;
                strBeg = strEnd + 1;
            }
        } else {
            if( curMatcher != null ) {
                if( searchInString( null, false ) )
                    return true;
                strEnd = strBeg - 1;
            }
            for( int pos = strEnd - 1; pos >= 0; pos-- ) {
                char c = cs.charAt( pos );
                if( c == '\n' )
                    strBeg = pos + 1;
                else if( pos == 0 )
                    strBeg = pos;
                else
                    continue;
                String s = cs.subSequence( strBeg, strEnd ).toString();
                if( searchInString( s, false ) )
                    return true;
                strEnd = strBeg - 1;
            }
        }
        return false;
    }

    private boolean searchInString( String s, boolean forward ) {
        try {
            if( s != null ) {
                curMatcher = searchPattern.matcher( s );
                findings = new ArrayList<MatchResult>();
                while( curMatcher.find() ) {
                    if( wholeWords ) {
                        int beg = curMatcher.start();
                        if( beg > 0 && Character.isLetterOrDigit( s.charAt( beg-1 ) ) ) continue;
                        int end = curMatcher.end();
                        if( end < s.length()-1 && Character.isLetterOrDigit( s.charAt( end ) ) ) continue;
                    }
                    findings.add( curMatcher.toMatchResult() );
                }
                lastFinding = forward ? 0 : findings.size()-1;
            }
            else
                lastFinding += forward ? 1 : -1;
            if( lastFinding >= 0 && lastFinding < findings.size() ) {
                MatchResult mr = findings.get( lastFinding );
                sink.found( strBeg + mr.start(), strBeg + mr.end() );
                return true;
            }
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        curMatcher = null;
        findings   = null;
        lastFinding = -1;
        return false;
    }

    public void invalidateCurrentString() {
        curMatcher = null;
    }

    public boolean lastDirectionWasDown() {
        return lastDown;
    }
}
