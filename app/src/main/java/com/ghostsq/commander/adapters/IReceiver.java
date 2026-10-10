package com.ghostsq.commander.adapters;

import android.net.Uri;

import java.io.Closeable;
import java.io.OutputStream;
import java.util.Date;

/**
 * A Receiver object holds the context of a current destination directory to receive entries into
 * The methods which parameter is an URI assume the item to operate with does exist.
 */
public interface IReceiver {
        /**
         * Prepares to receive a file with a given name
         * @param file_name - the name of the file to receive
         * @return  stream to data be written
         * The caller has to call closeStream() after it's done working with the content
         */
        OutputStream receive( String file_name );

        /**
         * Closes a stream opened by calling the receive() methods
         * @param s - the stream
         */
        void closeStream( Closeable s );

        /**
         * Returns an URI of an existing item
         * @param name - the name of an item
         * @param dir - the item is expected to be a directory
         * @return  URI of an item if it exists, null if it does not
         */
        Uri getItemURI( String name, boolean dir );

        /**
         * Check is an existing item a directory
         * @param item_uri uri of an item to check
         * @return true if it is a directory
         */
        boolean isDirectory( Uri item_uri );
        /**
         * @param new_dir_name - the name of the new directory
         * @return  URI of the new created directory
         */
        Uri makeDirectory( String new_dir_name );

        /**
         * Deletes an item
         * @param item_uri item to delete
         * @return false on a failure
         */
        boolean delete( Uri item_uri );

        /**
         * An attempt to set the original file timestamp to the destination file
         * @param item_uri      an existing item
         * @param timestamp     original timestamp
         * @return      usually false... :(
         */
        boolean setDate( Uri item_uri, Date timestamp );

        /**
         * To be called before destroyng this receiver
         * @return true on a success
         */
        boolean done();

/*
    // implementation template
    public static class Receiver implements IReceiver {
        private final static String TAG = "___Receiver";
        private Uri     mDest;

        Receiver( Uri dir ) {
            mDest = dir;
        }

        private final Uri buildItemUri( String name ) {
            return mDest.buildUpon().appendPath( name ).build();
        }

        @Override
        public OutputStream receive( String fn ) {
            try {
                String escaped_name = Utils.escapePath( fn );
            } catch( Exception e ) {
                Log.e( TAG, fn, e );
            }
            return null;
        }
        @Override
        public void closeStream( Closeable s ) {
            try {
                if( s != null )
                    s.close();
            } catch( IOException e ) {
                Log.e( TAG, "", e );
            }
        }

        @Override
        public Uri getItemURI( String name, boolean dir ) {
            return null;
        }

        @Override
        public boolean isDirectory( Uri item_uri ) {
            return false;
        }

        @Override
        public Uri makeDirectory( String new_dir_name ) {
            try {
                return null;
            } catch( Exception e ) {
                Log.e( TAG, mDest + " / " + new_dir_name, e );
            }
            return null;
        }

        @Override
        public boolean delete( Uri item_uri ) {
            try {
            } catch( Exception e ) {
                Log.e( TAG, item_uri.toString(), e );
            }
            return false;
        }

        @Override
        public boolean setDate( Uri item_uri, Date timestamp ) {
            try {
            } catch( Exception e ) {
                Log.e( TAG, item_uri.toString(), e );
            }
            return false;
        }

        @Override
        public boolean done() {
            return false;
        }
    }
*/
}
