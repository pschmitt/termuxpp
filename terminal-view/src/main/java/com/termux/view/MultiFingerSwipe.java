package com.termux.view;

import android.view.MotionEvent;

/** Recognizes a translation of two or three fingers when the first finger lifts. */
public final class MultiFingerSwipe {
    public enum Direction { UP, DOWN, LEFT, RIGHT }

    private final float minimumTravel;
    private final int[] ids = new int[3];
    private final float[] startX = new float[3];
    private final float[] startY = new float[3];
    private final float[] dx = new float[3];
    private final float[] dy = new float[3];
    private int count;
    private boolean invalid;

    MultiFingerSwipe(float minimumTravel) {
        this.minimumTravel = minimumTravel;
    }

    Direction observe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                count = 0;
                invalid = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (invalid || event.getPointerCount() > 3) {
                    invalid = true;
                    break;
                }
                count = event.getPointerCount();
                for (int i = 0; i < count; i++) {
                    ids[i] = event.getPointerId(i);
                    startX[i] = event.getX(i);
                    startY[i] = event.getY(i);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // A two-finger gesture must not match the remainder of a three-finger gesture.
                if (invalid || count != event.getPointerCount()) return null;
                Direction result = directionAt(event, minimumTravel);
                invalid = true;
                return result;
            case MotionEvent.ACTION_CANCEL:
                invalid = true;
                break;
            default:
                break;
        }
        return null;
    }

    Direction movingDirection(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return null;
        return directionAt(event, minimumTravel * 0.2f);
    }

    private Direction directionAt(MotionEvent event, float travel) {
        if (invalid || count < 2 || count != event.getPointerCount()) return null;
        for (int i = 0; i < count; i++) {
            int index = event.findPointerIndex(ids[i]);
            if (index < 0) return null;
            dx[i] = event.getX(index) - startX[i];
            dy[i] = event.getY(index) - startY[i];
        }
        return classify(dx, dy, count, travel);
    }

    static Direction classify(float[] dx, float[] dy, int count, float minimumTravel) {
        float x = 0, y = 0;
        for (int i = 0; i < count; i++) {
            x += dx[i];
            y += dy[i];
        }
        x /= count;
        y /= count;
        boolean horizontal = Math.abs(x) > Math.abs(y);
        float primary = horizontal ? x : y;
        float cross = horizontal ? y : x;
        if (Math.abs(primary) < minimumTravel || Math.abs(cross) > Math.abs(primary) * 0.6f)
            return null;

        for (int i = 0; i < count; i++) {
            // Reject pinches, rotations, and motion where only one finger moved.
            float axis = horizontal ? dx[i] : dy[i];
            if (axis * Math.signum(primary) < minimumTravel * 0.65f
                || Math.hypot(dx[i] - x, dy[i] - y) > Math.abs(primary) * 0.4f)
                return null;
        }
        if (horizontal) return primary > 0 ? Direction.RIGHT : Direction.LEFT;
        return primary > 0 ? Direction.DOWN : Direction.UP;
    }
}
