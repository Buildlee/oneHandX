package io.buildlee.onehand;

/** Direction boundaries shared by the live service and the safe short-swipe practice area. */
final class GestureRules {
    private GestureRules() { }

    static String direction(float inward, float dy, int angleLimit) {
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(inward, 1f)));
        if (angle <= -angleLimit) return "U";
        if (angle >= angleLimit) return "D";
        return "H";
    }
}
