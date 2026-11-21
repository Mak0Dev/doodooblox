package com.roblox.client;

import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class InputListener implements View.OnTouchListener, RotationGestureDetector.OnRotationGestureListener, ScaleGestureDetector.OnScaleGestureListener, SensorEventListener {
    private Sensor mAccelerometer;
    private float mDisplayDensity;
    private GestureDetector mGestureDetector;
    private GestureListener mGestureListener;
    private Sensor mGyroscope;
    private boolean mHasTouchscreen;
    private RotationGestureDetector mRotationDetector;
    private ScaleGestureDetector mScaleDetector;
    private SensorManager mSensorManager;
    private SurfaceView mSurfaceView;
    private SparseArray<TouchInfo> mActivePointers = new SparseArray<>();
    private boolean mUserRequested = false;
    private final float[] rotationQuaternion = new float[4];
    private final float[] rotationMatrix = new float[9];
    private final float[] eulerAnglesVector = new float[3];
    private final float[] gravity = new float[3];
    private final float[] linear_acceleration = new float[3];

    // DoodooBlox: gamepad API of the engine built from source (Android/libroblox/JNIInputListener.cpp)
    private static native void nativeGamepadConnectEvent(int deviceId);

    private static native void nativeGamepadButtonEvent(int deviceId, int keyCode, int buttonState);

    private static native void nativeGamepadAxisEvent(int deviceId, int actionType, float x, float y, float z);

    private static native void nativeSetGamepadSupportedKey(int deviceId, int keyCode, boolean supported);

    private static native void nativePassAccelerometerChange(float f, float f2, float f3);

    private static native void nativePassGravityChange(float f, float f2, float f3);

    private static native void nativePassGyroscopeChange(float f, float f2, float f3, float f4, float f5, float f6, float f7);

    private static native void nativePassInput(int i, int i2, int i3, int i4, int i5, int i6);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativePassLongPressGesture(int i, int i2, int i3);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativePassPanGesture(int i, int i2, int i3, float f, float f2, float f3);

    private static native void nativePassPinchGesture(int i, float f, float f2, int i2, int i3, int i4, int i5);

    private static native void nativePassRotateGesture(int i, float f, float f2, int i2, int i3, int i4, int i5);

    private static native void nativePassSwipeGesture(int i, int i2);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativePassTapGesture(int i, int i2);

    private static native void nativeSetAccelerometerEnabled(boolean z);

    private static native void nativeSetGyroscopeEnabled(boolean z);

    class TouchInfo {
        private int eventType;
        int lastEventType;
        int lastX;
        int lastY;
        private int x;
        private int y;

        TouchInfo() {
        }

        public int getX() {
            return this.x;
        }

        public void setX(int X) {
            this.lastX = this.x;
            this.x = X;
        }

        public int getY() {
            return this.y;
        }

        public void setY(int Y) {
            this.lastY = this.y;
            this.y = Y;
        }

        public int getEventType() {
            return this.eventType;
        }

        public void setEventType(int newEventType) {
            this.lastEventType = this.eventType;
            this.eventType = newEventType;
        }
    }

    public InputListener(ActivityGlView glView, SurfaceView surfaceView) {
        this.mSurfaceView = null;
        this.mDisplayDensity = 0.0f;
        this.mHasTouchscreen = true;
        this.mGestureDetector = null;
        this.mGestureListener = null;
        this.mRotationDetector = null;
        this.mScaleDetector = null;
        this.mSensorManager = null;
        this.mAccelerometer = null;
        this.mGyroscope = null;
        this.mSurfaceView = surfaceView;
        this.mDisplayDensity = glView.getResources().getDisplayMetrics().density;
        this.mHasTouchscreen = glView.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
        if (this.mHasTouchscreen) {
            this.mGestureListener = new GestureListener();
            this.mGestureDetector = new GestureDetector(glView, this.mGestureListener);
            this.mRotationDetector = new RotationGestureDetector(this);
            this.mScaleDetector = new ScaleGestureDetector(this);
        }
        setupControllerInput(surfaceView);
        this.mSensorManager = (SensorManager) glView.getBaseContext().getSystemService("sensor");
        this.mAccelerometer = this.mSensorManager.getDefaultSensor(1);
        this.mGyroscope = this.mSensorManager.getDefaultSensor(11);
        if (this.mGyroscope != null) {
            nativeSetGyroscopeEnabled(true);
        }
        if (this.mAccelerometer != null) {
            nativeSetAccelerometerEnabled(true);
        }
    }

    public void stopSensorListening() {
        this.mSensorManager.unregisterListener(this);
    }

    public void startSensorListening(boolean userRequested) {
        if (!this.mUserRequested) {
            this.mUserRequested = userRequested;
        }
        if (this.mUserRequested) {
            this.mSensorManager.registerListener(this, this.mGyroscope, 1);
            this.mSensorManager.registerListener(this, this.mAccelerometer, 1);
        }
    }

    private final SparseArray<Boolean> mGamepads = new SparseArray<>();

    private static final int[] GAMEPAD_KEYS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START};

    private static final int[] GAMEPAD_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};

    // Tell the engine about a controller the first time it sends input, as the 2016 shell did.
    private void checkForControllerConnection(int deviceId) {
        if (this.mGamepads.get(deviceId) != null) {
            return;
        }
        this.mGamepads.put(deviceId, Boolean.TRUE);
        InputDevice device = InputDevice.getDevice(deviceId);
        boolean[] keySupported = null;
        if (device != null && Build.VERSION.SDK_INT >= 19) {
            keySupported = device.hasKeys(GAMEPAD_KEYS);
        }
        for (int i = 0; i < GAMEPAD_KEYS.length; i++) {
            nativeSetGamepadSupportedKey(deviceId, GAMEPAD_KEYS[i], keySupported == null || keySupported[i]);
        }
        for (int axis : GAMEPAD_AXES) {
            nativeSetGamepadSupportedKey(deviceId, axis, device != null && device.getMotionRange(axis) != null);
        }
        nativeGamepadConnectEvent(deviceId);
    }

    private static float centeredAxis(MotionEvent event, InputDevice device, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) {
            return 0.0f;
        }
        float value = event.getAxisValue(axis);
        return Math.abs(value) > range.getFlat() ? value : 0.0f;
    }

    private void setupControllerInput(SurfaceView surfaceView) {
        surfaceView.setOnKeyListener(new View.OnKeyListener() { // from class: com.roblox.client.InputListener.1
            @Override // android.view.View.OnKeyListener
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_BUTTON_A:
                    case KeyEvent.KEYCODE_BUTTON_B:
                    case KeyEvent.KEYCODE_BUTTON_X:
                    case KeyEvent.KEYCODE_BUTTON_Y:
                    case KeyEvent.KEYCODE_BUTTON_THUMBR:
                    case KeyEvent.KEYCODE_BUTTON_THUMBL:
                    case KeyEvent.KEYCODE_BUTTON_R1:
                    case KeyEvent.KEYCODE_BUTTON_R2:
                    case KeyEvent.KEYCODE_BUTTON_L1:
                    case KeyEvent.KEYCODE_BUTTON_L2:
                    case KeyEvent.KEYCODE_BUTTON_START:
                    case KeyEvent.KEYCODE_BUTTON_SELECT:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int deviceId = event.getDeviceId();
                        InputListener.this.checkForControllerConnection(deviceId);
                        InputListener.nativeGamepadButtonEvent(deviceId, keyCode, event.getAction() == KeyEvent.ACTION_DOWN ? 1 : 0);
                        return true;
                    default:
                        return false;
                }
            }
        });
        surfaceView.setOnGenericMotionListener(new View.OnGenericMotionListener() { // from class: com.roblox.client.InputListener.2
            @Override // android.view.View.OnGenericMotionListener
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getSource() != InputDevice.SOURCE_GAMEPAD && event.getSource() != InputDevice.SOURCE_JOYSTICK) {
                    return false;
                }
                InputDevice device = event.getDevice();
                if (device == null) {
                    return false;
                }
                int deviceId = event.getDeviceId();
                InputListener.this.checkForControllerConnection(deviceId);
                float lx = centeredAxis(event, device, MotionEvent.AXIS_X);
                float ly = centeredAxis(event, device, MotionEvent.AXIS_Y);
                float rx = centeredAxis(event, device, MotionEvent.AXIS_Z);
                float ry = centeredAxis(event, device, MotionEvent.AXIS_RZ);
                // some pads report GAS/BRAKE instead of the trigger axes
                float lt = Math.max(centeredAxis(event, device, MotionEvent.AXIS_LTRIGGER), centeredAxis(event, device, MotionEvent.AXIS_BRAKE));
                float rt = Math.max(centeredAxis(event, device, MotionEvent.AXIS_RTRIGGER), centeredAxis(event, device, MotionEvent.AXIS_GAS));
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_X, lx, -ly, 0.0f);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_Y, lx, -ly, 0.0f);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_Z, rx, -ry, 0.0f);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_RZ, rx, -ry, 0.0f);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_LTRIGGER, 0.0f, 0.0f, lt);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_RTRIGGER, 0.0f, 0.0f, rt);
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_HAT_X, 0.0f, 0.0f, centeredAxis(event, device, MotionEvent.AXIS_HAT_X));
                InputListener.nativeGamepadAxisEvent(deviceId, MotionEvent.AXIS_HAT_Y, 0.0f, 0.0f, -centeredAxis(event, device, MotionEvent.AXIS_HAT_Y));
                return true;
            }
        });
    }

    @Override // android.view.View.OnTouchListener
    public boolean onTouch(View view, MotionEvent event) {
        if (!this.mHasTouchscreen) {
            return false;
        }
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);
        int maskedAction = event.getActionMasked();
        switch (maskedAction) {
            case 0:
            case 5:
                TouchInfo info = new TouchInfo();
                info.setX((int) (event.getX(pointerIndex) / this.mDisplayDensity));
                info.setY((int) (event.getY(pointerIndex) / this.mDisplayDensity));
                info.setEventType(0);
                this.mActivePointers.put(pointerId, info);
                break;
            case 1:
            case 3:
            case 6:
                TouchInfo info2 = this.mActivePointers.get(pointerId);
                info2.setEventType(2);
                if (pointerId == this.mGestureListener.longPressEventId) {
                    this.mGestureListener.longPressEventId = -1;
                    nativePassLongPressGesture(2, info2.getX(), info2.getY());
                } else if (pointerId == this.mGestureListener.panEventId) {
                    this.mGestureListener.endPanGesture();
                }
                break;
            case 2:
                for (int j = 0; j < this.mActivePointers.size(); j++) {
                    try {
                        int touchId = this.mActivePointers.keyAt(j);
                        int movePointerIndex = event.findPointerIndex(touchId);
                        TouchInfo info3 = this.mActivePointers.get(touchId);
                        info3.setX((int) (event.getX(movePointerIndex) / this.mDisplayDensity));
                        info3.setY((int) (event.getY(movePointerIndex) / this.mDisplayDensity));
                        info3.setEventType(1);
                        if (touchId == this.mGestureListener.longPressEventId) {
                            nativePassLongPressGesture(1, info3.getX(), info3.getY());
                        }
                    } catch (IllegalArgumentException e) {
                    }
                }
                break;
        }
        List<Integer> idsToRemove = new ArrayList<>();
        int size = this.mActivePointers.size();
        for (int i = 0; i < size; i++) {
            int touchId2 = this.mActivePointers.keyAt(i);
            TouchInfo point = this.mActivePointers.valueAt(i);
            boolean processEvent = true;
            if (point.getEventType() == 2) {
                idsToRemove.add(Integer.valueOf(touchId2));
            } else if (point.getEventType() == point.lastEventType) {
                if (point.getX() == point.lastX && point.getY() == point.lastY) {
                    processEvent = false;
                }
            } else if (point.getEventType() == 1 && point.lastEventType == 0 && point.getX() == point.lastX && point.getY() == point.lastY) {
                processEvent = false;
            }
            if (processEvent && ActivityGlView.getSurfaceState() == 2) {
                int width = (int) (this.mSurfaceView.getWidth() / this.mDisplayDensity);
                int height = (int) (this.mSurfaceView.getHeight() / this.mDisplayDensity);
                point.lastX = point.getX();
                point.lastY = point.getY();
                nativePassInput(touchId2, point.getX(), point.getY(), point.getEventType(), width, height);
            } else if (ActivityGlView.getSurfaceState() != 2) {
                Log.w("InputListener", "nativePassInput not ready or already passed event");
            }
        }
        for (Integer key : idsToRemove) {
            this.mActivePointers.remove(key.intValue());
        }
        this.mScaleDetector.onTouchEvent(event);
        this.mGestureDetector.onTouchEvent(event);
        this.mRotationDetector.onTouchEvent(event);
        return true;
    }

    private final class GestureListener implements GestureDetector.OnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;
        public int longPressEventId;
        public int panEventId;
        private int xCurrPanPos;
        private int xPanTranslation;
        private int yCurrPanPos;
        private int yPanTranslation;

        private GestureListener() {
            this.longPressEventId = -1;
            this.panEventId = -1;
            this.xCurrPanPos = 0;
            this.yCurrPanPos = 0;
            this.xPanTranslation = 0;
            this.yPanTranslation = 0;
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public boolean onDown(MotionEvent e) {
            return false;
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            try {
                float diffY = e2.getY() - e1.getY();
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100.0f && Math.abs(velocityX) > 100.0f) {
                        if (diffX > 0.0f) {
                            InputListener.this.onSwipeRight();
                        } else {
                            InputListener.this.onSwipeLeft();
                        }
                    }
                } else if (Math.abs(diffY) > 100.0f && Math.abs(velocityY) > 100.0f) {
                    if (diffY > 0.0f) {
                        InputListener.this.onSwipeBottom();
                    } else {
                        InputListener.this.onSwipeTop();
                    }
                }
                return false;
            } catch (Exception exception) {
                exception.printStackTrace();
                return false;
            }
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public void onShowPress(MotionEvent e) {
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public boolean onSingleTapUp(MotionEvent e) {
            int xPos = (int) (e.getX() / InputListener.this.mDisplayDensity);
            int yPos = (int) (e.getY() / InputListener.this.mDisplayDensity);
            InputListener.nativePassTapGesture(xPos, yPos);
            return false;
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            boolean isNewEvent = false;
            if (this.panEventId == -1) {
                isNewEvent = true;
            }
            int pointerIndex = e2.getActionIndex();
            this.panEventId = e2.getPointerId(pointerIndex);
            this.xCurrPanPos = (int) (e2.getX() / InputListener.this.mDisplayDensity);
            this.yCurrPanPos = (int) (e2.getY() / InputListener.this.mDisplayDensity);
            int xInitPos = (int) (e1.getX() / InputListener.this.mDisplayDensity);
            int yInitPos = (int) (e1.getY() / InputListener.this.mDisplayDensity);
            this.xPanTranslation = this.xCurrPanPos - xInitPos;
            this.yPanTranslation = this.yCurrPanPos - yInitPos;
            if (isNewEvent) {
                InputListener.nativePassPanGesture(0, this.xCurrPanPos, this.yCurrPanPos, this.xPanTranslation, this.yPanTranslation, 0.0f);
                return false;
            }
            InputListener.nativePassPanGesture(1, this.xCurrPanPos, this.yCurrPanPos, this.xPanTranslation, this.yPanTranslation, 0.0f);
            return false;
        }

        public void endPanGesture() {
            this.panEventId = -1;
            InputListener.nativePassPanGesture(2, this.xCurrPanPos, this.yCurrPanPos, this.xPanTranslation, this.yPanTranslation, 0.0f);
        }

        @Override // android.view.GestureDetector.OnGestureListener
        public void onLongPress(MotionEvent e) {
            int pointerIndex = e.getActionIndex();
            this.longPressEventId = e.getPointerId(pointerIndex);
            int xPos = (int) (e.getX() / InputListener.this.mDisplayDensity);
            int yPos = (int) (e.getY() / InputListener.this.mDisplayDensity);
            InputListener.nativePassLongPressGesture(0, xPos, yPos);
        }
    }

    public void onSwipeRight() {
        nativePassSwipeGesture(0, 1);
    }

    public void onSwipeBottom() {
        nativePassSwipeGesture(1, 1);
    }

    public void onSwipeLeft() {
        nativePassSwipeGesture(2, 1);
    }

    public void onSwipeTop() {
        nativePassSwipeGesture(3, 1);
    }

    @Override // com.roblox.client.RotationGestureDetector.OnRotationGestureListener
    public void OnRotation(RotationGestureDetector rotationDetector) {
        float angle = rotationDetector.getAngle();
        int state = rotationDetector.getState();
        Point point1 = rotationDetector.getPoint1();
        Point point2 = rotationDetector.getPoint2();
        nativePassRotateGesture(state, angle, 0.0f, point1.x, point1.y, point2.x, point2.y);
    }

    @Override // com.roblox.client.ScaleGestureDetector.OnScaleGestureListener
    public void OnScale(ScaleGestureDetector scaleDetector) {
        Point point1 = scaleDetector.getPoint1();
        Point point2 = scaleDetector.getPoint2();
        float scale = scaleDetector.getScale();
        int state = scaleDetector.getState();
        nativePassPinchGesture(state, scale, 0.0f, point1.x, point1.y, point2.x, point2.y);
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == 11) {
            SensorManager.getQuaternionFromVector(this.rotationQuaternion, event.values);
            SensorManager.getRotationMatrixFromVector(this.rotationMatrix, this.rotationQuaternion);
            SensorManager.getOrientation(this.rotationMatrix, this.eulerAnglesVector);
            nativePassGyroscopeChange(this.eulerAnglesVector[0], this.eulerAnglesVector[1], this.eulerAnglesVector[2], this.rotationQuaternion[0], this.rotationQuaternion[2], this.rotationQuaternion[3], this.rotationQuaternion[1]);
            return;
        }
        if (event.sensor.getType() == 1) {
            this.gravity[0] = (this.gravity[0] * 0.8f) + (event.values[0] * 0.19999999f);
            this.gravity[1] = (this.gravity[1] * 0.8f) + (event.values[1] * 0.19999999f);
            this.gravity[2] = (this.gravity[2] * 0.8f) + (event.values[2] * 0.19999999f);
            this.linear_acceleration[0] = event.values[0] - this.gravity[0];
            this.linear_acceleration[1] = event.values[1] - this.gravity[1];
            this.linear_acceleration[2] = event.values[2] - this.gravity[2];
            nativePassAccelerometerChange(-this.linear_acceleration[1], -this.linear_acceleration[2], -this.linear_acceleration[0]);
            nativePassGravityChange(-this.gravity[1], -this.gravity[2], -this.gravity[0]);
        }
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
