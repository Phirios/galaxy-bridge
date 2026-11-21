package com.samsung.android.service.health.tracking.data;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Minimal re-implementation of the Samsung Health tracking service's ServiceValue wire type, so
 * Android's Parcel machinery can resolve it by name when we read a sensor data point over Binder.
 * Only the field layout (int, float, long) is reproduced — it is the service's wire contract, not
 * Samsung's source. We only ever READ these (the service sends them); write is for completeness.
 */
public final class ServiceValue implements Parcelable {
    private int integerValue;
    private float floatValue;
    private long longValue;

    public ServiceValue(int i, float f, long l) { this.integerValue = i; this.floatValue = f; this.longValue = l; }

    public int getIntegerValue() { return integerValue; }
    public float getFloatValue() { return floatValue; }
    public long getLongValue() { return longValue; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel p, int flags) {
        p.writeInt(integerValue); p.writeFloat(floatValue); p.writeLong(longValue);
    }

    public static final Creator<ServiceValue> CREATOR = new Creator<ServiceValue>() {
        @Override public ServiceValue createFromParcel(Parcel p) {
            return new ServiceValue(p.readInt(), p.readFloat(), p.readLong());
        }
        @Override public ServiceValue[] newArray(int size) { return new ServiceValue[size]; }
    };
}
