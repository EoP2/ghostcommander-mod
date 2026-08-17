package com.ghostsq.commander;

import com.ghostsq.commander.adapters.CommanderAdapter;

public abstract class Tools {
    public static int[] getIds() {
        int[] bia = {
            R.id.F1,
            R.id.F2,
            R.id.F3,
            R.id.F4,
            R.id.new_item,
            R.id.F5F6,
            R.id.F8,
            R.id.F9,
            R.id.F10,
            R.id.remount,
            R.id.sz,
            R.id.dirsz,
            R.id.eq,
            R.id.swap,
            R.id.tgl,
            R.id.enter,
            R.id.refresh,
            R.id.hidden,
            R.id.sel_dlg,
            R.id.add_fav,
            R.id.home,
            R.id.favs,
            R.id.sdcard,
            R.id.root,
            R.id.mount,
            R.id.menu,
            R.id.softkbd,
            R.id.search,
            R.id.filter,
            R.id.compare,
            R.id.totop,
            R.id.tobot,
            R.id.action_back,
            R.id.action_sort,
            R.id.action_select,
            R.id.new_zip,
            R.id.send
        };
        return bia;
    }

    public static int getId( String cn ) {
        if( cn == null ) return 0;
        if( cn.equals( "F1"      ) ) return        R.id.F1;
        if( cn.equals( "F2"      ) ) return        R.id.F2;
        if( cn.equals( "F3"      ) ) return        R.id.F3;
        if( cn.equals( "F4"      ) ) return        R.id.F4;
        if( cn.equals( "new_item") ) return        R.id.new_item;
        if( cn.equals( "F5F6"    ) ) return        R.id.F5F6;
        if( cn.equals( "F8"      ) ) return        R.id.F8;
        if( cn.equals( "F9"      ) ) return        R.id.F9;
        if( cn.equals( "F10"     ) ) return        R.id.F10;
        if( cn.equals( "eq"      ) ) return        R.id.eq;
        if( cn.equals( "tgl"     ) ) return        R.id.tgl;
        if( cn.equals( "sz"      ) ) return        R.id.sz;
        if( cn.equals( "sort"    ) ) return        R.id.action_sort;
        if( cn.equals( "sel_dlg" ) ) return        R.id.sel_dlg;
        if( cn.equals( "enter"   ) ) return        R.id.enter;
        if( cn.equals( "addfav"  ) ) return        R.id.add_fav;
        if( cn.equals( "remount" ) ) return        R.id.remount;
        if( cn.equals( "home"    ) ) return        R.id.home;
        if( cn.equals( "favs"    ) ) return        R.id.favs;
        if( cn.equals( "sdcard"  ) ) return        R.id.sdcard;
        if( cn.equals( "rootb"   ) ) return        R.id.root;
        if( cn.equals( "mount"   ) ) return        R.id.mount;
        if( cn.equals( "hidden"  ) ) return        R.id.hidden;
        if( cn.equals( "refresh" ) ) return        R.id.refresh;
        if( cn.equals( "softkbd" ) ) return        R.id.softkbd;
        if( cn.equals( "search"  ) ) return        R.id.search;
        if( cn.equals( "menu"    ) ) return        R.id.menu;
        if( cn.equals( "totop"   ) ) return        R.id.totop;
        if( cn.equals( "tobot"   ) ) return        R.id.tobot;
        if( cn.equals( "go_up"   ) ) return        R.id.action_back;
        if( cn.equals( "swap"    ) ) return        R.id.swap;
        if( cn.equals( "compare" ) ) return        R.id.compare;
        if( cn.equals( "filter"  ) ) return        R.id.filter;
        if( cn.equals( "dirsz"   ) ) return        R.id.dirsz;
        if( cn.equals( "send"    ) ) return        R.id.send;
        return 0;
    }

    public static CommanderAdapter.Feature getFeature( int id ) {
        if( id == R.id.F1 )           return  CommanderAdapter.Feature.F1;
        if( id == R.id.F2 )           return  CommanderAdapter.Feature.F2;
        if( id == R.id.F3 )           return  CommanderAdapter.Feature.F3;
        if( id == R.id.F4 )           return  CommanderAdapter.Feature.F4;
        if( id == R.id.new_item )     return  CommanderAdapter.Feature.F7;
        if( id == R.id.F5F6 )         return  CommanderAdapter.Feature.F5;
        if( id == R.id.F8 )           return  CommanderAdapter.Feature.F8;
        if( id == R.id.F9 )           return  CommanderAdapter.Feature.F9;
        if( id == R.id.F10 )          return  CommanderAdapter.Feature.F10;
        if( id == R.id.eq )           return  CommanderAdapter.Feature.EQ;
        if( id == R.id.tgl )          return  CommanderAdapter.Feature.TGL;
        if( id == R.id.swap )         return  CommanderAdapter.Feature.TGL;
        if( id == R.id.sz )           return  CommanderAdapter.Feature.SZ;
        if( id == R.id.action_select
         || id == R.id.sel_dlg )      return  CommanderAdapter.Feature.SEL_UNS;
        if( id == R.id.enter )        return  CommanderAdapter.Feature.ENTER;
        if( id == R.id.add_fav )      return  CommanderAdapter.Feature.ADD_FAV;
        if( id == R.id.remount )      return  CommanderAdapter.Feature.REMOUNT;
        if( id == R.id.home )         return  CommanderAdapter.Feature.HOME;
        if( id == R.id.favs )         return  CommanderAdapter.Feature.FAVS;
        if( id == R.id.sdcard )       return  CommanderAdapter.Feature.SDCARD;
        if( id == R.id.root )         return  CommanderAdapter.Feature.ROOT;
        if( id == R.id.mount )        return  CommanderAdapter.Feature.MOUNT;
        if( id == R.id.hidden )       return  CommanderAdapter.Feature.HIDDEN;
        if( id == R.id.refresh )      return  CommanderAdapter.Feature.REFRESH;
        if( id == R.id.softkbd )      return  CommanderAdapter.Feature.SOFTKBD;
        if( id == R.id.search )       return  CommanderAdapter.Feature.SEARCH;
        if( id == R.id.menu )         return  CommanderAdapter.Feature.MENU;
        if( id == R.id.totop
         || id == R.id.tobot
         || id == R.id.action_back )  return  CommanderAdapter.Feature.SCROLL;
        if( id == R.id.action_sort )  return  CommanderAdapter.Feature.SORTING;
        if( id == R.id.compare )      return  CommanderAdapter.Feature.REAL;
        if( id == R.id.filter )       return  CommanderAdapter.Feature.FILTER;
        if( id == R.id.dirsz )        return  CommanderAdapter.Feature.DIRSIZES;
        if( id == R.id.new_zip )      return  CommanderAdapter.Feature.MKZIP;
        if( id == R.id.send )         return  CommanderAdapter.Feature.SEND;
        return null;
    }

    public static String getCodeName( int id ) {
        if( id == R.id.F1 )           return  "F1";
        if( id == R.id.F2 )           return  "F2";
        if( id == R.id.F3 )           return  "F3";
        if( id == R.id.F4 )           return  "F4";
        if( id == R.id.new_item )     return  "new_item";
        if( id == R.id.F5F6 )         return  "F5F6";
        if( id == R.id.F8 )           return  "F8";
        if( id == R.id.F9 )           return  "F9";
        if( id == R.id.F10 )          return  "F10";
        if( id == R.id.eq )           return  "eq";
        if( id == R.id.tgl )          return  "tgl";
        if( id == R.id.sz )           return  "sz";
        if( id == R.id.action_sort )  return  "sort";
        if( id == R.id.sel_dlg )      return  "sel_dlg";
        if( id == R.id.enter )        return  "enter";
        if( id == R.id.add_fav )      return  "addfav";
        if( id == R.id.remount )      return  "remount";
        if( id == R.id.home )         return  "home";
        if( id == R.id.favs )         return  "favs";
        if( id == R.id.sdcard )       return  "sdcard";
        if( id == R.id.root )         return  "rootb";
        if( id == R.id.mount )        return  "mount";
        if( id == R.id.hidden )       return  "hidden";
        if( id == R.id.refresh )      return  "refresh";
        if( id == R.id.softkbd )      return  "softkbd";
        if( id == R.id.search )       return  "search";
        if( id == R.id.menu )         return  "menu";
        if( id == R.id.totop )        return  "totop";
        if( id == R.id.tobot )        return  "tobot";
        if( id == R.id.action_back )  return  "go_up";
        if( id == R.id.swap )         return  "swap";
        if( id == R.id.compare )      return  "compare";
        if( id == R.id.filter )       return  "filter";
        if( id == R.id.dirsz )        return  "dirsz";
        if( id == R.id.send )         return  "send";
        return null;
    }

    public static char getBoundKey( int id ) {
        if( id == R.id.F1 )           return  '1';
        if( id == R.id.F2 )           return  '2';
        if( id == R.id.F3 )           return  '3';
        if( id == R.id.F4 )           return  '4';
        if( id == R.id.new_item )     return  '7';
        if( id == R.id.F5F6 )         return  '5';
        if( id == R.id.F8 )           return  '8';
        if( id == R.id.F9 )           return  '9';
        if( id == R.id.F10 )          return  '0';
        if( id == R.id.eq )           return  '=';
        if( id == R.id.tgl )          return  0;
        if( id == R.id.sz )           return  '"';
        if( id == R.id.sel_dlg )      return  '+';
        if( id == R.id.enter )        return  0;
        if( id == R.id.add_fav )      return  '*';
        if( id == R.id.remount )      return  0;
        if( id == R.id.search )       return  '/';
        if( id == R.id.filter )       return  '&';
        if( id == R.id.swap )         return  '~';
        if( id == R.id.compare )      return  '%';
        if( id == R.id.dirsz )        return  '#';
        return 0;
    }

    public static int getCaptionRId( int id ) {
        if( id == R.id.F1 )           return  R.string.F1;
        if( id == R.id.F2 )           return  R.string.F2;
        if( id == R.id.F3 )           return  R.string.F3;
        if( id == R.id.F4 )           return  R.string.F4;
        if( id == R.id.new_item )     return  R.string.new_item;
        if( id == R.id.F5F6 )         return  R.string.F5F6;
        if( id == R.id.F8 )           return  R.string.F8;
        if( id == R.id.F9 )           return  R.string.F9;
        if( id == R.id.F10 )          return  R.string.F10;
        if( id == R.id.eq )           return  R.string.eq;
        if( id == R.id.tgl )          return  R.string.tgl;
        if( id == R.id.sz )           return  R.string.sz;
        if( id == R.id.action_sort )  return  R.string.sorting;
        if( id == R.id.sel_dlg )      return  R.string.sel_dlg;
        if( id == R.id.enter )        return  R.string.enter_b;
        if( id == R.id.add_fav )      return  R.string.add_fav_b;
        if( id == R.id.remount )      return  R.string.remount_b;
        if( id == R.id.home )         return  R.string.home;
        if( id == R.id.favs )         return  R.string.favs;
        if( id == R.id.sdcard )       return  R.string.sdcard;
        if( id == R.id.root )         return  R.string.root;
        if( id == R.id.mount )        return  R.string.mount_b;
        if( id == R.id.hidden )       return  R.string.hidden;
        if( id == R.id.refresh )      return  R.string.refresh;
        if( id == R.id.softkbd )      return  R.string.softkbd;
        if( id == R.id.search )       return  R.string.search_file;
        if( id == R.id.menu )         return  R.string.menu;
        if( id == R.id.totop )        return  R.string.go_top;
        if( id == R.id.tobot )        return  R.string.go_end;
        if( id == R.id.action_back )  return  R.string.go_up;
        if( id == R.id.swap )         return  R.string.swap;
        if( id == R.id.compare )      return  R.string.compare;
        if( id == R.id.filter )       return  R.string.filter;
        if( id == R.id.dirsz )        return  R.string.dirsz;
        if( id == R.id.send )         return  R.string.send_to;
        return 0;
    }

    public final static boolean getVisibleDefault( int id ) {
        if( id == R.id.F2 )           return  true;
        if( id == R.id.F5F6 )         return  true;
        if( id == R.id.F8 )           return  true;
        if( id == R.id.home )         return  true;
        if( id == R.id.sel_dlg )      return  true;
        return false;
    }

    public final static String getIcon( int id ) {
        if( id == R.id.F1 )           return  "ⓘ";
        if( id == R.id.F2 )           return  "〆";
        if( id == R.id.F3 )           return  "❍";
        if( id == R.id.F4 )           return  "≢";
        if( id == R.id.new_item )     return  "＋";
        if( id == R.id.F5F6 )         return  "❏️";
        if( id == R.id.F8 )           return  "⌫";
        if( id == R.id.F9 )           return  "⚒";
        if( id == R.id.F10 )          return  "⊗";
        if( id == R.id.eq )           return  "⚌";
        if( id == R.id.tgl )          return  "⇌";
        if( id == R.id.sz )           return  "ⓘ";
        if( id == R.id.action_sort )  return  "☷";
        if( id == R.id.sel_dlg )      return  "☑";
        if( id == R.id.enter )        return  "⤴";
        if( id == R.id.add_fav )      return  "✱";
        if( id == R.id.remount )      return  "⏏";
        if( id == R.id.home )         return  "⌂";
        if( id == R.id.favs )         return  "✱";
        if( id == R.id.sdcard )       return  "⚅";
        if( id == R.id.root )         return  "#";
        if( id == R.id.mount )        return  "⏏";
        if( id == R.id.hidden )       return  "◌";
        if( id == R.id.refresh )      return  "↻";
        if( id == R.id.softkbd )      return  "⌨︎";
        if( id == R.id.search )       return  "⌕";
        if( id == R.id.menu )         return  "≡";
        if( id == R.id.totop )        return  "△";
        if( id == R.id.tobot )        return  "▽";
        if( id == R.id.action_back )  return  "⋯";
        if( id == R.id.swap )         return  "⇌";
        if( id == R.id.compare )      return  "⧉️";
        if( id == R.id.filter )       return  "▦";
        if( id == R.id.dirsz )        return  "▤";
        if( id == R.id.send )         return  "❢";
        return null;
    }

}
