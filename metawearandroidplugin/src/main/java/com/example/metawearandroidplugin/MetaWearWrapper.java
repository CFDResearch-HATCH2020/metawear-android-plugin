package com.example.metawearandroidplugin;


import android.content.Context;
import android.app.Activity;
import android.app.Application;

import android.content.Intent;
import android.content.*;
import android.os.IBinder;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;

import bolts.Continuation;
import bolts.Task;


import com.mbientlab.metawear.DeviceInformation;
import com.mbientlab.metawear.MetaWearBoard;


import com.mbientlab.metawear.module.Led;
import com.mbientlab.metawear.android.BtleService;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.data.EulerAngles;
import com.mbientlab.metawear.module.SensorFusionBosch;
import com.mbientlab.metawear.module.SensorFusionBosch.*;
import com.mbientlab.metawear.module.Settings;
import com.mbientlab.metawear.module.Settings.BatteryState;
//import com.mbientlab.metawear.module.Logging;

import com.unity3d.player.UnityPlayer;

public class MetaWearWrapper extends Application
{
    private Activity m_MainActivity;
    private int connectionAttempts = 0;
    private BtleService.LocalBinder serviceBinder;
    private ServiceConnection mConnection;
    private final Context m_MainContext;
    private Led ledModule;
    private SensorFusionBosch sensorFusion;
    private static MetaWearWrapper m_instance;
    private String MW_MAC_ADDRESS;
    private MetaWearBoard board;
    private Settings settings;

    public MetaWearWrapper(Activity MainActivity, String address)
    {
        System.out.println("Attempting to Connect");
        m_MainActivity = MainActivity;
        m_MainContext = MainActivity.getApplicationContext();
        MW_MAC_ADDRESS =  address;
        mConnection = new ServiceConnection() {

            public void onServiceDisconnected(ComponentName name) {

            }

            public void onServiceConnected(ComponentName name, IBinder service) {

                System.out.println("Service Connected!");
                serviceBinder = (BtleService.LocalBinder) service;
                retrieveBoard();
            }
        };

        m_MainActivity.getApplicationContext().bindService(new Intent(m_MainActivity, BtleService.class), mConnection, Context.BIND_AUTO_CREATE);

    }

    private void sendEulerData(float pitch, float roll, float yaw)
    {

        String pitchStr=String.valueOf(pitch);
        String rollStr=String.valueOf(roll);
        String yawStr=String.valueOf(yaw);
        UnityPlayer.UnitySendMessage("BlueCharacter", "changePitchValue", pitchStr);
        UnityPlayer.UnitySendMessage("BlueCharacter", "changeRollValue", rollStr);
        UnityPlayer.UnitySendMessage("BlueCharacter", "changeYawValue", yawStr);
    }

    private void sendBatteryData(int charge)
    {
        String chargeStr=String.valueOf(charge);
        UnityPlayer.UnitySendMessage("BlueCharacter", "changeBatteryChargeValue", chargeStr);
    }

    private void makeBlink() {
        Led led = board.getModule(Led.class);
        if (led != null) {
            led.editPattern(Led.Color.BLUE, Led.PatternPreset.BLINK).repeatCount((byte) 10).commit();
            led.play();
        }

    }

    private void getBattery() {
        settings = board.getModule(Settings.class);
        settings.battery().read();
    }


    private void setupBattery() {
        settings = board.getModule(Settings.class);
        settings.battery().addRouteAsync(new RouteBuilder() {
            @Override
            public void configure(RouteComponent source) {
                source.stream(new Subscriber() {
                    @Override
                    public void apply(Data data, Object ... env) {
                        sendBatteryData(data.value(BatteryState.class).charge);
                        //System.out.println("battery state = " + data.value(BatteryState.class).charge);
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {
                settings.battery().read();
                return null;
            }
        });

    }


    private void setSensorFusionSettings() {
        sensorFusion = board.getModule(SensorFusionBosch.class);
        sensorFusion.configure().mode(Mode.NDOF).accRange(AccRange.AR_16G).gyroRange(GyroRange.GR_2000DPS).commit();
        sensorFusion.eulerAngles().addRouteAsync(new RouteBuilder() {
            @Override
            public void configure(RouteComponent source) {
                source.stream(new Subscriber() {
                    @Override
                    public void apply(Data data, Object... env) {
                        float p=data.value(EulerAngles.class).pitch();
                        float r=data.value(EulerAngles.class).roll();
                        float y=data.value(EulerAngles.class).yaw();

                        //m_MainActivity.onNewSensorData(p,r,y);
                        sendEulerData(p,r,y);
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {
                sensorFusion.eulerAngles().start();
                sensorFusion.start();
                return null;
            }
        });

        //logging = board.getModule(Logging.class);


        //logging.start(false);
    }


    public void retrieveBoard() {
        connectionAttempts++;
        final BluetoothManager btManager= (BluetoothManager) m_MainContext.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice= btManager.getAdapter().getRemoteDevice(MW_MAC_ADDRESS);

        // Create a MetaWear board object for the Bluetooth Device
        board= serviceBinder.getMetaWearBoard(remoteDevice);

        board.connectAsync().continueWith(new Continuation<Void, Void>() {
            @Override
            public Void then(Task<Void> task) throws Exception {
                if (task.isFaulted()) {
                    System.out.println("MainActivity: Failed to connect");
                    retrieveBoard();
                } else {
                    System.out.println("MainActivity: Connected After " + connectionAttempts + " Attempts");

                    makeBlink();
                    setSensorFusionSettings();
                    setupBattery();
                }
                return null;
            }
        });

        board.onUnexpectedDisconnect(new MetaWearBoard.UnexpectedDisconnectHandler() {
            @Override
            public void disconnected(int status) {
                System.out.println("MainActivity: Unexpectedly lost connection: " + status);
            }
        });
        board.readDeviceInformationAsync()
                .continueWith(new Continuation<DeviceInformation, Void>() {
                    @Override
                    public Void then(Task<DeviceInformation> task) throws Exception {
                        System.out.println("Device Information: " + task.getResult().toString());
                        return null;
                    }
                });

        System.out.println("Board Retrieved: " + board.getMacAddress());


    }
}

