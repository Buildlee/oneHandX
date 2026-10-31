package io.buildlee.onehand;

/** Direction boundaries shared by the live service and the safe short-swipe practice area. */
final class GestureRules {
    private GestureRules() { }

    static String direction(float inward, float dy, int angleLimit) {
        // 贴近垂直的滑动单独成类（参考 gulugulu 的向上/向下手势）
        if (Math.abs(inward) < Math.abs(dy) * 0.32f) return dy < 0 ? "VU" : "VD";
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(inward, 1f)));
        if (angle <= -angleLimit) return "U";
        if (angle >= angleLimit) return "D";
        return "H";
    }
}
