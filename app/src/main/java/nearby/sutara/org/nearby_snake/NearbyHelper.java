package nearby.sutara.org.nearby_snake;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

public class NearbyHelper {
    private static final String TAG = NearbyHelper.class.getSimpleName();
    /**
     * This service id lets us find other nearby devices that are interested in the same thing. Our
     * sample does exactly one thing, so we hardcode the ID.
     */
    private static final String SERVICE_ID = "nearby.sutara.org.nearby_snake";
    private static NearbyHelper _instance = null;
    private static CountDownTimer discoveryTimeOutTimer = null;

    private Context context;
    private int howManyTimeDiscoveryFail = 2;
    private int connectionFailedTimes = 0;
    public int discoveryFailedTimes = 0;
    private boolean mDiscoverAsTeacher = false;
    private String currentAdvertisingEndPoint = null;
    /**
     * The devices we've discovered near us.
     */
    private final Map<String, EndPoint> mDiscoveredEndpoints = new HashMap<>();
    /**
     * The devices we have pending connections to. They will stay pending until we call {
     * #acceptConnection(Endpoint)} or {#rejectConnection(Endpoint)}.
     */
    private final Map<String, EndPoint> mPendingConnections = new HashMap<>();
    /**
     * The devices we are currently connected to. For advertisers, this may be large. For discoverers,
     * there will only be one entry in this map.
     */
    private final Map<String, EndPoint> mEstablishedConnections = new HashMap<>();
    private NearbyInfo info;
    /**
     * Callbacks for payloads (bytes of data) sent from another device to us.
     */
    private final PayloadCallback mPayloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, Payload payload) {
                    logD(String.format("onPayloadReceived(endpointId=%s, payload=%s)", endpointId, payload));
                    _instance.info.onReceive(mEstablishedConnections.get(endpointId), payload);
                }

                @Override
                public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
                    logD(
                            String.format(
                                    "onPayloadTransferUpdate(endpointId=%s, update=%s)", endpointId, update));
                }
            };
    /**
     * Our handler to Nearby Connections.
     */
    private ConnectionsClient mConnectionsClient;
    private String advertisingName;
    private State mState = State.UNKNOWN;
    /**
     * True if we are asking a discovered device to connect to us. While we ask, we cannot ask another
     * device.
     */
    private boolean mTeacher = false;
    private boolean mIsConnecting = false;
    /**
     * Callbacks for connections to other devices.
     */
    private final ConnectionLifecycleCallback mConnectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    logD(
                            String.format(
                                    "onConnectionInitiated(endpointId=%s, endpointName=%s)",
                                    endpointId, connectionInfo.getEndpointName()));
                    EndPoint endpoint = new EndPoint(endpointId, connectionInfo.getEndpointName());
                    mPendingConnections.put(endpointId, endpoint);
                    info.onConnectionInitiated(endpoint, connectionInfo);
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result) {
                    logD(String.format("onConnectionResponse(endpointId=%s, result=%s)", endpointId, result));

                    // We're no longer connecting
                    mIsConnecting = false;

                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            // We're connected! Can now start sending and receiving data.
                            logD("Connected : " + endpointId + " ," + result.toString());
                            connectedToEndpoint(mPendingConnections.remove(endpointId));
                            _instance.setState(NearbyHelper.State.STOP_DISCOVERING);
                            _instance.setDiscoveryAsTeacher(false); //reset
                            if (_instance.mTeacher) {
                                _instance.setState(NearbyHelper.State.STOP_ADVERTISING);
                                _instance.setTeacher(false); // no longer teacher
                            } else {
                                if (ismAdvertisingForChild) {
                                    logD("Connected Child: " + endpointId + " ," + result.toString());
                                    _instance.setState(State.STOP_ADVERTISING);
                                    _instance.setAdvertisingForChild(false); //reset
                                } else {
                                    _instance.setState(NearbyHelper.State.ADVERTISING_FOR_CHILD);
                                }
                            }
                            break;
                        case ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT:
                            // We're connected! Can now start sending and receiving data.
                            logD("Already Connected : " + endpointId + " ," + result.toString());
                            mIsConnecting = true;
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                            // The connection was rejected by one or both sides.
                            logW(
                                    String.format(
                                            "Connection failed. Received status %s.",
                                            result.getStatus()));
                            _instance.info.onConnectionFailed(mPendingConnections.remove(endpointId), _instance.connectionFailedTimes++);
                            break;
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            // The connection broke before it was able to be accepted.
                            break;
                        default:
                            // Unknown status code
                    }
                }

                /** Returns {@code true} if we're currently attempting to connect to another device. */
                protected final boolean isConnecting() {
                    return mIsConnecting;
                }

                private void connectedToEndpoint(EndPoint endpoint) {
                    logD(String.format("connectedToEndpoint(endpoint=%s)", endpoint));
                    mEstablishedConnections.put(endpoint.getId(), endpoint);
                    _instance.info.onEndpointConnected(endpoint);
                    _instance.connectionFailedTimes = 0;
                }

                private void disconnectedFromEndpoint(EndPoint endpoint) {
                    logD(String.format("disconnectedFromEndpoint(endpoint=%s)", endpoint));
                    mEstablishedConnections.remove(endpoint.getId());
                    _instance.info.onEndpointDisconnected(endpoint);

                    // i am 1.1 and got message disconnected from 1.1.1
                    _instance.info.notifyMessage("local advertising point:" + _instance.getLocalAdvertiseName());
                    _instance.info.notifyMessage("disconnected from:" + endpoint.getName());

                    if ((_instance.getLocalAdvertiseName() + ".1").equals(endpoint.getName())) {
                        _instance.howManyTimeDiscoveryFail = 5;
                        _instance.setState(State.DISCOVERING_AS_TEACHER);
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    if (!mEstablishedConnections.containsKey(endpointId)) {
                        logW("Unexpected disconnection from endpoint " + endpointId);
                        return;
                    }
                    disconnectedFromEndpoint(mEstablishedConnections.get(endpointId));
                }
            };
    /**
     * True if we are discovering.
     */
    private boolean mIsDiscovering = false;
    /**
     * True if we are advertising.
     */
    private boolean mIsAdvertising = false;
    private boolean ismAdvertisingForChild = false;


    /**
     * True if we are discovering.
     */
    public boolean mIsDiscovered = false;


    public boolean isManuallyStoppedDiscovery = false;

    private NearbyHelper(NearbyInfo info, Context context) {
        this.info = info;
        this.context = context;
        mConnectionsClient = Nearby.getConnectionsClient(this.context);
    }

    public static NearbyHelper getInstance(NearbyInfo info, Context context, boolean shouldStartAdv) {
        if (_instance == null) {
            _instance = new NearbyHelper(info, context);
            _instance.setBluetooth(true);
            if (shouldStartAdv) {
                _instance.setTeacher(true);
                _instance.setState(State.DISCOVERING_AS_TEACHER);
            } else {
                _instance.setTeacher(false);
                _instance.setState(State.DISCOVERING);
            }
        }
        return _instance;
    }

    public void setAdvertisingForChild(boolean b) {
        this.ismAdvertisingForChild = b;
    }

    public void setDiscoveryAsTeacher(boolean b) {
        mDiscoverAsTeacher = b;
    }

    public Map<String, EndPoint> getDiscoveredEndpoints() {
        return mDiscoveredEndpoints;
    }


    protected void logD(String msg) {
        Log.d(TAG, msg);
    }

    protected void logW(String msg) {
        Log.w(TAG, msg);
    }


    protected void logW(String msg, Throwable e) {
        Log.w(TAG, msg, e);
    }

    protected void logE(String msg, Throwable e) {
        Log.e(TAG, msg, e);
    }

    public String getLocalAdvertiseName() {
        if (this.advertisingName == null) {
            this.advertisingName = "1";
        }
        return this.advertisingName;
    }

    public void setLocalAdvertiseName(String name) {
        this.advertisingName = name;
    }


    public void startAdvertisingForChild() {
        mIsAdvertising = true;
        ismAdvertisingForChild = true;
        final String localEndpointName = this.getLocalAdvertiseName();

        logD("startAdvertising ... end point " + localEndpointName + " with strategy " + info.getStrategy());
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(info.getStrategy()).build();

        mConnectionsClient
                .startAdvertising(
                        localEndpointName,
                        SERVICE_ID,
                        mConnectionLifecycleCallback,
                        advertisingOptions)
                .addOnSuccessListener(
                        new OnSuccessListener<Void>() {
                            @Override
                            public void onSuccess(Void unusedResult) {
                                logD("Now advertising endpoint " + localEndpointName);
                                info.onAdvertisingStarted(localEndpointName);
                            }
                        })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                mIsAdvertising = false;
                                ismAdvertisingForChild = false;
                                logW("startAdvertising() failed.", e);
                                info.onAdvertisingFailed();
                            }
                        });
    }

    /**
     * Sets the device to advertising mode. It will broadcast to other devices in discovery mode.
     * Either {onAdvertisingStarted()} or {onAdvertisingFailed()} will be called once
     * we've found out if we successfully entered this mode.
     */
    public void startAdvertising() {
        mIsAdvertising = true;
        final String localEndpointName = this.getLocalAdvertiseName();

        logD("startAdvertising ... end point " + localEndpointName + " with strategy " + info.getStrategy());
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(info.getStrategy()).build();

        mConnectionsClient
                .startAdvertising(
                        localEndpointName,
                        SERVICE_ID,
                        mConnectionLifecycleCallback,
                        advertisingOptions)
                .addOnSuccessListener(
                        new OnSuccessListener<Void>() {
                            @Override
                            public void onSuccess(Void unusedResult) {
                                logD("Now advertising endpoint " + localEndpointName);
                                info.onAdvertisingStarted(localEndpointName);
                            }
                        })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                mIsAdvertising = false;
                                logW("startAdvertising() failed.", e);
                                info.onAdvertisingFailed();
                            }
                        });
    }

    /**
     * Returns {@code true} if currently advertising.
     */
    public boolean isAdvertising() {
        return mIsAdvertising;
    }

    /**
     * Called when a pending connection with a remote endpoint is created. Use {ConnectionInfo}
     * for metadata about the connection (like incoming vs outgoing, or the authentication token). If
     * we want to continue with the connection, call {acceptConnection(Endpoint)}. Otherwise,
     * call {rejectConnection(Endpoint)}.
     */
    protected void onConnectionInitiated(EndPoint endpoint, ConnectionInfo connectionInfo) {
    }

    /**
     * Accepts a connection request.
     */
    public void acceptConnection(final EndPoint endpoint) {
        mConnectionsClient
                .acceptConnection(endpoint.getId(), mPayloadCallback)
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                logW("acceptConnection() failed.", e);
                            }
                        });
    }

    /**
     * Sets the device to discovery mode. It will now listen for devices in advertising mode. Either
     * {onDiscoveryStarted()} or {onDiscoveryFailed()} will be called once we've found
     * out if we successfully entered this mode.
     */
    protected void startDiscovering() {
        logD("calling startDiscovering ...with strategy" + info.getStrategy());
        if (discoveryTimeOutTimer == null && !mIsDiscovering) {
            startDiscoveryTimeOutTimer(10 * 1000);
        }
        mIsDiscovering = true;
        mIsDiscovered = false;
        mDiscoveredEndpoints.clear();
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(info.getStrategy()).build();

        mConnectionsClient
                .startDiscovery(
                        SERVICE_ID,
                        new EndpointDiscoveryCallback() {
                            @Override
                            public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                                logD(
                                        String.format(
                                                "onEndpointFound(endpointId=%s, serviceId=%s, endpointName=%s)",
                                                endpointId, info.getServiceId(), info.getEndpointName()));

                                logD("service Id:" + SERVICE_ID);
                                if (SERVICE_ID.equals(info.getServiceId())) {
                                    _instance.setLocalAdvertiseName(info.getEndpointName() + ".1");
                                    mIsDiscovered = true;
                                    discoveryFailedTimes = 0;
                                    if (discoveryTimeOutTimer != null) {
                                        discoveryTimeOutTimer.cancel();
                                        discoveryTimeOutTimer = null;
                                    }
                                    EndPoint endpoint = new EndPoint(endpointId, info.getEndpointName());
                                    mDiscoveredEndpoints.put(endpointId, endpoint);
                                    _instance.info.onEndpointDiscovered(endpoint);

                                    if (!_instance.isConnecting()) {
                                        _instance.connectToEndpoint(new EndPoint(endpointId, info.getEndpointName()));
                                    }


                                }

                            }

                            @Override
                            public void onEndpointLost(String endpointId) {
                                logD(String.format("onEndpointLost(endpointId=%s)", endpointId));
                            }
                        },
                        discoveryOptions)
                .addOnSuccessListener(
                        new OnSuccessListener<Void>() {
                            @Override
                            public void onSuccess(Void unusedResult) {
                                _instance.info.onDiscoveryStarted();
                            }
                        })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                _instance.failedDiscovery();
                            }
                        });

    }

    /**
     * Returns {@code true} if currently discovering.
     */
    public boolean isDiscovering() {
        return mIsDiscovering;
    }

    /**
     * Stops discovery.
     */
    public void stopDiscovering() {
        logD("stopDiscovering ...");
        mIsDiscovering = false;
        if (discoveryTimeOutTimer != null) {
            discoveryTimeOutTimer.cancel();
            discoveryTimeOutTimer = null;
        }

        mConnectionsClient.stopDiscovery();
    }

    /**
     * Stops advertising.
     */
    public void stopAdvertising() {
        logD("stopAdvertising ...");
        mIsAdvertising = false;
        mConnectionsClient.stopAdvertising();
        info.onStopAdvertising();
    }

    /**
     * Disconnects from all currently connected endpoints.
     */
    public void disconnectFromAllEndpoints() {
        logD("disconnectFromAllEndpoints ...");
        for (EndPoint endpoint : mEstablishedConnections.values()) {
            mConnectionsClient.disconnectFromEndpoint(endpoint.getId());
        }
        mEstablishedConnections.clear();
        if (mEstablishedConnections != null) {
            logD("cleared mEstablishedConnections ... ->" + mEstablishedConnections.size());
        }

    }

    /**
     * Returns {@code true} if we're currently attempting to connect to another device.
     */
    public final boolean isConnecting() {
        return mIsConnecting;
    }

    /**
     * Sends a connection request to the endpoint. Either {onConnectionInitiated(Endpoint,
     * ConnectionInfo)} or {onConnectionFailed(Endpoint)} will be called once we've found out
     * if we successfully reached the device.
     */
    public void connectToEndpoint(final EndPoint endpoint) {

        logD("Sending a connection request to endpoint " + endpoint);
        // Mark ourselves as connecting so we don't connect multiple times
        // Disconnect from all connected endpoints
        // _instance.disconnectFromAllEndpoints();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                mIsConnecting = true;

                // Ask to connect
                mConnectionsClient
                        .requestConnection(_instance.getLocalAdvertiseName(), endpoint.getId(), mConnectionLifecycleCallback)
                        .addOnFailureListener(
                                new OnFailureListener() {
                                    @Override
                                    public void onFailure(Exception e) {
                                        logW("requestConnection() failed.", e);
                                        mIsConnecting = false;
                                        _instance.info.onConnectionFailed(endpoint, _instance.connectionFailedTimes++);
                                    }
                                });
            }
        }, 1 * 1000);


    }

    public void resetFailedConnectionTimes() {
        _instance.connectionFailedTimes = 0;
    }

    public void disconnectFromEndPoint(String endPointId) {
        logD("disconnectFromEndPoint ..." + endPointId);
        if (mEstablishedConnections != null) {
            logD("connections before removing endpoint mEstablishedConnections ... ->" + mEstablishedConnections.size());
        }

        for (EndPoint endpoint : mEstablishedConnections.values()) {
            if (endpoint.getId().equals(endPointId)) {
                mConnectionsClient.disconnectFromEndpoint(endpoint.getId());
                mEstablishedConnections.remove(endPointId);
                if (mEstablishedConnections != null) {
                    logD("connections mEstablishedConnections ... ->" + mEstablishedConnections.size());
                }
                break;
            }
        }
    }

    /**
     * Returns a list of currently connected endpoints.
     */
    public Set<EndPoint> getConnectedEndpoints() {
        Set<EndPoint> endpoints = new HashSet<>();
        endpoints.addAll(mEstablishedConnections.values());
        return endpoints;
    }


    // Send

    /**
     * Sends a {Payload} to all currently connected endpoints.
     *
     * @param payload The data you want to send.
     */
    public void sendToAllConnected(Payload payload) {
        send(payload, mEstablishedConnections.keySet());
    }

    public List<Map<String, String>> getAllEstablishedConnections() {
        List allEndPoints = new ArrayList();
        Collection<EndPoint> endPoints = this.mEstablishedConnections.values();
        Iterator<EndPoint> it = endPoints.iterator();
        while (it.hasNext()) {
            EndPoint e = (EndPoint) it.next();
            Map<String, String> mapping = new HashMap<String, String>();
            mapping.put("endPointId", e.getId());
            mapping.put("endPointName", e.getName());
            allEndPoints.add(mapping);
        }
        return allEndPoints;
    }

    /**
     * Sends a {@link Payload} to one connected endpoint.
     *
     * @param payload The data you want to send.
     */
    public void sendToEndPoint(Payload payload, String key) {
        send(payload, key);
    }

    private void send(Payload payload, String endpoint) {
        mConnectionsClient
                .sendPayload(endpoint, payload)
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                logW("sendPayload() failed.", e);
                            }
                        });
    }

    public void send(Payload payload, Set<String> endpoints) {
        Set<String> tree_Set = new TreeSet<String>(endpoints);
        logD("all connected end points: " + tree_Set);
        logD("sending message" + payload.toString() + " to all end points");
        if (!tree_Set.isEmpty()) {
            mConnectionsClient
                    .sendPayload(new ArrayList<>(endpoints), payload)
                    .addOnFailureListener(
                            new OnFailureListener() {
                                @Override
                                public void onFailure(Exception e) {
                                    logW("sendPayload() failed.", e);
                                }
                            });
        }
    }


    public void stopAllEndpoints() {
        mConnectionsClient.stopAllEndpoints();
        mIsAdvertising = false;
        mIsDiscovering = false;
        mIsDiscovered = false;
        mIsConnecting = false;
        discoveryFailedTimes = 0;
        mDiscoveredEndpoints.clear();
        mPendingConnections.clear();
        mEstablishedConnections.clear();
        if (discoveryTimeOutTimer != null) {
            discoveryTimeOutTimer.cancel();
            discoveryTimeOutTimer = null;
        }
    }

    public String getState() {
        switch (mState) {
            case DISCOVERING:
                return "DISCOVERING";
            case STOP_DISCOVERING:
                return "STOP_DISCOVERING";
            case ADVERTISING:
                return "ADVERTISING";
            case ADVERTISING_FOR_CHILD:
                return "ADVERTISING_FOR_CHILD";
            case CONNECTED:
                return "CONNECTED";
            case UNKNOWN:
                return "UNKNOWN";
            default:
                // no-op
                return "";
        }
    }

    public void setTeacher(boolean teacher) {
        mTeacher = teacher;
    }

    public void setState(State state) {
        if (mState == state) {
            logW("State set to " + state + " but already in that state");
            return;
        }

        logD("State set to " + state);
        State oldState = mState;
        mState = state;
        onStateChanged(state);
    }

    /**
     * State has changed.
     *
     * @param newState The new state we're now in
     */
    private void onStateChanged(State newState) {
        // Update Nearby Connections to the new state.
        switch (newState) {
            case DISCOVERING_AS_TEACHER:
                _instance.setDiscoveryAsTeacher(true);
                logD("On state change DISCOVERING_AS_TEACHER");
                if (isAdvertising()) {
                    stopAdvertising();
                }
                startDiscovering();
                break;

            case DISCOVERING:
                logD("On state change DISCOVERING");
                if (isAdvertising()) {
                    stopAdvertising();
                }
                // disconnectFromAllEndpoints();
                startDiscovering();
                break;
            case STOP_DISCOVERING:
                stopDiscovering();
                break;
            case STOP_ADVERTISING:
                stopAdvertising();
                break;
            case ADVERTISING:
                if (isDiscovering()) {
                    stopDiscovering();
                }
                // disconnectFromAllEndpoints();
                startAdvertising();
                break;
            case ADVERTISING_FOR_CHILD:
                if (isDiscovering()) {
                    stopDiscovering();
                }
                // disconnectFromAllEndpoints();
                startAdvertisingForChild();
                break;
            case CONNECTED:
                if (isDiscovering()) {
                    stopDiscovering();
                }
                break;
            case UNKNOWN:
            case DISCONNECT:
                stopAllEndpoints();
                break;
            default:
                // no-op
                break;
        }
    }

    /**
     * States that the UI goes through.
     */
    public enum State {
        UNKNOWN,
        DISCOVERING_AS_TEACHER,
        DISCOVERING,
        ADVERTISING,
        ADVERTISING_FOR_CHILD,
        CONNECTED,
        STOP_DISCOVERING,
        STOP_ADVERTISING,
        DISCONNECT
    }

    private void startDiscoveryTimeOutTimer(final int seconds) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                discoveryTimeOutTimer = new CountDownTimer(seconds, 1000) {
                    public void onTick(long millisUntilFinished) {
                        logD("ticking..." + millisUntilFinished);
                    }

                    public void onFinish() {
                        discoveryTimeOutTimer = null;
                        if (!_instance.mIsDiscovered) {
                            _instance.failedDiscovery();
                        }
                    }
                }.start();

            }
        });
    }

    private void failedDiscovery() {
        discoveryFailedTimes++;
        mIsDiscovering = false;
        mIsDiscovered = false;
        logW("startDiscovering() failed. times ..." + discoveryFailedTimes);
        if (discoveryTimeOutTimer != null) {
            discoveryTimeOutTimer.cancel();
            discoveryTimeOutTimer = null;
        }
        _instance.info.onDiscoveryFailed();

        if (!isManuallyStoppedDiscovery && _instance.mDiscoverAsTeacher && discoveryFailedTimes >= howManyTimeDiscoveryFail) {
            _instance.resetAsTeacher();
        } else if (!isManuallyStoppedDiscovery) {
            _instance.resetDiscovery();
        }
    }

    public void resetAsTeacher() {
        logD("resetAsTeacher");
        _instance.setState(State.STOP_DISCOVERING);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                _instance.setState(State.ADVERTISING);
            }
        }, 5 * 1000);
    }

    public void resetOnConnectionFailed() {
        if(_instance.mDiscoverAsTeacher) {
            _instance.resetAsTeacher();
        } else {
            _instance.resetDiscovery();
        }
    }

    public void resetDiscovery() {
        logD("resetDiscovery");
        stopDiscovering();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                _instance.startDiscovering();
            }
        }, 5 * 1000);
    }

    private static int getRandomNumberInRange(int min, int max) {
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }


    public static boolean setBluetooth(boolean enable) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        boolean isEnabled = bluetoothAdapter.isEnabled();
        if (enable && !isEnabled) {
            Log.d(TAG, "setBluetooth: enable");
            return bluetoothAdapter.enable();
        } else if (!enable && isEnabled) {
            Log.d(TAG, "setBluetooth: disable");
            return bluetoothAdapter.disable();
        }
        // No need to change bluetooth state
        return true;
    }
}
