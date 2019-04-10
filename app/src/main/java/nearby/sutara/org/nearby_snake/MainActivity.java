package nearby.sutara.org.nearby_snake;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends AppCompatActivity implements NearbyInfo {

    private static final String TAG = MainActivity.class.getSimpleName();

    public static final String CONSOLE_TYPE = "console";
    public static final String LOG_TYPE = "log";
    public static final String CLEAR_CONSOLE_TYPE = "clear-console";
    public static final String uiMessageEvent = "ui-message-event";

    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            };
    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 5432;

    private Toolbar toolbar;
    private TextView consoleView;
    private TextView console;
    private TextView logView;
    private EditText messageToSendField;
    private MainActivity that = this;
    private NearbyHelper helper = null;
    private boolean isTeacher = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setLogo(R.mipmap.ic_launcher);
        this.isTeacher = true;
        this.helper = NearbyHelper.getInstance(this, this, this.isTeacher);
    }

    protected void onStart() {
        super.onStart();
        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
            }
        }
        this.console = (TextView) findViewById(R.id.console);
        this.consoleView = (TextView) findViewById(R.id.consoleTextView);
        this.logView = (TextView) findViewById(R.id.logTextView);
        this.messageToSendField = (EditText) findViewById(R.id.messageToSend);
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter(uiMessageEvent));

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (that.isTeacher) {
                    that.notifyUI("Connecting as Teacher -> ", " --------> ", LOG_TYPE);
                } else {
                    that.notifyUI("Connecting as Student -> ", " --------> ", LOG_TYPE);
                }
            }
        }, 2000);

    }


    public void notifyUI(String message, String fromIP, String type) {

        final String consoleMessage = "[" + fromIP + "]: " + message + "\n";
        Log.d(TAG, "got message: " + consoleMessage);
        Intent intent = new Intent(uiMessageEvent);
        intent.putExtra("message", consoleMessage);
        intent.putExtra("type", type);
        LocalBroadcastManager.getInstance(that).sendBroadcast(intent);
    }

    protected void onDestroy() {
        super.onDestroy();
        this.helper.disconnectFromAllEndpoints();
        this.helper.stopAllEndpoints();
    }


    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }


    public void onButton(View view) {
        // Hide the keyboard
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);

        if (view.getId() == R.id.clearConsoleButton) {
            clearConsole();
        } else if (view.getId() == R.id.sendMessageButton) {
            sendMulticastMessage(getMessageToSend());
        }
    }

    private void clearConsole() {
        this.consoleView.setText("");
        this.consoleView.setTextColor(getResources().getColor(R.color.colorPrimaryDark));
    }

    public String getMessageToSend() {
        return this.messageToSendField.getText().toString();
    }

    private void sendMulticastMessage(String message) {
        notifyUI("sending text message:" + message, "--------->", CONSOLE_TYPE);
        Payload bytesPayload = Payload.fromBytes(message.getBytes());
        helper.sendToAllConnected(bytesPayload);
    }

    private void sendMulticastMessageToEndPoints(Set<EndPoint> endpoints, Payload bytesPayload) {
        Set<String> endPointIds = new TreeSet<String>();
        Iterator<EndPoint> it = endpoints.iterator();
        while(it.hasNext()) {
            EndPoint e = it.next();
            endPointIds.add(e.getId());
        }
        this.helper.send(bytesPayload, endPointIds);
    }


    public void outputTextToLog(String message) {
        this.logView.append(message);
        ScrollView logScrollView = ((ScrollView) this.logView.getParent());
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    public void outputTextToConsole(String message) {
        this.consoleView.append(message);
        ScrollView scrollView = ((ScrollView) this.consoleView.getParent());
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));


    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("message");
            String type = intent.getStringExtra("type");
            if (type.equals(CONSOLE_TYPE)) {
                that.outputTextToConsole(message);
            } else if (type.equals(LOG_TYPE)) {
                that.outputTextToLog(message);
            } else if (type.equals(CLEAR_CONSOLE_TYPE)) {
                that.clearConsole();
            }
        }
    };

    @Override
    public void onAdvertisingStarted(String name) {
        this.console.setText("Console - My Adv ID: " + name);
        notifyUI("onAdvertisingStarted " + name, "-------->", LOG_TYPE);
    }

    @Override
    public void onAdvertisingFailed() {
        notifyUI("onAdvertisingStarted ", "-------->", LOG_TYPE);
    }

    @Override
    public void onConnectionInitiated(EndPoint endpoint, ConnectionInfo connectionInfo) {
        helper.acceptConnection(endpoint);
        helper.setLocalAdvertiseName(endpoint.getName() + ".1");
        notifyUI("onConnectionInitiated " + endpoint.getName(), "-------->", LOG_TYPE);
    }

    @Override
    public Strategy getStrategy() {
        return Strategy.P2P_CLUSTER;
    }

    @Override
    public void onDiscoveryStarted() {
        notifyUI("onDiscoveryStarted ", "-------->", LOG_TYPE);
    }

    @Override
    public void onDiscoveryFailed() {
        notifyUI("onDiscoveryFailed ", "-------->", LOG_TYPE);
    }

    @Override
    public void onEndpointDiscovered(EndPoint endpoint) {
        this.helper.logD("onEndpointDiscovered id:" + endpoint.getId() + " name:" + endpoint.getName());
        notifyUI("onEndpointDiscovered id:" + endpoint.getId() + " name:" + endpoint.getName(), " --------->", LOG_TYPE);
    }

    @Override
    public void onConnectionFailed(EndPoint endpoint, int numberOfTimes) {
        notifyUI("onConnectionFailed id:" + endpoint.getId() + " name:" + endpoint.getName(), " --------->", LOG_TYPE);
        this.helper.setState(NearbyHelper.State.DISCOVERING);
    }

    @Override
    public void onEndpointConnected(EndPoint endpoint) {
        notifyUI("*******onEndpointConnected********* id:" + endpoint.getId() + " name:" + endpoint.getName(), " --------->", LOG_TYPE);
    }

    @Override
    public void onEndpointDisconnected(EndPoint endpoint) {
        notifyUI("********onEndpointDisconnected******  id:" + endpoint.getId() + " name:" + endpoint.getName(), " --------->", LOG_TYPE);
    }

    @Override
    public void onReceive(EndPoint endpoint, Payload payload) {
        if (payload.getType() == Payload.Type.BYTES) {
            handleBytesPayload(endpoint, payload);
        }
        // send payload to all connected addresses
        Set<EndPoint> allEstablishedConnections = this.helper.getConnectedEndpoints();
        allEstablishedConnections.remove(endpoint);
        sendMulticastMessageToEndPoints(allEstablishedConnections, payload);


    }

    @Override
    public void onStopAdvertising() {
        notifyUI("onStopAdvertising id:", " --------->", LOG_TYPE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
    }

    /**
     * Called when the user has accepted (or denied) our permission request.
     */
    @CallSuper
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            recreate();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public void handleBytesPayload(EndPoint endPoint, Payload payload) {
        String result = new String(payload.asBytes());
        notifyUI("message received from " + endPoint.getName() + " with content :" + result, "-------> ", CONSOLE_TYPE);
    }
}
