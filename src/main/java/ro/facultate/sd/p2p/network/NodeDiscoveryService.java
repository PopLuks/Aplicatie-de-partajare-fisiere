package ro.facultate.sd.p2p.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import ro.facultate.sd.p2p.model.P2PMessage;
import ro.facultate.sd.p2p.model.PeerInfo;

/**
 * Serviciu pentru descoperirea automată a nodurilor în rețeaua P2P
 * Folosește UDP multicast pentru a anunța prezența și a descoperi alte noduri
 */
public class NodeDiscoveryService {
    private static final Logger logger = LoggerFactory.getLogger(NodeDiscoveryService.class);
    private static final String MULTICAST_GROUP = "230.0.0.1"; // Adresă multicast pentru P2P
    private static final int DISCOVERY_PORT = 9876; // Port comun pentru TOȚI peers
    private static final int ANNOUNCE_INTERVAL_SECONDS = 10;
    private static final int PEER_TIMEOUT_SECONDS = 30;
    
    private final String peerId;
    private final int fileTransferPort;
    private final Map<String, PeerInfo> discoveredPeers;
    private final Gson gson;
    
    private MulticastSocket socket;
    private InetAddress group;
    private Thread listenerThread;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;
    
    private Consumer<PeerInfo> onPeerDiscovered;
    private Consumer<String> onPeerLost;
    private Consumer<ro.facultate.sd.p2p.model.FileInfo> onFileAdded;
   
    /* Generare Peer  */
    public NodeDiscoveryService(int fileTransferPort) {
        this.peerId = UUID.randomUUID().toString();
        this.fileTransferPort = fileTransferPort;
        this.discoveredPeers = new ConcurrentHashMap<>();
        this.gson = new Gson();
    }
    
    /**
     * Pornește serviciul de descoperire
     */
    public void start() throws IOException {
        if (running) {
            logger.warn("NodeDiscoveryService este deja pornit");
            return;
        }
        
        // Creează multicast socket care permite multiple instanțe să asculte pe același port
        socket = new MulticastSocket(DISCOVERY_PORT);
        socket.setReuseAddress(true);
        
        // Join la grupul multicast
        group = InetAddress.getByName(MULTICAST_GROUP);
        socket.joinGroup(group);
        
        running = true;
        
        // Thread pentru ascultarea mesajelor de descoperire
        listenerThread = new Thread(this::listenForPeers, "DiscoveryListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        
        // Scheduler pentru anunțuri periodice și curățare peers vechi
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(this::announceSelf, 0, ANNOUNCE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupInactivePeers, PEER_TIMEOUT_SECONDS, 
                                      PEER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        logger.info("NodeDiscoveryService pornit. Peer ID: {}, Port: {}", 
                    peerId.substring(0, 8), fileTransferPort);
    }
    
    /**
     * Oprește serviciul de descoperire
     */
    public void stop() {
        running = false;
        
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        
        if (socket != null && !socket.isClosed()) {
            try {
                socket.leaveGroup(group);
            } catch (IOException e) {
                logger.warn("Eroare la părăsirea grupului multicast", e);
            }
            socket.close();
        }
        
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        
        logger.info("NodeDiscoveryService oprit");
    }
    
    /**
     * Trimite mesaj de anunțare în rețea (multicast)
     */
    private void announceSelf() {
        try {
            PeerInfo myInfo = new PeerInfo(peerId, getLocalAddress(), DISCOVERY_PORT, fileTransferPort);
            P2PMessage message = new P2PMessage(P2PMessage.MessageType.PEER_ANNOUNCE, myInfo);
            
            byte[] data = gson.toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(
                data, data.length, 
                group, 
                DISCOVERY_PORT
            );
            
            socket.send(packet);
            logger.debug("Mesaj de anunțare trimis");
            
        } catch (IOException e) {
            logger.error("Eroare la trimiterea mesajului de anunțare", e);
        }
    }
    
    /**
     * Asculta mesaje de descoperire de la alte noduri
     */
    private void listenForPeers() {
        byte[] buffer = new byte[8192];
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                String jsonMessage = new String(packet.getData(), 0, packet.getLength());
                P2PMessage message = gson.fromJson(jsonMessage, P2PMessage.class);
                
                if (message.getType() == P2PMessage.MessageType.PEER_ANNOUNCE) {
                    handlePeerAnnounce(message, packet.getAddress());
                } else if (message.getType() == P2PMessage.MessageType.FILE_ADDED) {
                    handleFileAdded(message, packet.getAddress());
                }
                
            } catch (SocketException e) {
                if (running) {
                    logger.error("Socket închis neașteptat", e);
                }
            } catch (IOException e) {
                logger.error("Eroare la primirea mesajului", e);
            } catch (Exception e) {
                logger.error("Eroare la procesarea mesajului", e);
            }
        }
    }
    
    /**
     * Procesează un mesaj de anunțare de la alt peer
     */
    private void handlePeerAnnounce(P2PMessage message, InetAddress senderAddress) {
        PeerInfo peerInfo = message.getSenderInfo();
        
        // Ignoră propriile mesaje
        if (peerInfo.getPeerId().equals(peerId)) {
            return;
        }
        
        // Actualizează adresa IP cu cea reală (nu cea declarată)
        peerInfo.setAddress(senderAddress.getHostAddress());
        peerInfo.updateLastSeen();
        
        boolean isNewPeer = !discoveredPeers.containsKey(peerInfo.getPeerId());
        discoveredPeers.put(peerInfo.getPeerId(), peerInfo);
        
        if (isNewPeer) {
            logger.info("Peer nou descoperit: {}", peerInfo);
            if (onPeerDiscovered != null) {
                onPeerDiscovered.accept(peerInfo);
            }
            
            // Răspunde direct la noul peer
            sendDirectResponse(peerInfo);
        } else {
            logger.debug("Peer actualizat: {}", peerInfo.getPeerId().substring(0, 8));
        }
    }
    
    /**
     * Trimite un răspuns direct unui peer (nu broadcast)
     */
    private void sendDirectResponse(PeerInfo targetPeer) {
        try {
            PeerInfo myInfo = new PeerInfo(peerId, getLocalAddress(), DISCOVERY_PORT, fileTransferPort);
            P2PMessage response = new P2PMessage(P2PMessage.MessageType.PEER_RESPONSE, myInfo);
            
            byte[] data = gson.toJson(response).getBytes();
            DatagramPacket packet = new DatagramPacket(
                data, data.length,
                InetAddress.getByName(targetPeer.getAddress()),
                targetPeer.getDiscoveryPort()
            );
            
            socket.send(packet);
            logger.debug("Răspuns trimis direct la peer {}", targetPeer.getPeerId().substring(0, 8));
            
        } catch (IOException e) {
            logger.error("Eroare la trimiterea răspunsului direct", e);
        }
    }
    
    /**
     * Procesează notificare că un peer a adăugat un fișier nou (inclusiv propriile fișiere)
     */
    private void handleFileAdded(P2PMessage message, InetAddress senderAddress) {
        PeerInfo peerInfo = message.getSenderInfo();
        ro.facultate.sd.p2p.model.FileInfo fileInfo = message.getFileInfo();
        
        if (fileInfo != null && onFileAdded != null) {
            // Procesează toate mesajele FILE_ADDED, inclusiv propriile (pentru UI consistent)
            logger.info("Fișier nou anunțat de peer {}: {}", 
                       peerInfo != null ? peerInfo.getPeerId().substring(0, 8) : "?",
                       fileInfo.getFileName());
            onFileAdded.accept(fileInfo);
        }
    }
    
    /**
     * Trimite notificare când se adaugă un fișier nou (broadcast multicast)
     */
    public void broadcastFileAdded(ro.facultate.sd.p2p.model.FileInfo fileInfo) {
        try {
            PeerInfo myInfo = new PeerInfo(peerId, getLocalAddress(), DISCOVERY_PORT, fileTransferPort);
            P2PMessage message = new P2PMessage(P2PMessage.MessageType.FILE_ADDED, myInfo);
            message.setFileInfo(fileInfo);
            
            byte[] data = gson.toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(
                data, data.length, 
                group, 
                DISCOVERY_PORT
            );
            
            socket.send(packet);
            logger.info("Notificare FILE_ADDED trimisă pentru: {}", fileInfo.getFileName());
            
        } catch (IOException e) {
            logger.error("Eroare la trimiterea notificării FILE_ADDED", e);
        }
    }
    
    /**
     * Curăță peers-ii care nu au mai răspuns de mult timp
     */
    private void cleanupInactivePeers() {
        long now = System.currentTimeMillis();
        long timeout = PEER_TIMEOUT_SECONDS * 1000L;
        
        discoveredPeers.entrySet().removeIf(entry -> {
            PeerInfo peer = entry.getValue();
            boolean isInactive = (now - peer.getLastSeen()) > timeout;
            
            if (isInactive) {
                logger.info("Peer inactiv eliminat: {}", peer);
                if (onPeerLost != null) {
                    onPeerLost.accept(peer.getPeerId());
                }
            }
            
            return isInactive;
        });
    }
    
    /**
     * Obține adresa IP locală
     */
    private String getLocalAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.warn("Nu s-a putut determina adresa locală, folosesc localhost");
            return "127.0.0.1";
        }
    }
    
    /**
     * Găsește un port UDP disponibil în intervalul specificat
     */
    @SuppressWarnings("unused")
    private int findAvailableUdpPort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            try (DatagramSocket testSocket = new DatagramSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port ocupat, încearcă următorul
            }
        }
        throw new RuntimeException("Nu s-a găsit niciun port UDP disponibil între " + startPort + " și " + endPort);
    }
    
    // Getters
    public String getPeerId() {
        return peerId;
    }
    
    public int getDiscoveryPort() {
        return DISCOVERY_PORT;
    }
    
    public Map<String, PeerInfo> getDiscoveredPeers() {
        return new ConcurrentHashMap<>(discoveredPeers);
    }
    
    // Callbacks
    public void setOnPeerDiscovered(Consumer<PeerInfo> callback) {
        this.onPeerDiscovered = callback;
    }
    
    public void setOnPeerLost(Consumer<String> callback) {
        this.onPeerLost = callback;
    }
    
    public void setOnFileAdded(Consumer<ro.facultate.sd.p2p.model.FileInfo> callback) {
        this.onFileAdded = callback;
    }
}
