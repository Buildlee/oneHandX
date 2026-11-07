package io.buildlee.onehand;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Keep;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Independent Shizuku shell process. Raising a limit never disables native Back itself. */
public final class BudgetGuardUserService extends IBudgetGuard.Stub {
    private static final String KEY="system_gesture_exclusion_limit_dp";
    private final int appUid;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private String owner, original;
    private int target;
    private long deadline, trialDeadline, sequence, retryAfter;
    private boolean confirmed, expired;
    private IBinder client;
    private IBinder.DeathRecipient death;
    @Keep public BudgetGuardUserService(Context context) {
        appUid=context.getApplicationInfo().uid;
        timer.scheduleAtFixedRate(() -> { synchronized(this) {
            if (owner!=null && SystemClock.elapsedRealtime()>=retryAfter && (expired || SystemClock.elapsedRealtime()>=deadline
                    || (!confirmed && SystemClock.elapsedRealtime()>=trialDeadline))) {
                expired=true; if (!tryRestore()) retryAfter=SystemClock.elapsedRealtime()+5000;
            }
        }},500,500,TimeUnit.MILLISECONDS);
    }
    private void caller() {
        if (Process.myUid()!=2000 || Binder.getCallingUid()!=appUid) throw new SecurityException("Unsupported caller");
    }
    private String command(String... args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("系统命令超时"); }
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        byte[] buffer=new byte[1024]; int count;
        while ((count=process.getInputStream().read(buffer))!=-1) bytes.write(buffer,0,count);
        String output=bytes.toString("UTF-8").trim();
        if (process.exitValue()!=0) throw new IllegalStateException("系统拒绝临时额度操作");
        return output;
    }
    private boolean valid(String value) { return value==null || value.matches("[0-9]{1,5}"); }
    private String read() throws Exception {
        String value=command("/system/bin/device_config","get","android",KEY);
        if ("null".equals(value)) return null;
        if (!valid(value)) throw new IllegalStateException("系统额度读取失败");
        return value;
    }
    private void write(String value) throws Exception {
        if (value==null) command("/system/bin/device_config","delete","android",KEY);
        else command("/system/bin/device_config","put","android",KEY,value);
    }
    @Override public synchronized String readBudget() { caller(); try { return read(); } catch(Exception e) { throw new IllegalStateException("无法读取系统额度",e); } }
    @Override public synchronized boolean start(String id,String baseline,int requested,IBinder token) {
        caller();
        if (owner!=null || id==null || !id.matches("[a-zA-Z0-9-]{1,80}") || !valid(baseline)
                || requested<200 || requested>2000 || token==null) return false;
        try {
            if (!Objects.equals(read(),baseline)) return false;
            if (baseline!=null && Integer.parseInt(baseline)>requested) return false;
            owner=id; original=baseline; target=requested; client=token; confirmed=false; expired=false; sequence=0; retryAfter=0;
            deadline=SystemClock.elapsedRealtime()+12000; trialDeadline=deadline+60000;
            death=() -> { synchronized(this) { if (Objects.equals(owner,id)) { expired=true; tryRestore(); } } };
            client.linkToDeath(death,0);
            write(String.valueOf(target));
            if (!String.valueOf(target).equals(read())) throw new IllegalStateException("额度读回不一致");
            boolean applied=false;
            for (int i=0;i<12;i++) {
                String constants=command("/system/bin/dumpsys","window","constants");
                if (constants.contains("system_gesture_exclusion_limit_dp="+target+"\n")
                        || constants.trim().endsWith("system_gesture_exclusion_limit_dp="+target)) { applied=true; break; }
                Thread.sleep(100);
            }
            if (!applied) throw new IllegalStateException("窗口系统未应用额度");
            deadline=SystemClock.elapsedRealtime()+4000; trialDeadline=SystemClock.elapsedRealtime()+60000;
            return true;
        } catch(Exception e) { expired=true; tryRestore(); Log.w("OneHandBudgetGuard","start failed",e); return false; }
    }
    @Override public synchronized boolean renew(String id,long next) {
        caller(); long now=SystemClock.elapsedRealtime();
        if (!Objects.equals(owner,id) || owner==null || expired || next<=sequence || now>=deadline
                || (!confirmed && now>=trialDeadline)) return false;
        sequence=next; deadline=now+4000; return true;
    }
    @Override public synchronized boolean confirm(String id) {
        caller(); if (owner==null || !owner.equals(id) || expired || SystemClock.elapsedRealtime()>=deadline || SystemClock.elapsedRealtime()>=trialDeadline) return false;
        confirmed=true; return true;
    }
    @Override public synchronized boolean restore(String id,String baseline,int requested) {
        caller();
        if (id==null || !id.matches("[a-zA-Z0-9-]{1,80}") || !valid(baseline) || requested<200 || requested>2000) return false;
        if (owner!=null) {
            if (!owner.equals(id) || !Objects.equals(original,baseline) || target!=requested) return false;
            expired=true; return tryRestore();
        }
        try { return restoreValue(baseline,requested); } catch(Exception e) { return false; }
    }
    private boolean restoreValue(String baseline,int requested) throws Exception {
        String current=read();
        if (Objects.equals(current,baseline)) return true;
        if (!String.valueOf(requested).equals(current)) return false; // Preserve unrelated edits.
        write(baseline); return Objects.equals(read(),baseline);
    }
    private boolean tryRestore() {
        if (owner==null) return true;
        try {
            if (!restoreValue(original,target)) return false;
            if (client!=null && death!=null) client.unlinkToDeath(death,0);
            owner=null; client=null; death=null; original=null; target=0; return true;
        } catch(Exception e) { Log.w("OneHandBudgetGuard","restoration pending",e); return false; }
    }
    @Override public synchronized void destroy() { if (tryRestore()) System.exit(0); }
}
