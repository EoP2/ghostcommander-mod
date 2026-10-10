package com.ghostsq.commander;

import android.content.Context;
import android.os.Parcel;

import com.ghostsq.commander.adapters.CommanderAdapter.Item;

public class FilterProps extends Query {
    public boolean include_matched;

    public FilterProps() {
        include_matched = false;
    }

    protected FilterProps( Parcel in ) {
        super( in );
        include_matched = in.readByte() != 0;
    }

    @Override
    public void writeToParcel( Parcel dest, int flags ) {
        super.writeToParcel( dest, flags );
        dest.writeByte( (byte)( include_matched ? 1 : 0 ) );
    }

    public static final Creator<FilterProps> CREATOR = new Creator<FilterProps>() {
        @Override
        public FilterProps createFromParcel( Parcel in ) {
            return new FilterProps( in );
        }

        @Override
        public FilterProps[] newArray( int size ) {
            return new FilterProps[size];
        }
    };

    public boolean isMatched( Item item ) {
        return match( item ) == include_matched;
    }

    public String getString( Context ctx ) {
        return ctx.getString( include_matched ? R.string.show_matched : R.string.hide_matched) +
                ": " + super.getString( ctx );
    }
}
