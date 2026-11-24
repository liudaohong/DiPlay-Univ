package com.shilapi.xcertplay.daynight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * 车灯状态来源（双路，带优先级和自动降级）。
 *
 * <p>移植自 handover/CarLampSource.java（长安 UNI-V / 飞鱼OS 已验证），只保留 CarPlay 日夜模式
 * 需要的两路读数，去掉了高德 10048 的昼夜常量依赖：
 *
 * <ul>
 *   <li>车机私有属性 {@code 0x11400E10}：1=大灯开，0=关，读不到返回 -1；</li>
 *   <li>{@link Settings.System} 的 {@code car_mode}：0=白天，非 0=黑夜，未设置返回 -1；</li>
 * </ul>
 *
 * <p>依赖：AndroidManifest 的 {@code <application>} 里需要
 * {@code <uses-library android:name="android.car" android:required="false" />}。
 * 原「车机助手」把属性读取放在进程 {@code com.wt.n2048}；本实现先在本进程读，读不到时
 * 自动落到 car_mode（已验证同样跟随车灯），并在 {@link CarLampMonitor} 的日志里标出来。
 */
public final class CarLampSource {

    public static final int PID_HEADLAMP = 0x11400E10; // 289410576
    public static final int PID_VEHICLE_SPEED = 0x11600207;
    public static final int PID_ENGINE_RPM = 0x11600305;
    public static final int PID_ODOMETER = 0x11600204;
    public static final int PID_RANGE = 0x11600308;
    public static final int PID_GEAR_CODE = 0x21410320;
    public static final int PID_GEAR_SELECTION = 0x2141342A;
    public static final int PID_GEAR_BITMASK = 0x11400400;
    public static final int PID_TURN_LEFT_REQUEST = 0x21400A36;
    public static final int PID_TURN_RIGHT_REQUEST = 0x21400A38;
    public static final int PID_HAZARD_REQUEST = 0x2940341B;
    public static final int PID_TURN_LEFT_OUTPUT = 0x21400A35;
    public static final int PID_TURN_RIGHT_OUTPUT = 0x21400A37;
    public static final String KEY_CAR_MODE = "car_mode";

    private static final String TAG = "CarLampSource";

    private volatile Object propMgr;
    private volatile boolean connecting;
    private volatile long lastConnectAt = 0L;
    private volatile int connectFailures = 0;
    private volatile String lastError = "";
    private volatile String createCarMethod = "";
    private volatile String managerName = "";

    /** 是否已经连上车机属性服务。 */
    public boolean isConnected() {
        return propMgr != null;
    }

    /** 最近一次读到的属性管理器类名，排查用。 */
    public String managerClass() {
        Object pm = propMgr;
        return pm == null ? "" : pm.getClass().getName();
    }

    /** 连接失败次数，用于日志区分“没连上”和“连上了但属性不支持”。 */
    public int connectFailures() {
        return connectFailures;
    }

    /** 最近一次失败的根因（createCar / getCarManager / getIntProperty），成功时清空。 */
    public String lastError() {
        return lastError;
    }

    /** createCar 选中的方法签名，排查用。 */
    public String createCarMethod() {
        return createCarMethod;
    }

    /** getCarManager 选中的管理器名（property/cabin/mcu/adas）。 */
    public String managerName() {
        return managerName;
    }

    /**
     * 异步连接车机属性服务。不会阻塞调用线程；失败后按 15s/30s/60s/120s 退避重试。
     *
     * @return true 表示本次真的发起了连接
     */
    public boolean connectAsync(final Context ctx) {
        if (propMgr != null || connecting) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 服务每秒会重试一次；失败后 1s/2s/4s/8s/15s 退避，既快又不刷线程。
        long delay = Math.min(15000L, 1000L << Math.min(connectFailures, 4));
        if (lastConnectAt != 0L && now - lastConnectAt < delay) {
            return false;
        }
        lastConnectAt = now;
        connecting = true;

        final Context app = ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> carCls = Class.forName("android.car.Car");
                    Object car = createCar(carCls, app);
                    if (car == null) {
                        throw new IllegalStateException("createCar 返回 null");
                    }
                    try {
                        carCls.getMethod("connect").invoke(car);
                    } catch (Throwable ignore) {
                        // 某些版本 createCar 之后已经自动连接
                    }

                    Object pm = null;
                    Method getMgr = carCls.getMethod("getCarManager", String.class);
                    String[] names = {"property", "cabin", "mcu", "adas"};
                    for (int i = 0; i < 40 && pm == null; i++) {
                        for (int n = 0; n < names.length; n++) {
                            try {
                                Object m = getMgr.invoke(car, names[n]);
                                if (m != null) {
                                    pm = m;
                                    managerName = names[n];
                                    break;
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                        if (pm == null) {
                            Thread.sleep(250L);
                        }
                    }
                    if (pm == null) {
                        throw new IllegalStateException("getCarManager 超时");
                    }
                    propMgr = pm;
                    connectFailures = 0;
                    lastError = "";
                    Log.i(TAG, "car connected: " + pm.getClass().getName() + " mgr=" + managerName);
                } catch (Throwable t) {
                    connectFailures++;
                    lastError = rootMsg(t);
                    Log.w(TAG, "car connect failed (" + connectFailures + "): " + lastError, t);
                } finally {
                    connecting = false;
                }
            }
        }, "CarLampConnect").start();
        return true;
    }

    /** 读大灯：1=开，0=关，-1=读不到（未连接/不支持/异常）。 */
    public int readLamp() {
        Object pm = propMgr;
        if (pm == null) {
            return -1;
        }
        try {
            Method m = pm.getClass().getMethod("getIntProperty", int.class, int.class);
            return ((Integer) m.invoke(pm, PID_HEADLAMP, 0)).intValue();
        } catch (Throwable t) {
            lastError = rootMsg(t);
        }
        // Android 9 以后的新签名，兜底
        try {
            Method m = pm.getClass().getMethod("getIntProperty", int.class);
            return ((Integer) m.invoke(pm, PID_HEADLAMP)).intValue();
        } catch (Throwable t) {
            lastError = lastError + " | " + rootMsg(t);
        }
        return -1;
    }

    /** 读系统昼夜标志：>=0 有效（0=白天，非 0=黑夜），-1 未设置，-2 读取异常。 */
    public int readCarMode(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), KEY_CAR_MODE, -1);
        } catch (Throwable t) {
            return -2;
        }
    }

    /** Raw PERF_VEHICLE_SPEED (vendor 0x11600207). NaN when unavailable. */
    public float readSpeed() {
        return readFloatProperty(PID_VEHICLE_SPEED);
    }

    /** ENGINE_RPM (vendor 0x11600305). NaN when unavailable. */
    public float readRpm() {
        return readFloatProperty(PID_ENGINE_RPM);
    }

    /** PERF_ODOMETER (vendor 0x11600204), km. NaN when unavailable. */
    public float readOdometer() {
        return readFloatProperty(PID_ODOMETER);
    }

    /** RANGE_REMAINING (vendor 0x11600308), km. NaN when unavailable. */
    public float readRange() {
        return readFloatProperty(PID_RANGE);
    }

    /** Raw gear code: 1..6=M1..M6, 7=P, 8=R, 9=N, 10=D; 0 unknown. */
    public int readGearRaw() {
        int code = readArrayValue(PID_GEAR_CODE, 19);
        if (code >= 1 && code <= 10) return code;
        int selection = readArrayValue(PID_GEAR_SELECTION, 1);
        switch (selection) {
            case 0: return 7; // P
            case 1: return 8; // R
            case 2: return 9; // N
            case 3: return 10; // D
            case 7: return 1; // manual, exact gear unknown
            default: break;
        }
        int mask = readIntProperty(PID_GEAR_BITMASK);
        if (mask == 4) return 7;
        if (mask == 8) return 10;
        if (mask == 2) return 8;
        if (mask == 1) return 9;
        return 0;
    }

    /** Canonical: 1=P,2=R,3=N,4=D,5..10=M1..M6. */
    public int readGear() {
        int raw = readGearRaw();
        if (raw == 0) return 0;
        if (raw >= 7) return raw - 6;
        return raw + 4;
    }

    public String readGearText() {
        int raw = readGearRaw();
        switch (raw) {
            case 1: return "M1";
            case 2: return "M2";
            case 3: return "M3";
            case 4: return "M4";
            case 5: return "M5";
            case 6: return "M6";
            case 7: return "P";
            case 8: return "R";
            case 9: return "N";
            case 10: return "D";
            default: return "";
        }
    }

    public boolean turnLeftRequested() {
        return readIntProperty(PID_TURN_LEFT_REQUEST) == 2;
    }

    public boolean turnRightRequested() {
        return readIntProperty(PID_TURN_RIGHT_REQUEST) == 2;
    }

    public boolean hazardRequested() {
        return readIntProperty(PID_HAZARD_REQUEST) == 1;
    }

    public boolean turnLeftBlink() {
        return readIntProperty(PID_TURN_LEFT_OUTPUT) == 1;
    }

    public boolean turnRightBlink() {
        return readIntProperty(PID_TURN_RIGHT_OUTPUT) == 1;
    }

    private int readIntProperty(int propId) {
        Object pm = propMgr;
        if (pm == null) return -1;
        try {
            if (!isPropertyAvailable(pm, propId)) return -1;
            Method m = pm.getClass().getMethod("getIntProperty", int.class, int.class);
            Object v = m.invoke(pm, propId, 0);
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable t) {
            lastError = rootMsg(t);
            return -1;
        }
    }

    private int readArrayValue(int propId, int index) {
        Object pm = propMgr;
        if (pm == null) return 0;
        try {
            if (!isPropertyAvailable(pm, propId)) return 0;
            Method m = pm.getClass().getMethod("getIntArrayProperty", int.class, int.class);
            Object v = m.invoke(pm, propId, 0);
            if (!(v instanceof int[])) return 0;
            int[] array = (int[]) v;
            return index >= 0 && index < array.length ? array[index] : 0;
        } catch (Throwable t) {
            lastError = rootMsg(t);
            return 0;
        }
    }

    /**
     * Float property reader. These vendor IDs are floats: using getIntProperty would throw
     * "type mismatch" on Android 9. isPropertyAvailable is checked first because an
     * unregistered property returns a misleading 0.0.
     */
    private float readFloatProperty(int propId) {
        Object pm = propMgr;
        if (pm == null) return Float.NaN;
        try {
            if (!isPropertyAvailable(pm, propId)) return Float.NaN;
            Method m = pm.getClass().getMethod("getFloatProperty", int.class, int.class);
            Object v = m.invoke(pm, propId, 0);
            return v instanceof Float ? ((Float) v).floatValue() : Float.NaN;
        } catch (Throwable t) {
            lastError = rootMsg(t);
            return Float.NaN;
        }
    }

    private boolean isPropertyAvailable(Object pm, int propId) {
        try {
            Method m = pm.getClass().getMethod("isPropertyAvailable", int.class, int.class);
            Object v = m.invoke(pm, propId, 0);
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable t) {
            // Older signatures or a manager without the method: let the float read decide.
            try {
                Method m = pm.getClass().getMethod("isPropertyAvailable", int.class);
                Object v = m.invoke(pm, propId);
                return v instanceof Boolean && ((Boolean) v).booleanValue();
            } catch (Throwable ignore) {
                return true;
            }
        }
    }

    // ---------------------------------------------------------------- 反射工具

    private Object createCar(Class<?> carCls, Context ctx) {
        Handler main = new Handler(Looper.getMainLooper());
        Method[] methods = carCls.getMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            if (!Modifier.isStatic(m.getModifiers())
                    || !"createCar".equals(m.getName())
                    || m.getReturnType() == Void.TYPE) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            Object[] args = new Object[pts.length];
            boolean ok = true;
            for (int k = 0; k < pts.length; k++) {
                Class<?> p = pts[k];
                if (Context.class.isAssignableFrom(p)) {
                    args[k] = ctx;
                } else if (p == Handler.class) {
                    args[k] = main;
                } else if (p == long.class) {
                    args[k] = 8000L;
                } else if (p == int.class) {
                    args[k] = 8000;
                } else if (p == boolean.class) {
                    args[k] = Boolean.FALSE;
                } else if (p.isInterface()) {
                    args[k] = Proxy.newProxyInstance(p.getClassLoader(), new Class<?>[]{p},
                            new java.lang.reflect.InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] a) {
                                    String n = method.getName();
                                    if (method.getDeclaringClass() == Object.class) {
                                        if ("hashCode".equals(n)) {
                                            return Integer.valueOf(System.identityHashCode(proxy));
                                        }
                                        if ("equals".equals(n)) {
                                            return Boolean.valueOf(proxy == a[0]);
                                        }
                                        if ("toString".equals(n)) {
                                            return "CarProxy";
                                        }
                                    }
                                    return null;
                                }
                            });
                } else if (!p.isPrimitive()) {
                    args[k] = null;
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            try {
                Object car = m.invoke(null, args);
                if (car != null) {
                    createCarMethod = m.toString();
                    Log.i(TAG, "createCar via " + createCarMethod);
                    return car;
                }
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String s = c.getClass().getSimpleName();
        if (c.getMessage() != null && c.getMessage().length() > 0) {
            s = s + ": " + c.getMessage();
        }
        return s;
    }
}
