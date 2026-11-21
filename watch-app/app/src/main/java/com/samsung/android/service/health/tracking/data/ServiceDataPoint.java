package com.samsung.android.service.health.tracking.data;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal re-implementation of the service's ServiceDataPoint wire type (a timestamp plus an
 * ordered list of ServiceValue). Reproduces only the parcel layout so we can read sensor points
 * the service delivers over Binder. The per-sensor meaning of the value order is applied by
 * SensorBridge. Our own code, matching the wire contract; not Samsung source.
 */
public final class ServiceDataPoint implements Parcelable {
    private final long timeStamp;
    private final List<ServiceValue> values;

    public ServiceDataPoint(long ts, List<ServiceValue> values) { this.timeStamp = ts; this.values = values; }

    public long getTimeStamp() { return timeStamp; }
    public List<ServiceValue> getServiceValue() { return values; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel p, int flags) {
        p.writeLong(timeStamp);
        p.writeList(values);
    }

    @SuppressWarnings("unchecked")
    public static final Creator<ServiceDataPoint> CREATOR = new Creator<ServiceDataPoint>() {
        @Override public ServiceDataPoint createFromParcel(Parcel p) {
            long ts = p.readLong();
            ArrayList<ServiceValue> list = new ArrayList<>();
            p.readList(list, ServiceValue.class.getClassLoader());
            return new ServiceDataPoint(ts, list);
        }
        @Override public ServiceDataPoint[] newArray(int size) { return new ServiceDataPoint[size]; }
    };
}
