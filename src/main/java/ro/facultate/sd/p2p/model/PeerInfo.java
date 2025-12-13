package ro.facultate.sd.p2p.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Reprezintă informații despre un peer (nod) în rețeaua P2P
 */
public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String peerId; // UUID unic pentru fiecare peer
    private String address; // Adresa IP
    private int discoveryPort; // Port UDP pentru descoperire
    private int fileTransferPort; // Port TCP pentru transfer fișiere
    private long lastSeen; // Timestamp ultima comunicare
    
    public PeerInfo() {
    }
    
    public PeerInfo(String peerId, String address, int discoveryPort, int fileTransferPort) {
        this.peerId = peerId;
        this.address = address;
        this.discoveryPort = discoveryPort;
        this.fileTransferPort = fileTransferPort;
        this.lastSeen = System.currentTimeMillis();
    }
    
    public String getPeerId() {
        return peerId;
    }
    
    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }
    
    public String getAddress() {
        return address;
    }
    
    public void setAddress(String address) {
        this.address = address;
    }
    
    public int getDiscoveryPort() {
        return discoveryPort;
    }
    
    public void setDiscoveryPort(int discoveryPort) {
        this.discoveryPort = discoveryPort;
    }
    
    public int getFileTransferPort() {
        return fileTransferPort;
    }
    
    public void setFileTransferPort(int fileTransferPort) {
        this.fileTransferPort = fileTransferPort;
    }
    
    public long getLastSeen() {
        return lastSeen;
    }
    
    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) o;
        return Objects.equals(peerId, peerInfo.peerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }
    
    @Override
    public String toString() {
        return "Peer " + peerId.substring(0, 8) + "... @ " + address + ":" + fileTransferPort;
    }
}
