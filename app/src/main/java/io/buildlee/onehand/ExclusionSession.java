package io.buildlee.onehand;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;
import io.buildlee.onehand.takeover.TakeoverManager;

/** Explicit, process-local consent; a pending journal never auto-resumes replacement. */
final class ExclusionSession {
    private static ExclusionSession instance;
    private static volatile int activeBudget;
    static int budget() { return activeBudget; }
    static boolean active() { return activeBudget>0; }
    static boolean pending(Context context) { return new BudgetStore(context).pending(); }
    static synchronized ExclusionSession get(Context context) {
        if (instance==null) instance=new ExclusionSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final BudgetStore store;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final IBinder client=new Binder();
    private final Shizuku.UserServiceArgs args;
    private IBudgetGuard guard;
    private boolean binding, working, wanted, confirmed, left, right;
    private volatile long generation;
    private long bindingAttempt;
    private int recoveryConnectAttempts;
    private final Runnable retryConnection=this::connect;
    private String id, recoveryId;
    private long sequence, trialEnd;
    private volatile String status="未开启。需要已启动并授权的 Shizuku shell 服务。";
    private ExclusionSession(Context context) {
        this.context=context; store=new BudgetStore(context);
        args=new Shizuku.UserServiceArgs(new ComponentName(context,BudgetGuardUserService.class))
                .daemon(true).processNameSuffix("budget_guard").tag("onehand-budget-guard").version(2);
        Shizuku.addBinderDeadListener(() -> main.post(() -> { if (active() || wanted || store.pending())
            fail("Shizuku 已断开，手柄已暂停；临时额度将由守护尝试恢复。"); }));
    }
    String status() {
        if (active() && !confirmed) return "试用剩余 "+Math.max(0,(trialEnd-SystemClock.elapsedRealtime())/1000)
                +" 秒 · 左侧 "+(left?"已验证":"待滑动")+" · 右侧 "+(right?"已验证":"待滑动");
        return status;
    }
    boolean canConfirm() { return active() && !confirmed && left && right; }
    boolean confirmed() { return active() && confirmed; }
    boolean busy() { return binding || working; }
    void start() {
        if (active() || binding || working) return;
        EdgeService service=EdgeService.connectedService();
        if (service==null || !new Prefs(context).masterEnabled()) { status="请先开启手势服务和应用总开关。"; return; }
        if (TakeoverManager.get(context).status()!=TakeoverManager.Status.IDLE) {
            status="请先恢复旧版双侧接管，再开始临时替换。"; return;
        }
        main.removeCallbacks(retryConnection);
        ++generation; wanted=true; recoveryId=null; left=false; right=false; confirmed=false;
        connect();
    }
    void recover() {
        HandlePrioritySafety.persistPause(context);
        wanted=false; stopRuntime();
        recoveryConnectAttempts=0;
        EdgeService service=EdgeService.connectedService(); if(service!=null) service.refreshPriorityMode();
        connect();
    }
    /** Independent recovery already paused windows; never cancel a later session. */
    void recover(String expectedId) { main.removeCallbacks(retryConnection); wanted=false; recoveryId=expectedId; recoveryConnectAttempts=0; connect(); }
    private void connect() {
        if (working) return;
        try {
            if (!new io.buildlee.onehand.takeover.ShizukuBridge(context).available()) {
                if (!wanted && store.pending() && recoveryConnectAttempts++<20) {
                    status="正在等待恢复服务连接…";
                    main.removeCallbacks(retryConnection); main.postDelayed(retryConnection,500); return;
                }
                wanted=false; status=store.pending()?"手柄已暂停；恢复额度需要启动并授权 Shizuku。":"请先启动并授权 Shizuku。"; return;
            }
            if (guard!=null) { workAfterBind(); return; }
            if (binding) return;
            binding=true; status="正在连接临时接管服务…";
            final long attempt=++bindingAttempt;
            Shizuku.bindUserService(args,connection);
            main.postDelayed(() -> { if (binding && bindingAttempt==attempt) {
                binding=false; wanted=false;
                try { Shizuku.unbindUserService(args,connection,false); } catch(RuntimeException ignored) {}
                status="服务连接超时，请检查 Shizuku 后重试。";
            } },10000);
        } catch(RuntimeException e) { binding=false; wanted=false; status="Shizuku 服务连接失败。"; }
    }
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if (!binding) return;
            binding=false; guard=IBudgetGuard.Stub.asInterface(binder);
            try { binder.linkToDeath(() -> main.post(() -> { guard=null; fail("额度守护已停止，手柄已暂停；恢复记录已保留。" ); }),0); }
            catch(Exception e) { guard=null; fail("额度守护不可用。"); return; }
            workAfterBind();
        }
        @Override public void onServiceDisconnected(ComponentName name) { guard=null; binding=false; fail("额度守护已断开，手柄已暂停。"); }
    };
    private void workAfterBind() {
        working=true;
        final IBudgetGuard remote=guard;
        final String expectedRecoveryId=recoveryId;
        io.execute(() -> {
            try {
                JSONObject previous=store.read();
                if (previous!=null && expectedRecoveryId!=null && !expectedRecoveryId.equals(previous.getString("id"))) {
                    main.post(() -> { working=false; status="恢复请求已过期，较新的会话未被覆盖。"; }); return;
                }
                if (previous!=null && !restore(remote,previous)) throw new IllegalStateException("上次额度尚未恢复，未开始新会话。");
                main.post(() -> {
                    working=false;
                    if (wanted) begin(remote);
                    else status="临时额度已还原。手柄保持暂停，等待手动重新启用。";
                });
            } catch(Exception e) { main.post(() -> { working=false; wanted=false; status="额度恢复未完成，记录已保留；系统拒绝、权限不足或存在配置冲突。"; }); }
        });
    }
    private void begin(IBudgetGuard remote) {
        if (!wanted) return;
        final String sessionId=UUID.randomUUID().toString();
        final long expectedGeneration=generation;
        working=true;
        final int target=Math.min(2000,Math.max(400,((int)Math.ceil((Math.max(
                context.getResources().getDisplayMetrics().widthPixels,context.getResources().getDisplayMetrics().heightPixels)
                /context.getResources().getDisplayMetrics().density+64)/100))*100));
        status="正在检查并建立临时额度…";
        io.execute(() -> {
            try {
                String original=remote.readBudget();
                store.prepare(sessionId,original,target);
                if (!remote.start(sessionId,original,target,client)) {
                    JSONObject record=store.read();
                    if (record!=null) restore(remote,record);
                    throw new IllegalStateException("系统未批准临时额度");
                }
                main.post(() -> {
                    working=false;
                    if (!wanted || generation!=expectedGeneration || EdgeService.connectedService()==null) {
                        io.execute(() -> { try { JSONObject record=store.read(); if (record!=null && sessionId.equals(record.getString("id"))) restore(remote,record); } catch(Exception ignored) {} });
                        return;
                    }
                    id=sessionId; sequence=0; activeBudget=target; trialEnd=SystemClock.elapsedRealtime()+60000;
                    if (!new Prefs(context).setHandlePriorityEnabled(true)) { fail("无法解除安全暂停，未建立输入窗口。"); return; }
                    status="临时额度已建立，请在左右侧边分别滑动。";
                    main.removeCallbacks(pulse); main.post(pulse);
                });
            } catch(Exception e) {
                android.util.Log.w("OneHandBudget","replacement start failed",e);
                main.post(() -> { working=false; if(generation==expectedGeneration) fail(store.pending()
                        ?"临时接管未能开启，恢复记录已保留；请点恢复额度后重试。":"系统未批准临时接管，未建立替换手柄。"); });
            }
        });
    }
    private final Runnable pulse=new Runnable() {
        public void run() {
            if (!active()) return;
            if ((!confirmed && SystemClock.elapsedRealtime()>=trialEnd) || EdgeService.connectedService()==null
                    || !new Prefs(context).masterEnabled() || HandlePrioritySafety.isPaused(context)) {
                fail("试用结束或服务停止，手柄已暂停。请恢复额度后重新开始。"); return;
            }
            final String sessionId=id; final long next=++sequence; final IBudgetGuard remote=guard;
            io.execute(() -> { try {
                if (remote==null || !remote.renew(sessionId,next)) main.post(() -> { if (sessionId.equals(id)) fail("守护续期失败，手柄已暂停。"); });
            } catch(Exception e) { main.post(() -> { if (sessionId.equals(id)) fail("守护连接异常，手柄已暂停。"); }); } });
            main.postDelayed(this,1000);
        }
    };
    void record(boolean isLeft) { if (active() && !confirmed) { if (isLeft) left=true; else right=true; } }
    boolean trial() { return active() && !confirmed; }
    void confirm() {
        if (!canConfirm()) return;
        final String sessionId=id; final IBudgetGuard remote=guard;
        io.execute(() -> { try {
            boolean ok=remote!=null && remote.confirm(sessionId);
            main.post(() -> { if (!sessionId.equals(id) || !active()) return;
                if (ok) { confirmed=true; status="已确认临时替换。停止应用、手动恢复或故障时交还系统侧边返回。"; }
                else fail("试用已失效，手柄已暂停。"); });
        } catch(Exception e) { main.post(() -> { if(sessionId.equals(id)) fail("确认失败，手柄已暂停。"); }); } });
    }
    private boolean restore(IBudgetGuard remote,JSONObject record) throws Exception {
        String sessionId=record.getString("id");
        if (!remote.restore(sessionId,BudgetStore.original(record),record.getInt("target"))) return false;
        store.clear(sessionId); return true;
    }
    /** Called on the input main thread before detaching all windows. */
    void stopRuntime() {
        final String expectedId=id;
        ++generation; wanted=false; activeBudget=0; id=null; confirmed=false; main.removeCallbacks(pulse);
        final long stoppedGeneration=generation;
        main.removeCallbacks(retryConnection);
        final IBudgetGuard remote=guard;
        if (remote!=null && expectedId!=null) io.execute(() -> { try {
            JSONObject record=store.read();
            if (record!=null && !expectedId.equals(record.getString("id"))) return;
            boolean ok=record==null || restore(remote,record);
            if(generation==stoppedGeneration) status=ok?"手柄已停止，临时额度已还原。":"手柄已停止；额度恢复存在冲突，原记录已保留。";
        } catch(Exception e) { if(generation==stoppedGeneration) status="手柄已停止；额度恢复未完成，原记录已保留。"; } });
    }
    void inputStopped() { HandlePrioritySafety.persistPause(context); stopRuntime(); }
    private void fail(String message) {
        HandlePrioritySafety.persistPause(context);
        stopRuntime(); status=message;
        EdgeService service=EdgeService.connectedService(); if (service!=null) service.refreshPriorityMode();
    }
}
