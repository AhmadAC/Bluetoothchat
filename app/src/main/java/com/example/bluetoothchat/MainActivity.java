package com.example.bluetoothchat; // MUST MATCH YOUR PACKAGE NAME

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
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

    private ArrayList<String> chatMessages;
    private ArrayAdapter<String> chatAdapter;

    static final int STATE_LISTENING = 1;
    static final int STATE_CONNECTING = 2;
    static final int STATE_CONNECTED = 3;
    static final int STATE_CONNECTION_FAILED = 4;
    static final int STATE_MESSAGE_RECEIVED = 5;

    private static final String APP_NAME = "OfflineChat";
    // Standard UUID for Bluetooth Serial Port Profile (SPP)
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Map UI elements
        btnHost = findViewById(R.id.btnHost);
        btnConnect = findViewById(R.id.btnConnect);
        btnSend = findViewById(R.id.btnSend);
        statusText = findViewById(R.id.statusText);
        messageInput = findViewById(R.id.messageInput);
        chatListView = findViewById(R.id.chatListView);

        // Setup Chat List
        chatMessages = new ArrayList<>();
        chatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Check if device supports Bluetooth
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        requestBluetoothPermissions();

        // Button Listeners
        btnHost.setOnClickListener(v -> {
            if (checkPermissions()) {
                ServerClass serverClass = new ServerClass();
                serverClass.start();
            }
        });

        btnConnect.setOnClickListener(v -> {
            if (checkPermissions()) {
                showPairedDevices();
            }
        });

        btnSend.setOnClickListener(v -> {
            String msg = messageInput.getText().toString();
            if (!msg.trim().isEmpty() && sendReceive != null) {
                sendReceive.write(msg.getBytes());
                addMessage("Me: " + msg);
                messageInput.setText("");
            } else if (sendReceive == null) {
                Toast.makeText(this, "You are not connected to anyone!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 1);
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissions();
                return false;
            }
        }
        
        // Ask to turn on Bluetooth if it's off
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            return false;
        }
        return true;
    }

    private void addMessage(String message) {
        chatMessages.add(message);
        chatAdapter.notifyDataSetChanged();
        chatListView.setSelection(chatAdapter.getCount() - 1); // Scroll to bottom
    }

    @SuppressLint("MissingPermission")
    private void showPairedDevices() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        ArrayList<String> deviceNames = new ArrayList<>();
        ArrayList<BluetoothDevice> devices = new ArrayList<>();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice bt : pairedDevices) {
                deviceNames.add(bt.getName() + "\n" + bt.getAddress());
                devices.add(bt);
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
            new AlertDialog.Builder(this)
                    .setTitle("Select a Paired Device")
                    .setAdapter(adapter, (dialog, which) -> {
                        ClientClass clientClass = new ClientClass(devices.get(which));
                        clientClass.start();
                        statusText.setText("Status: Connecting...");
                    })
                    .show();
        } else {
            Toast.makeText(this, "No paired devices found! Pair in Android Settings first.", Toast.LENGTH_LONG).show();
        }
    }

    // Handles updates to the UI thread securely
    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case STATE_LISTENING:
                    statusText.setText("Status: Listening for connections...");
                    break;
                case STATE_CONNECTING:
                    statusText.setText("Status: Connecting...");
                    break;
                case STATE_CONNECTED:
                    statusText.setText("Status: Connected!");
                    break;
                case STATE_CONNECTION_FAILED:
                    statusText.setText("Status: Connection Failed / Dropped");
                    break;
                case STATE_MESSAGE_RECEIVED:
                    byte[] readBuff = (byte[]) msg.obj;
                    String tempMsg = new String(readBuff, 0, msg.arg1);
                    addMessage("Friend: " + tempMsg);
                    break;
            }
            return true;
        }
    });

    // 1. Thread for Hosting (Listening for incoming connections)
    private class ServerClass extends Thread {
        private BluetoothServerSocket serverSocket;

        @SuppressLint("MissingPermission")
        public ServerClass() {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            BluetoothSocket socket = null;
            handler.obtainMessage(STATE_LISTENING).sendToTarget();

            while (socket == null) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    handler.obtainMessage(STATE_CONNECTION_FAILED).sendToTarget();
                    break;
                }

                if (socket != null) {
                    handler.obtainMessage(STATE_CONNECTED).sendToTarget();
                    sendReceive = new SendReceive(socket);
                    sendReceive.start();
                    try {
                        serverSocket.close(); // Close server socket once connected to prevent others from joining
                    } catch (IOException e) { e.printStackTrace(); }
                    break;
                }
            }
        }
    }

    // 2. Thread for Connecting to a Host (Client)
    private class ClientClass extends Thread {
        private BluetoothSocket socket;

        @SuppressLint("MissingPermission")
        public ClientClass(BluetoothDevice device) {
            try {
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
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
                try {
                    socket.close();
                } catch (IOException ex) { ex.printStackTrace(); }
            }
        }
    }

    // 3. Thread for Sending and Receiving Data
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
            } catch (IOException e) {
                e.printStackTrace();
            }

            inputStream = tempIn;
            outputStream = tempOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    // Read incoming messages
                    bytes = inputStream.read(buffer);
                    handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                } catch (IOException e) {
                    handler.obtainMessage(STATE_CONNECTION_FAILED).sendToTarget();
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                // Send outgoing messages
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
