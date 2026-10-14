package com.ghostsq.commander.sftp;

// Result codes of SFTPAdapter.connectAndLogin(): negative means failure
public class SFTPConnection {
    public final static int WAS_IN = 1;
    public final static int LOGGED_IN = 2;
    public final static int NEUTRAL = 0;
    public final static int NO_CONNECT = -1;
    public final static int NO_LOGIN = -2;
}

