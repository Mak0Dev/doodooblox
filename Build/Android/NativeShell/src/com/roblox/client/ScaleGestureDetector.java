package com.roblox.client;

import android.graphics.Point;
import android.view.MotionEvent;

/* JADX INFO: loaded from: classes.dex */
public class ScaleGestureDetector {
    private static final int INVALID_POINTER_ID = -1;
    private OnScaleGestureListener mListener;
    private float mScale;
    private int mState;
    private Point initFinger1Pos = new Point();
    private Point initFinger2Pos = new Point();
    private Point currFinger1Pos = new Point();
    private Point currFinger2Pos = new Point();
    private int ptrID1 = -1;
    private int ptrID2 = -1;

    public interface OnScaleGestureListener {
        void OnScale(ScaleGestureDetector scaleGestureDetector);
    }

    public int getState() {
        return this.mState;
    }

    public float getScale() {
        return this.mScale;
    }

    public Point getPoint1() {
        return this.ptrID1 != -1 ? this.currFinger1Pos : new Point();
    }

    public Point getPoint2() {
        return this.ptrID2 != -1 ? this.currFinger2Pos : new Point();
    }

    public ScaleGestureDetector(OnScaleGestureListener listener) {
        this.mState = -1;
        this.mScale = 1.0f;
        this.mListener = listener;
        this.mState = -1;
        this.mScale = 1.0f;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public boolean onTouchEvent(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case 0:
                    if (this.ptrID1 == -1) {
                        this.ptrID1 = event.getPointerId(event.getActionIndex());
                        this.ptrID2 = -1;
                        this.mState = 0;
                    }
                    break;
                case 1:
                    this.mState = 2;
                    if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                        this.mListener.OnScale(this);
                    }
                    this.mScale = 1.0f;
                    this.ptrID1 = -1;
                    break;
                case 2:
                    if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                        this.mState = 1;
                        this.currFinger1Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID1));
                        this.currFinger1Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID1));
                        this.currFinger2Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID2));
                        this.currFinger2Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID2));
                        scaleBetweenPoints();
                        if (this.mListener != null) {
                            this.mListener.OnScale(this);
                        }
                    }
                    break;
                case 5:
                    if (this.ptrID2 == -1) {
                        this.ptrID2 = event.getPointerId(event.getActionIndex());
                        this.initFinger1Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID1));
                        this.initFinger1Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID1));
                        this.initFinger2Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID2));
                        this.initFinger2Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID2));
                        this.currFinger1Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID1));
                        this.currFinger1Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID1));
                        this.currFinger2Pos.x = (int) event.getX(event.findPointerIndex(this.ptrID2));
                        this.currFinger2Pos.y = (int) event.getY(event.findPointerIndex(this.ptrID2));
                        this.mState = 0;
                        if (this.ptrID1 != -1) {
                            this.mListener.OnScale(this);
                        }
                    }
                    break;
                case 6:
                    this.mState = 2;
                    if (this.ptrID1 != -1 && this.ptrID2 != -1) {
                        this.mListener.OnScale(this);
                    }
                    this.mScale = 1.0f;
                    this.ptrID2 = -1;
                    break;
            }
        } catch (IllegalArgumentException e) {
        }
        return true;
    }

    private void scaleBetweenPoints() {
        float initXDiff = this.initFinger2Pos.x - this.initFinger1Pos.x;
        float initYDiff = this.initFinger2Pos.y - this.initFinger1Pos.y;
        double initLength = Math.sqrt(Math.pow(initXDiff, 2.0d) + Math.pow(initYDiff, 2.0d));
        float currXDiff = this.currFinger2Pos.x - this.currFinger1Pos.x;
        float currYDiff = this.currFinger2Pos.y - this.currFinger1Pos.y;
        double currLength = Math.sqrt(Math.pow(currXDiff, 2.0d) + Math.pow(currYDiff, 2.0d));
        if (initLength != 0.0d) {
            this.mScale = (float) (currLength / initLength);
        } else {
            this.mScale = 1.0f;
        }
    }
}
