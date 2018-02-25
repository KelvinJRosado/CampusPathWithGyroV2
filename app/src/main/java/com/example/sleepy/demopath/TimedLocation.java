package com.example.sleepy.demopath;

import com.google.android.gms.maps.model.LatLng;

/**
 * Created by Kelvin on 2/24/2018.
 */

public class TimedLocation {

    private LatLng location;//Location at specified time
    private long timeStamp;//Timestamp of specified location

    //Constructor
    public TimedLocation(LatLng loc) {
        location = loc;
        timeStamp = System.currentTimeMillis();
    }

    //Accessor methods
    public LatLng getLocation() {
        return location;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{Latitude: ").append(location.latitude);
        sb.append(", Longitude: ").append(location.longitude);
        sb.append(", Timestamp: ").append(timeStamp);
        sb.append("}");
        return sb.toString();
    }

}
