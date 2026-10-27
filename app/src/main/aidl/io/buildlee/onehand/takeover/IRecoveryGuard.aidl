package io.buildlee.onehand.takeover;

import android.os.ParcelFileDescriptor;

/** Setup capabilities only. No system gesture settings are changed by this interface. */
interface IRecoveryGuard {
    int identity() = 0;
    boolean tryLock(in ParcelFileDescriptor file) = 1;
    boolean grantRecoveryPermission() = 2;
    boolean prepare(String sessionId, String left, String right, String lockPath,
                    long lockDevice, long lockInode, in ParcelFileDescriptor file, IBinder client) = 3;
    boolean apply(String sessionId) = 4;
    boolean renew(String sessionId, long sequence) = 5;
    boolean activate(String sessionId) = 6;
    boolean restore(String sessionId) = 7;
    void destroy() = 16777114;
}
