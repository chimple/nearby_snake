package nearby.sutara.org.nearby_snake;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;

public interface NearbyInfo {
    /**
     * Called when advertising successfully starts. Override this method to act on the event.
     */
    void onAdvertisingStarted(String name);

    /**
     * Called when advertising fails to start. Override this method to act on the event.
     */
    void onAdvertisingFailed();

    /**
     * Called when a pending connection with a remote endpoint is created. Use {ConnectionInfo}
     * for metadata about the connection (like incoming vs outgoing, or the authentication token). If
     * we want to continue with the connection, call {acceptConnection(Endpoint)}. Otherwise,
     * call {rejectConnection(Endpoint)}.
     */
    void onConnectionInitiated(EndPoint endpoint, ConnectionInfo connectionInfo);

    public Strategy getStrategy();

    /**
     * Called when discovery successfully starts. Override this method to act on the event.
     */
    void onDiscoveryStarted();

    /**
     * Called when discovery fails to start. Override this method to act on the event.
     */
    void onDiscoveryFailed();

    /**
     * Called when a remote endpoint is discovered.
     */
    void onEndpointDiscovered(EndPoint endpoint);

    /**
     * Called when a connection with this endpoint has failed. Override this method to act on the
     * event.
     */
    void onConnectionFailed(EndPoint endpoint, int numberOfTimes);

    /**
     * Called when someone has connected to us. Override this method to act on the event.
     */
    void onEndpointConnected(EndPoint endpoint);

    /**
     * Called when someone has disconnected. Override this method to act on the event.
     */
    void onEndpointDisconnected(EndPoint endpoint);

    void onReceive(EndPoint endpoint, Payload payload);

    void onStopAdvertising();

    void notifyMessage(String message);
}
