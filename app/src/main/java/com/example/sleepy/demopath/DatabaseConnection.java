package com.example.sleepy.demopath;

import android.os.AsyncTask;
import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Created by Kelvin on 2/24/2018.
 * Used to Asynchronously connect to the database
 */

public class DatabaseConnection extends AsyncTask<String, Void, Void> {

    String query;

    public DatabaseConnection(String q) {
        query = q;
    }

    @Override
    protected Void doInBackground(String... strings) {

        try {

            String dns = "on-campus-navigation.caqb3uzoiuo3.us-east-1.rds.amazonaws.com";
            String aClass = "net.sourceforge.jtds.jdbc.Driver";
            Class.forName(aClass).newInstance();

            Connection dbConnection = DriverManager.getConnection("jdbc:jtds:sqlserver://" + dns +
                    "/Campus-Navigation;user=Android;password=password");

            Log.w("Connection", "Open");
            Statement statement = dbConnection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);

            //Iterate through result and store in string
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append("Path: ").append(resultSet.getString(1)).append("\n");
            }

            Log.w("Result", sb.toString());

            dbConnection.close();

        } catch (Exception e) {
            //Exit on DB error
            e.printStackTrace();
            Log.w("Error Connection", "" + e.getMessage());
            return null;
        }

        return null;
    }
}
