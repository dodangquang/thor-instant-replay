package com.thorqol.shared;

/** A signed server time anchored to device uptime; wall-clock edits cannot extend a trial. */
public final class TrialClock {
    private TrialClock() {}
    public static long now(long server,long issued,long expiry,long baseElapsed,long elapsed,int oldBoot,int currentBoot,String savedProcess,String currentProcess){
        if(server!=issued||issued<=0||expiry<=issued||expiry-issued>86400||baseElapsed<0||elapsed<baseElapsed)throw new SecurityException("Invalid trial time anchor");
        if(currentBoot>=0?currentBoot!=oldBoot:savedProcess==null||!savedProcess.equals(currentProcess))throw new SecurityException("Online check required after restart");
        long seconds=(elapsed-baseElapsed)/1000;
        if(seconds>=expiry-server)throw new SecurityException("Trial lease expired");
        return server+seconds;
    }
}
