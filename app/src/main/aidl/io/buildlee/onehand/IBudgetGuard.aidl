package io.buildlee.onehand;

/** Fixed-purpose shell guard. No arbitrary command execution. */
interface IBudgetGuard {
    String readBudget() = 0;
    boolean start(String id, String original, int target, IBinder client) = 1;
    boolean renew(String id, long sequence) = 2;
    boolean confirm(String id) = 3;
    boolean restore(String id, String original, int target) = 4;
    void destroy() = 16777114;
}
