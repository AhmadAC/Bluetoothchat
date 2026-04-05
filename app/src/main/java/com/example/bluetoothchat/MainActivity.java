package com.example.bluetoothchat; 

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private Button btnHost, btnConnect, btnSend;
    private TextView statusText;
    private EditText messageInput;
    private ListView chatListView;

    private BluetoothAdapter bluetoothAdapter;
    private SendReceive sendReceive;

    // Chat List Variables
    private ArrayList<String> chatMessages;
    private ArrayAdapter<String> chatAdapter;

    // Device Scanner Variables
    private ArrayList<String> deviceList;
    private ArrayList<BluetoothDevice> deviceObjects;
    private ArrayAdapter<String> deviceAdapter;

    static final int STATE_LISTENING = 1;
    static final int STATE_CONNECTING = 2;
    static final int STATE_CONNECTED = 3;
    static final int STATE_CONNECTION_FAILED = 4;
    static final int STATE_MESSAGE_RECEIVED = 5;

    private static final String APP_NAME = "OfflineChat";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnHost = findViewById(R.id.btnHost);
        btnConnect = findViewById(R.id.btnConnect);
        btnSend = findViewById(R.id.btnSend);
        statusText = findViewById(R.id.statusText);
        messageInput = findViewById(R.id.messageInput);
        chatListView = findViewById(R.id.chatListView);

        chatMessages = new ArrayList<>();
        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        requestBluetoothPermissions();

        // Register the BroadcastReceiver for discovering new devices
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(deviceReceiver, filter);

        // Setup Device Scanner Adapter
        deviceList = new ArrayList<>();
        deviceObjects = new ArrayList<>();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);

        btnHost.setOnClickListener(v -> {
            if (checkPermissions()) {
                makeDiscoverableAndHost();
            }
        });

        btnConnect.setOnClickListener(v -> {
            if (checkPermissions()) {
                showDeviceListAndScan();
            }
        });

        btnSend.setOnClickListener(v -> {
            String msg = messageInput.getText().toString();
            if (!msg.trim().isEmpty() && sendReceive != null) {
                sendReceive.write(msg.getBytes());
                addMessage("Me: " + msg);
                messageInput.setText("");
            } else if (sendReceive == null) {
                Toast.makeText(this, "Not connected!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(deviceReceiver);
        } catch (IllegalArgumentException e) { e.printStackTrace(); }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1);
        }
    }

    private boolean checkPermissions() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void makeDiscoverableAndHost() {
        // Asks the user to make this phone visible to others for 5 minutes
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(discoverableIntent);

        ServerClass serverClass = new ServerClass();
        serverClass.start();
    }

    @SuppressLint("MissingPermission")
    private void showDeviceListAndScan() {
        deviceList.clear();
        deviceObjects.clear();

        // 1. Load already paired devices
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null && pairedDevices.size() > 0) {
            deviceList.add("--- PAIRED DEVICES ---");
            deviceObjects.add(null); 
            for (BluetoothDevice bt : pairedDevices) {
                deviceList.add(bt.getName() + "\n" + bt.getAddress());
                deviceObjects.add(bt);
            }
        }

        // 2. Add header for new devices
        deviceList.add("--- NEW DEVICES (Scanning...) ---");
        deviceObjects.add(null);
        deviceAdapter.notifyDataSetChanged();

        // 3. Show Popup
        new AlertDialog.Builder(this)
                .setTitle("Select a Device")
                .setAdapter(deviceAdapter, (dialog, which) -> {
                    BluetoothDevice target = deviceObjects.get(which);
                    if (target == null) return; // User clicked a header text

                    bluetoothAdapter.cancelDiscovery(); // Stop scanning to save battery
                    ClientClass clientClass = new ClientClass(target);
                    clientClass.start();
                    statusText.setText("Status: Connecting...");
                })
                .setOnDismissListener(dialog -> {
                    if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
                })
                .show();

        // 4. Start scanning the airwaves
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

    // Broadcast Receiver that catches devices as they are found in the air
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getName() != null) {
                    String deviceInfo = device.getName() + "\n" + device.getAddress();
                    
                    // Add device to list if it's not already there
                    if (!deviceList.contains(deviceInfo)) {
                        deviceList.add(deviceInfo);
                        deviceObjects.add(device);
                        deviceAdapter.notifyDataSetChanged();
                    }
                }
            }
        }
    };

    private void addMessage(String message) {
        chatMessages.add(message);
        chatAdapter.notifyDataSetChanged();
        chatListView.setSelection(chatAdapter.getCount() - 1);
    }

    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case STATE_LISTENING: statusText.setText("Status: Listening..."); break;
                case STATE_CONNECTING: statusText.setText("Status: Connecting/Pairing..."); break;
                case STATE_CONNECTED: statusText.setText("Status: Connected!"); break;
                case STATE_CONNECTION_FAILED: statusText.setText("Status: Connection Failed"); break;
                case STATE_MESSAGE_RECEIVED:
                    byte[] readBuff = (byte[]) msg.obj;
                    String tempMsg = new String(readBuff, 0, msg.arg1);
                    addMessage("Friend: " + tempMsg);
                    break;
            }
            return true;
        }
    });

    // 1. Server Thread (Host)
    private class ServerClass extends Thread {
        private BluetoothServerSocket serverSocket;
        @SuppressLint("MissingPermission")
        public ServerClass() {
            try { serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID); } 
            catch (IOException e) { e.printStackTrace(); }
        }
        public void run() {
            BluetoothSocket socket = null;
            handler.obtainMessage(STATE_LISTENING).sendToTarget();
            while (socket == null) {
                try { socket = serverSocket.accept(); } 
                catch (IOException e) { handler.obtainMessage(STATE_CONNECTION_FAILED).sendToTarget(); break; }
                
                if (socket != null) {
                    handler.obtainMessage(STATE_CONNECTED).sendToTarget();
                    sendReceive = new SendReceive(socket);
                    sendReceive.start();
                    try { serverSocket.close(); } catch (IOException e) { e.printStackTrace(); }
                    break;
                }
            }
        }
    }

    // 2. Client Thread (Join)
    private class ClientClass extends Thread {
        private BluetoothSocket socket;
        @SuppressLint("MissingPermission")
        public ClientClass(BluetoothDevice device) {
            try { socket = device.createRfcommSocketToServiceRecord(MY_UUID); } 
            catch (IOException e) { e.printStackTrace(); }
        }
        @SuppressLint("MissingPermission")
        public void run() {
            try {
                handler.obtainMessage(STATE_CONNECTING).sendToTarget();
                socket.connect();
                handler.obtainMessage(STATE_CONNECTED).sendToTarget();
                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                e.printStackTrace();
                handler.obtainMessage(STATE_CONNECTION_FAILED).sendToTarget();
                try { socket.close(); } catch (IOException ex) { ex.printStackTrace(); }
            }
        }
    }

    // 3. Send/Receive Thread
    private class SendReceive extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public SendReceive(BluetoothSocket socket) {
            bluetoothSocket = socket;
            InputStream tempIn = null;
            OutputStream tempOut = null;
            try {
                tempIn = bluetoothSocket.getInputStream();
                tempOut = bluetoothSocket.getOutputStream();
            } catch (IOException e) { e.printStackTrace(); }
            inputStream = tempIn;
            outputStream = tempOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                } catch (IOException e) {
                    handler.obtainMessage(STATE_CONNECTION_FAILED).sendToTarget();
                    break;
                }
            }
        }
        
        // This is the complete, correct write method
        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
