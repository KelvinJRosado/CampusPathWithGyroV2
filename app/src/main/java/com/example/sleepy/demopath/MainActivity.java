package com.example.sleepy.demopath;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.maps.android.SphericalUtil;

import java.util.ArrayList;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

//Implement Google Maps interface
public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, SensorEventListener {
    /*
    References
    [1] http://www.codingforandroid.com/2011/01/using-orientation-sensors-simple.html
    [2] https://www.built.io/blog/applying-low-pass-filter-to-android-sensor-s-readings
    */

    //Map API
    private FusedLocationProviderClient fusedLocationProviderClient;//Gets starting location
    private GoogleMap googleMap;//Reference to map

    //Reference to context
    Context thisContext = this;

    //Sensors
    private SensorManager sensorManager;
    private Sensor stepSensor;

    //Gravity and rotation info; Used for calculating orientation
    private Sensor accelSensor, magnetSensor, gyroSensor;
    private float[] lastAccel = new float[3];
    private float[] lastMagnet = new float[3];
    private boolean accelSet = false, magnetSet = false;
    private float[] rotation = new float[9];
    private float[] orientation = new float[3];
    private float currentAngle = 0f;
    private double zGyro;
    private double yGyro;
    private double xGyro;
    private double currentDirection;
    private final float ALPHA = (float) 0.25;
    private int sensorChanged;

    private GeomagneticField geomagneticField;

    private ArrayList<LatLng> userPath;

    private double stepLength = 0.839216;//0.6923532;//0.7099996;//<-Sleepy's step, based off of calculations with a ruler: Kelvin's step ->0.6923532; in meters

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Get the map fragment
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        //Set up location client
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);


        init();//Initialize objects


    }

    //Initialize and display map
    @Override
    public void onMapReady(final GoogleMap googleMap) {

        this.googleMap = googleMap;

        //Make a marker in start location and display
        if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{ACCESS_FINE_LOCATION}, PackageManager.PERMISSION_GRANTED);

        }
        //Plot starting position
        fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this, new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                if (location != null) {

                    LatLng start = new LatLng(location.getLatitude(), location.getLongitude());
                    //Place marker at start position
                    googleMap.addMarker(new MarkerOptions().position(start).title("Start location"));
                    //Center map on marker and zoom in
                    //Zoom is a float in range [2.0f,21.0f]
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 20.0f));

                    //Add start location to buffer
                    userPath.add(start);

                    //Get declination for finding true north
                    geomagneticField = new GeomagneticField((float) location.getLatitude(),
                            (float) location.getLatitude(), (float) location.getAltitude(), System.currentTimeMillis());

                } else {
                    //Show AUS if location not found
                    LatLng sydney = new LatLng(-33.852, 151.211);
                    googleMap.addMarker(new MarkerOptions().position(sydney).title("Location not found so here's Australia"));
                    googleMap.moveCamera(CameraUpdateFactory.newLatLng(sydney));
                }
            }
        });

    }

    //Filters sensor data to improve accuracy
    //Based on code from [2]
    protected float[] filter(float[] in, float[] out) {

        if (out == null) return in;

        for (int i = 0; i < in.length; i++) {
            out[i] = out[i] + (ALPHA * (in[i] - out[i]));
        }

        return out;

    }

    //Initialize objects
    private void init() {

        //Initialize buffer
        userPath = new ArrayList<>();

        //Initialize sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        assert sensorManager != null;//Assume phone has step counter
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

    }

    protected void onResume() {
        super.onResume();
        //Register sensor listeners
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, magnetSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this, stepSensor);
        sensorManager.unregisterListener(this, accelSensor);
        sensorManager.unregisterListener(this, magnetSensor);
        sensorManager.unregisterListener(this, gyroSensor);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        //This will help up get direction the phone is pointing
        sensorChanged++;    //This will keep track of how many times the sensor has changed
        //Accel sensor
        if (event.sensor == accelSensor) {
            lastAccel = filter(event.values.clone(), lastAccel);
            accelSet = true;
        }

        //Magnet sensor
        else if (event.sensor == magnetSensor) {
            lastMagnet = filter(event.values.clone(), lastMagnet);
            magnetSet = true;
        }

        //The phone will have 30 tries to find the direction the phone is pointing
        if (sensorChanged < 30) {
            if (accelSet && magnetSet && geomagneticField != null) {
                SensorManager.getRotationMatrix(rotation, null, lastAccel, lastMagnet);
                SensorManager.getOrientation(rotation, orientation);

                float azimuthRadians = orientation[0];
                currentAngle = ((float) (Math.toDegrees(azimuthRadians) + 360) % 360) - geomagneticField.getDeclination();
                currentDirection = currentAngle;
                //Log.d("direction", "init: Hello World " + currentAngle); //For debugging purposes
            }

        }

        //double readings = event.values[2];

        if (event.sensor == gyroSensor) {
            zGyro = (event.values[2] / 5.69) * -(180.0 / Math.PI);
            currentDirection += zGyro;
            //Log.d("Print", "onSensorChanged: " + zGyro);
        }

        /*
        //This is where the gyroscope in the z axis is calculated
        long currentTime = System.currentTimeMillis();  //Keeps track of the system time
        long lastUpdate = currentTime - System.currentTimeMillis();    //This will keep track of how much time has passed between readings
        long updateTime = System.nanoTime();
        //Log.d("Print", "onSensorChanged: " + lastUpdate);
        //These are where the different gyro readings are held
        double zGyro1 = 0, zGyro2 = 0, zGyro3 = 0, zGyro4 = 0, zGyro5 = 0, zGyro6 = 0, zGyro7 = 0, zGyro8 = 0;
        //Log.d("Print", "onSensorChanged: Print " + currentTime);
        if (event.sensor == gyroSensor) {
            if (System.currentTimeMillis() - lastUpdate > 0) {   //1st gyro reading in the z-axis
                zGyro1 = event.values[2];
            } else if (System.currentTimeMillis() - lastUpdate > 199) {  //2nd gyro reading
                zGyro2 = event.values[2];
            } else if (System.currentTimeMillis() - lastUpdate > 299) {  //3rd gyro reading
                zGyro3 = event.values[2];
            } else if (System.currentTimeMillis() - lastUpdate > 399) {  //4th gyro reading
                zGyro4 = event.values[2];
            } else if (System.currentTimeMillis() - lastUpdate > 499) {  //5th gyro reading
                zGyro5 = event.values[2];
            } else if (System.currentTimeMillis() - lastUpdate > 599) {  //6th gyro reading
                zGyro6 = event.values[2];
                lastUpdate = System.currentTimeMillis(); //Since this is the last reading I want to collect, I reset the time counter to 0
                //by having it equal to the currentTime
            } else if (System.currentTimeMillis() - lastUpdate > 699) {  //7th gyro reading
                zGyro7 = event.values[2];
                lastUpdate = currentTime;
            } else if (System.currentTimeMillis() - lastUpdate > 799) {  //8th gyro reading
                zGyro8 = event.values[2];
                lastUpdate = currentTime;
            }
            //The sensitivity can be adjusted by messing with the time in the if statements

            //lastGyro = filter(event.values.cl, lastGyro); //Attempt to filter results
            //The accuracy can be adjusted by getting more readings and adding them to the average
            zGyro = ((zGyro1 + zGyro2 + zGyro3 + zGyro4 + zGyro5 + zGyro6 + zGyro7) / 6) * -(180.0 / 3.14);
            currentDirection += zGyro;  //Add changes in direction to the currentDirection
            //Log.d("direction", "onSensorChanged: " + currentDirection);
            //Log.d("tagTest", "onSensorChanged: " + zGyro);    //For debugging purposes

        }*/



        //If event is a step
        if (event.sensor == stepSensor && userPath.size() >= 1) {


            LatLng lastLocation = userPath.get(userPath.size() - 1);

            //Calculate new LatLng
            LatLng currentPos = SphericalUtil.computeOffset(lastLocation, stepLength, currentDirection);

            //Draw a line between last and current positions
            Polyline line = googleMap.addPolyline(new PolylineOptions().add(lastLocation, currentPos).width(25).color(Color.RED));

            userPath.add(currentPos);

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        //Do nothing
    }
}
