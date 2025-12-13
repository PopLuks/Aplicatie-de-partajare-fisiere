package ro.facultate.sd.p2p.network;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.facultate.sd.p2p.model.FileInfo;
import ro.facultate.sd.p2p.model.P2PMessage;
import ro.facultate.sd.p2p.model.PeerInfo;

/**
 * Client pentru descărcarea fișierelor de la alți peers
 */
public class FileClient {
    private static final Logger logger = LoggerFactory.getLogger(FileClient.class);
    private static final int CONNECTION_TIMEOUT = 5000; // 5 secunde
    
    private final Path downloadFolder;
    
    private Consumer<String> onDownloadStart;
    private BiConsumer<String, Double> onDownloadProgress;
    private Consumer<String> onDownloadComplete;
    private BiConsumer<String, String> onDownloadError;
    
    public FileClient(Path downloadFolder) {
        this.downloadFolder = downloadFolder;
        
        // Creează folderul dacă nu există
        try {
            Files.createDirectories(downloadFolder);
        } catch (IOException e) {
            logger.error("Nu s-a putut crea folderul de descărcări", e);
        }
    }
    
    /**
     * Cere lista de fișiere de la un peer
     */
    public List<FileInfo> requestFileList(PeerInfo peer) {
        List<FileInfo> files = new ArrayList<>();
        
        try (Socket socket = new Socket(peer.getAddress(), peer.getFileTransferPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            socket.setSoTimeout(CONNECTION_TIMEOUT);
            
            // Trimite cerere
            P2PMessage request = new P2PMessage(P2PMessage.MessageType.REQUEST_FILE_LIST);
            out.writeObject(request);
            out.flush();
            
            // Primește răspuns
            P2PMessage response = (P2PMessage) in.readObject();
            
            if (response.getType() == P2PMessage.MessageType.FILE_LIST_RESPONSE) {
                files = response.getFileList();
                
                // Setează informații despre peer
                for (FileInfo file : files) {
                    file.setOwnerPeerId(peer.getPeerId());
                    file.setOwnerAddress(peer.getAddress());
                    file.setOwnerPort(peer.getFileTransferPort());
                }
                
                logger.info("Primite {} fișiere de la peer {}", 
                           files.size(), peer.getPeerId().substring(0, 8));
            }
            
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Eroare la cererea listei de fișiere de la peer " + 
                        peer.getPeerId().substring(0, 8), e);
        }
        
        return files;
    }
    
    /**
     * Descarcă un fișier de la un peer
     */
    public boolean downloadFile(FileInfo fileInfo) {
        String fileName = fileInfo.getFileName();
        
        if (onDownloadStart != null) {
            onDownloadStart.accept(fileName);
        }
        
        logger.info("Începe descărcarea: {} de la {}:{}", 
                   fileName, fileInfo.getOwnerAddress(), fileInfo.getOwnerPort());
        
        Path targetPath = downloadFolder.resolve(fileName);
        
        try (Socket socket = new Socket(fileInfo.getOwnerAddress(), fileInfo.getOwnerPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
            
            // Trimite cererea de fișier
            P2PMessage request = new P2PMessage(P2PMessage.MessageType.FILE_REQUEST);
            request.setRequestedFileName(fileName);
            out.writeObject(request);
            out.flush();
            
            // Primește răspunsul
            P2PMessage response = (P2PMessage) in.readObject();
            
            if (response.getType() == P2PMessage.MessageType.FILE_REJECT) {
                String error = response.getErrorMessage();
                logger.error("Cerere respinsă: {}", error);
                
                if (onDownloadError != null) {
                    onDownloadError.accept(fileName, error);
                }
                
                return false;
            }
            
            if (response.getType() != P2PMessage.MessageType.FILE_ACCEPT) {
                logger.error("Răspuns neașteptat: {}", response.getType());
                
                if (onDownloadError != null) {
                    onDownloadError.accept(fileName, "Răspuns invalid de la server");
                }
                
                return false;
            }
            
            // Primește fișierul în bucăți
            long totalBytes = fileInfo.getFileSize();
            long receivedBytes = 0;
            
            while (true) {
                P2PMessage chunk = (P2PMessage) in.readObject();
                
                if (chunk.getType() == P2PMessage.MessageType.FILE_CHUNK) {
                    byte[] data = chunk.getFileData();
                    fos.write(data);
                    
                    receivedBytes += data.length;
                    
                    // Raportează progresul
                    if (onDownloadProgress != null && totalBytes > 0) {
                        double progress = (receivedBytes * 100.0) / totalBytes;
                        onDownloadProgress.accept(fileName, progress);
                    }
                    
                } else if (chunk.getType() == P2PMessage.MessageType.FILE_COMPLETE) {
                    logger.info("Descărcare completă: {} ({} bytes)", fileName, receivedBytes);
                    
                    if (onDownloadComplete != null) {
                        onDownloadComplete.accept(fileName);
                    }
                    
                    return true;
                }
            }
            
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Eroare la descărcarea fișierului: " + fileName, e);
            
            // Șterge fișierul parțial
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ex) {
                logger.warn("Nu s-a putut șterge fișierul parțial", ex);
            }
            
            if (onDownloadError != null) {
                onDownloadError.accept(fileName, e.getMessage());
            }
            
            return false;
        }
    }
    
    /**
     * Descarcă un fișier într-un thread separat
     */
    public void downloadFileAsync(FileInfo fileInfo) {
        Thread downloadThread = new Thread(() -> downloadFile(fileInfo), 
                                          "Download-" + fileInfo.getFileName());
        downloadThread.setDaemon(true);
        downloadThread.start();
    }
    
    /**
     * Verifică dacă un peer este activ (trimite PING)
     */
    public boolean pingPeer(PeerInfo peer) {
        try (Socket socket = new Socket(peer.getAddress(), peer.getFileTransferPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            
            socket.setSoTimeout(CONNECTION_TIMEOUT);
            
            P2PMessage ping = new P2PMessage(P2PMessage.MessageType.PING);
            out.writeObject(ping);
            out.flush();
            
            P2PMessage response = (P2PMessage) in.readObject();
            
            return response.getType() == P2PMessage.MessageType.PONG;
            
        } catch (IOException | ClassNotFoundException e) {
            logger.debug("Peer inactiv: {}", peer.getPeerId().substring(0, 8));
            return false;
        }
    }
    
    // Getters
    public Path getDownloadFolder() {
        return downloadFolder;
    }
    
    // Callbacks
    public void setOnDownloadStart(Consumer<String> callback) {
        this.onDownloadStart = callback;
    }
    
    public void setOnDownloadProgress(BiConsumer<String, Double> callback) {
        this.onDownloadProgress = callback;
    }
    
    public void setOnDownloadComplete(Consumer<String> callback) {
        this.onDownloadComplete = callback;
    }
    
    public void setOnDownloadError(BiConsumer<String, String> callback) {
        this.onDownloadError = callback;
    }
}
