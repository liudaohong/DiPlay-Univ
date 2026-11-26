package com.shilapi.xcertplay.daynight;

import android.os.Bundle;

/**
 * Runs in the vendor-whitelisted process "com.wt.n2048" (see LampReaderService), where the car's
 * private property 0x11400E10 is readable. The main process binds to it; car_mode is returned for
 * diagnostics only and never drives the theme.
 */
interface ILampReader {
    /** 1 = headlamps on, 0 = off, -1 = unreadable. */
    int getLamp();

    /** Raw PERF_VEHICLE_SPEED (vendor 0x11600207). NaN when unavailable. */
    float getSpeed();

    /** ENGINE_RPM (vendor 0x11600305). NaN when unavailable. */
    float getRpm();

    /** PERF_ODOMETER (vendor 0x11600204), km. NaN when unavailable. */
    float getOdometer();

    /** RANGE_REMAINING (vendor 0x11600308), km. NaN when unavailable. */
    float getRange();

    /** Raw Settings.System car_mode: diagnostics only (this unit reports 1 when the lamps are off). */
    int getCarMode();

    /** Whether the service's android.car property manager is connected. */
    boolean isConnected();

    String getManagerClass();

    int getConnectFailures();

    /** Root cause of the last createCar/getCarManager/getIntProperty failure, empty when healthy. */
    String getLastError();

    /** The createCar method the reflection picked (diagnostics). */
    String getCreateCarMethod();

    /** The getCarManager name that returned a manager (property/cabin/mcu/adas). */
    String getManagerName();

    /** Canonical gear: 0=unknown, 1=P, 2=R, 3=N, 4=D, 5..10=M1..M6. */
    int getGear();

    /** Raw 0x21410320[19]: 1..6=M1..M6, 7=P, 8=R, 9=N, 10=D. */
    int getGearRaw();

    /** P/R/N/D/M1..M6, empty when unknown. */
    String getGearText();

    boolean isTurnLeftRequested();
    boolean isTurnRightRequested();
    boolean isHazardRequested();
    boolean isTurnLeftBlink();
    boolean isTurnRightBlink();

    /** Batch snapshot of vehicle properties; when motionOnly is true, skips RPM/odo/range/turn signals. */
    Bundle getVehicleSnapshot(boolean motionOnly);
}
