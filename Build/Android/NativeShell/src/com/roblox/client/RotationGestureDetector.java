package com.roblox.client;

import android.graphics.Point;
import android.view.MotionEvent;

/* JADX INFO: loaded from: classes.dex */
public class RotationGestureDetector {
    private static final int INVALID_POINTER_ID = -1;
    private OnRotationGestureListener mListener;
    private Point lastFinger1Pos = new Point();
    private Point lastFinger2Pos = new Point();
    private Point lastVector = new Point();
    private float mAngle = 0.0f;
    private int mState = -1;
    private int ptrID1 = -1;
    private int ptrID2 = -1;

    public interface OnRotationGestureListener {
        void OnRotation(RotationGestureDetector rotationGestureDetector);
    }

    public float getAngle() {
        return this.mAngle;
    }

    public int getState() {
        return this.mState;
    }

    public Point getPoint1() {
        return this.ptrID1 != -1 ? this.lastFinger1Pos : new Point();
    }

    public Point getPoint2() {
        return this.ptrID2 != -1 ? this.lastFinger2Pos : new Point();
    }

    public RotationGestureDetector(OnRotationGestureListener listener) {
        this.mListener = listener;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case 0:
                if (this.ptrID1 == -1) {
                    this.ptrID1 = event.getPointerId(event.getActionIndex());
                    this.ptrID2 = -1;
                    this.mState = 0;
                    this.mAngle = 0.0f;
                }
                return true;
            case 1:
                this.mState = 2;
                if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                    this.mListener.OnRotation(this);
                }
                this.mAngle = 0.0f;
                this.ptrID1 = -1;
                return true;
            case 2:
                if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                    this.mState = 1;
                    float nsX = event.getX(event.findPointerIndex(this.ptrID1));
                    float nsY = event.getY(event.findPointerIndex(this.ptrID1));
                    float nfX = event.getX(event.findPointerIndex(this.ptrID2));
                    float nfY = event.getY(event.findPointerIndex(this.ptrID2));
                    angleBetweenLines(nfX, nfY, nsX, nsY);
                    if (this.mListener != null) {
                        this.mListener.OnRotation(this);
                    }
                }
                return true;
            case 3:
            case 4:
            default:
                return true;
            case 5:
                if (this.ptrID2 == -1) {
                    this.ptrID2 = event.getPointerId(event.getActionIndex());
                    this.lastFinger1Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID1));
                    this.lastFinger1Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID1));
                    this.lastFinger2Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID2));
                    this.lastFinger2Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID2));
                    this.lastVector.x = this.lastFinger2Pos.x - this.lastFinger1Pos.x;
                    this.lastVector.y = this.lastFinger2Pos.y - this.lastFinger1Pos.y;
                    if (this.ptrID1 != -1) {
                        this.mListener.OnRotation(this);
                    }
                }
                return true;
            case 6:
                this.mState = 2;
                if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                    this.mListener.OnRotation(this);
                }
                this.mAngle = 0.0f;
                this.ptrID2 = -1;
                return true;
        }
    }

    private void angleBetweenLines(float nfX, float nfY, float nsX, float nsY) {
        Point newVector = new Point();
        newVector.x = (int) (nfX - nsX);
        newVector.y = (int) (nfY - nsY);
        this.lastVector.x = this.lastFinger2Pos.x - this.lastFinger1Pos.x;
        this.lastVector.y = this.lastFinger2Pos.y - this.lastFinger1Pos.y;
        float dotProductDelta = (this.lastVector.x * newVector.x) + (this.lastVector.y * newVector.y);
        double angleDelta = Math.atan2((newVector.x * this.lastVector.y) - (newVector.y * this.lastVector.x), dotProductDelta);
        this.lastFinger2Pos.x = (int) nfX;
        this.lastFinger2Pos.y = (int) nfY;
        this.lastFinger1Pos.x = (int) nsX;
        this.lastFinger1Pos.y = (int) nsY;
        this.mAngle -= (float) angleDelta;
    }
}
