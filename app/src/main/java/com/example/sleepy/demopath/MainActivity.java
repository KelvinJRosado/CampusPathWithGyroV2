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
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

//Implement Google Maps interface
public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, SensorEventListener {
    /*
    References
    [1] http://www.codingforandroid.com/2011/01/using-orientation-sensors-simple.html
    [2] https://www.built.io/blog/applying-low-pass-filter-to-android-sensor-s-readings
    */

    private final float ALPHA = (float) 0.25;
    //Reference to context
    Context thisContext = this;
    long currentTime, nextTime;
    //Map API
    private FusedLocationProviderClient fusedLocationProviderClient;//Gets starting location
    private GoogleMap googleMap;//Reference to map
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
    private double zGyroTotal;
    private boolean getCompass = false;
    private double currentDirection;
    private int sensorChanged;
    private GeomagneticField geomagneticField;

    private ArrayList<TimedLocation> userPath;

    private Button btSendPath;

    private double stepLength = 0.7088336;//<-Sleepy's step, based off of calculations with a ruler: Kelvin's step ->0.6923532; in meters

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
                    userPath.add(new TimedLocation((start)));

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

        btSendPath = findViewById(R.id.btSendTest);

        btSendPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                //Serialize list of points
                ArrayList<JSONObject> pathTemp = new ArrayList<>();

                for (int i = 0; i < userPath.size(); i += 2) {
                    try {
                        JSONObject temp = new JSONObject();
                        temp.put("Latitude", userPath.get(i).getLocation().latitude);
                        temp.put("Longitude", userPath.get(i).getLocation().longitude);
                        temp.put("Time", userPath.get(i).getTimeStamp());

                        pathTemp.add(temp);

                    } catch (JSONException e) {
                        //Exit on JSON error
                        e.printStackTrace();
                        Toast.makeText(thisContext, e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                JSONArray pathJSON = new JSONArray(pathTemp);

                String st = "'" + pathJSON.toString() + "'";

                //Send to database
                String query = "INSERT INTO My_Test_Table (User_Path)" +
                        " VALUES (" + st.toString() + ");";
                new DatabaseConnection(query).execute();

                //Clear map and buffer
                googleMap.clear();

                TimedLocation temp = userPath.get(userPath.size() - 1);//Take last point of previous path as start of next
                userPath.clear();
                userPath.add(temp);

                googleMap.addMarker(new MarkerOptions().position(temp.getLocation()).title("Start location"));
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(temp.getLocation(), 20.0f));

                //Notify user of success or failure
                String ss = "Path sent to server";
                Toast.makeText(thisContext, ss, Toast.LENGTH_SHORT).show();


            }
        });

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
        if (sensorChanged < 30 || getCompass == true) {
            if (accelSet && magnetSet && geomagneticField != null && getCompass == true) {
                for (int i = 0; i < 5; i++) {
                    SensorManager.getRotationMatrix(rotation, null, lastAccel, lastMagnet);
                    SensorManager.getOrientation(rotation, orientation);

                    float azimuthRadians = orientation[0];
                    currentAngle = ((float) (Math.toDegrees(azimuthRadians) + 360) % 360) - geomagneticField.getDeclination();
                    currentDirection = currentAngle;

                    Log.d("direction", "init: " + currentDirection); //For debugging purposes
                }
                getCompass = false;
            }
            else if (accelSet && magnetSet && geomagneticField != null) {
                SensorManager.getRotationMatrix(rotation, null, lastAccel, lastMagnet);
                SensorManager.getOrientation(rotation, orientation);

                float azimuthRadians = orientation[0];
                currentAngle = ((float) (Math.toDegrees(azimuthRadians) + 360) % 360) - geomagneticField.getDeclination();
                currentDirection = currentAngle;
            }

        }

        //double readings = event.values[2];

        if (event.sensor == gyroSensor) {
            if (event.values[2] <= -0.06 || event.values[2] >= 0.06) {  //Filter out these results
                zGyro = (event.values[2] / 5.89111) * -(180.0 / Math.PI); //The smaller the number, the more sensitive the results are
                currentDirection += zGyro;
                zGyroTotal += zGyro;    //Stores how much movement the gyroscopes have detected
                if ((45 % zGyroTotal) == 45) {  //If the gyroscopes have moved more than 45 degrees
                    zGyroTotal = 0;             //We check the direction using the magnet meter
                    getCompass = true;          //Allows us to check the magnet meter
                    //Log.d("Print", "onSensorChanged: zGyro: " + zGyroTotal + " zGyroMod: " + (60 % zGyroTotal));  //For testing purposes
                }

            }
        }



        //If event is a step
        if (event.sensor == stepSensor && userPath.size() >= 1) {


            LatLng lastLocation = userPath.get(userPath.size() - 1).getLocation();

            //Calculate new LatLng
            LatLng currentPos = SphericalUtil.computeOffset(lastLocation, stepLength, currentDirection);

            //Draw a line between last and current positions
            Polyline line = googleMap.addPolyline(new PolylineOptions().add(lastLocation, currentPos).width(10).color(Color.RED));

            //Move map to new location
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 20.0f));

            userPath.add(new TimedLocation(currentPos));

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        //Do nothing
    }
}
