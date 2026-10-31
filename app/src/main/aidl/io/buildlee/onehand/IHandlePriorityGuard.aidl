package io.buildlee.onehand;
import android.os.IBinder;
interface IHandlePriorityGuard {
    void arm(IBinder client, String session);
    boolean renew(String session, long sequence);
    void disarm(String session);
}
