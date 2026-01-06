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
    private static final String PARTIAL_SUFFIX = ".partial"; // Extensie pentru fișiere incomplete
    private static final int MAX_RETRY_ATTEMPTS = 3; // Număr maxim de reîncercări
    private static final int RETRY_DELAY_MS = 2000; // Delay inițial între reîncercări (2 secunde)
    
    private final Path downloadFolder;
    private boolean simulateInterruptionForTesting = false;
    private long interruptAtBytes = 0;
    
    private final java.util.concurrent.ConcurrentHashMap<String, Thread> activeDownloads = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> pausedDownloads = new java.util.concurrent.ConcurrentHashMap<>();
    
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
     * Descarcă un fișier de la un peer (cu suport pentru resume)
     */
    public boolean downloadFile(FileInfo fileInfo) {
        String fileName = fileInfo.getFileName();
        Path targetPath = downloadFolder.resolve(fileName);
        Path partialPath = downloadFolder.resolve(fileName + PARTIAL_SUFFIX);
        
        // Verifică dacă există fișier parțial de la o descărcare anterioară
        long resumeOffset = 0;
        if (Files.exists(partialPath)) {
            try {
                resumeOffset = Files.size(partialPath);
                logger.info("Găsit fișier parțial pentru {}: {} bytes. RELUARE de la byte {}", 
                           fileName, resumeOffset, resumeOffset);
            } catch (IOException e) {
                logger.warn("Nu s-a putut citi dimensiunea fișierului parțial", e);
                resumeOffset = 0;
            }
        }
        
        if (onDownloadStart != null) {
            onDownloadStart.accept(fileName);
        }
        
        if (resumeOffset > 0) {
            logger.info("🔄 RELUARE descărcare: {} de la byte {} ({}%)", 
                       fileName, resumeOffset, (resumeOffset * 100.0) / fileInfo.getFileSize());
        } else {
            logger.info("⬇️ Începe descărcare NOUĂ: {} de la {}:{}", 
                       fileName, fileInfo.getOwnerAddress(), fileInfo.getOwnerPort());
        }
        
        // Deschide fișierul în mod append dacă reluăm, altfel creează nou
        try (Socket socket = new Socket(fileInfo.getOwnerAddress(), fileInfo.getOwnerPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             FileOutputStream fos = new FileOutputStream(partialPath.toFile(), resumeOffset > 0)) {
            
            // Trimite cererea de fișier cu offset pentru resume
            P2PMessage request = new P2PMessage(P2PMessage.MessageType.FILE_REQUEST);
            request.setRequestedFileName(fileName);
            request.setResumeOffset(resumeOffset);
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
            long receivedBytes = resumeOffset; // Începem de la offset dacă reluăm
            
            while (true) {
                // Verifică dacă download-ul e pe pauză
                while (pausedDownloads.getOrDefault(fileName, false)) {
                    try {
                        Thread.sleep(500); // Așteaptă 500ms și verifică din nou
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.info("Download întrerupt pentru: {}", fileName);
                        throw new IOException("Download anulat");
                    }
                }
                
                P2PMessage chunk = (P2PMessage) in.readObject();
                
                if (chunk.getType() == P2PMessage.MessageType.FILE_CHUNK) {
                    byte[] data = chunk.getFileData();
                    fos.write(data);
                    
                    receivedBytes += data.length;
                    
                    // Simulare întrerupere pentru testare
                    if (simulateInterruptionForTesting && receivedBytes >= interruptAtBytes) {
                        logger.warn("⚠️ SIMULARE ÎNTRERUPERE la {} bytes pentru testare!", receivedBytes);
                        throw new IOException("Simulare întrerupere pentru testare");
                    }
                    
                    // Raportează progresul
                    if (onDownloadProgress != null && totalBytes > 0) {
                        double progress = (receivedBytes * 100.0) / totalBytes;
                        onDownloadProgress.accept(fileName, progress);
                    }
                    
                } else if (chunk.getType() == P2PMessage.MessageType.FILE_COMPLETE) {
                    logger.info("✅ Descărcare completă: {} ({} bytes total)", fileName, receivedBytes);
                    
                    // Setează progresul la 100% ÎNAINTE de callback
                    if (onDownloadProgress != null) {
                        onDownloadProgress.accept(fileName, 100.0);
                    }
                    
                    // Redenumește fișierul .partial în numele final
                    try {
                        Files.move(partialPath, targetPath, 
                                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        logger.info("📁 Fișier mutat: {} -> {}", partialPath.getFileName(), fileName);
                    } catch (IOException e) {
                        logger.error("Eroare la redenumirea fișierului final", e);
                    }
                    
                    if (onDownloadComplete != null) {
                        onDownloadComplete.accept(fileName);
                    }
                    
                    return true;
                }
            }
            
        } catch (IOException | ClassNotFoundException e) {
            // NU șterge fișierul parțial - păstrează-l pentru reluare!
            try {
                long partialSize = Files.exists(partialPath) ? Files.size(partialPath) : 0;
                if (partialSize > 0) {
                    logger.warn("⚠️ Transfer întrerupt pentru {}: {} (progres salvat: {} bytes)", 
                               fileName, e.getMessage(), partialSize);
                } else {
                    logger.error("❌ Eroare la descărcarea fișierului: " + fileName, e);
                }
            } catch (IOException ex) {
                logger.error("❌ Eroare la descărcarea fișierului: " + fileName, e);
            }
            
            // Aruncă excepția mai departe pentru logica de retry
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Descarcă un fișier cu retry automat în caz de eroare
     * @param fileInfo Informații despre fișier
     * @param maxRetries Număr maxim de reîncercări (0 = fără retry)
     * @return true dacă descărcarea a reușit
     */
    public boolean downloadFileWithRetry(FileInfo fileInfo, int maxRetries) {
        String fileName = fileInfo.getFileName();
        int attempt = 0;
        
        // Salvez thread-ul curent pentru control pauză
        activeDownloads.put(fileName, Thread.currentThread());
        
        try {
            while (attempt <= maxRetries) {
                try {
                    if (attempt > 0) {
                        // Calculează delay exponențial: 2s, 4s, 8s...
                        int delayMs = RETRY_DELAY_MS * (1 << (attempt - 1));
                        logger.info("🔄 Reîncerc descărcarea {} (încercarea {}/{}) în {} secunde...", 
                                   fileName, attempt + 1, maxRetries + 1, delayMs / 1000);
                        Thread.sleep(delayMs);
                    }
                    
                    boolean success = downloadFile(fileInfo);
                    
                    if (success) {
                        if (attempt > 0) {
                            logger.info("✅ Descărcare reușită după {} reîncercări: {}", attempt, fileName);
                        }
                        return true;
                    }
                    
                    // Dacă downloadFile returnează false (fișier reject), nu mai încercăm
                    logger.warn("❌ Descărcarea a fost respinsă de server: {}", fileName);
                    if (onDownloadError != null) {
                        onDownloadError.accept(fileName, "Fișierul nu este disponibil pe server");
                    }
                    return false;
                    
                } catch (RuntimeException e) {
                    attempt++;
                    
                    if (attempt > maxRetries) {
                        // Am epuizat toate reîncercările
                        logger.error("❌ Descărcarea {} a eșuat după {} încercări", fileName, attempt);
                        
                        if (onDownloadError != null) {
                            Path partialPath = downloadFolder.resolve(fileName + PARTIAL_SUFFIX);
                            try {
                                long partialSize = Files.exists(partialPath) ? Files.size(partialPath) : 0;
                                if (partialSize > 0) {
                                    onDownloadError.accept(fileName, 
                                        String.format("Conexiune întreruptă. Progres salvat: %d bytes. Încearcă din nou mai târziu.", partialSize));
                                } else {
                                    onDownloadError.accept(fileName, "Nu s-a putut conecta la peer. Verifică dacă peer-ul este online.");
                                }
                            } catch (IOException ex) {
                                onDownloadError.accept(fileName, "Descărcare eșuată: " + e.getCause().getMessage());
                            }
                        }
                        
                        return false;
                    }
                    
                    // Mai încercăm o dată
                    logger.warn("⚠️ Eroare la descărcare, voi reîncerca... ({}/{})", attempt, maxRetries);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Transfer întrerupt");
                    if (onDownloadError != null) {
                        onDownloadError.accept(fileName, "Transfer anulat");
                    }
                    return false;
                }
            }
            
            return false;
            
        } finally {
            // Cleanup
            activeDownloads.remove(fileName);
            pausedDownloads.remove(fileName);
        }
    }
    
    /**
     * Descarcă un fișier într-un thread separat (cu retry automat)
     */
    public void downloadFileAsync(FileInfo fileInfo) {
        Thread downloadThread = new Thread(() -> downloadFileWithRetry(fileInfo, MAX_RETRY_ATTEMPTS), 
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
    
    // Metode pentru control download
    
    /**
     * Pune pe pauză descărcarea unui fișier
     * @param fileName Numele fișierului a cărui descărcare trebuie pusă pe pauză
     */
    public void pauseDownload(String fileName) {
        if (activeDownloads.containsKey(fileName)) {
            pausedDownloads.put(fileName, true);
            logger.info("⏸ Download pus pe pauză: {}", fileName);
        } else {
            logger.warn("Nu există download activ pentru: {}", fileName);
        }
    }
    
    /**
     * Reia descărcarea unui fișier pus pe pauză
     * @param fileName Numele fișierului a cărui descărcare trebuie reluată
     */
    public void resumeDownload(String fileName) {
        if (activeDownloads.containsKey(fileName)) {
            pausedDownloads.put(fileName, false);
            logger.info("▶ Download reluat: {}", fileName);
        } else {
            logger.warn("Nu există download activ pentru: {}", fileName);
        }
    }
    
    /**
     * Verifică dacă un download este pe pauză
     * @param fileName Numele fișierului
     * @return true dacă download-ul este pe pauză
     */
    public boolean isDownloadPaused(String fileName) {
        return pausedDownloads.getOrDefault(fileName, false);
    }
    
    /**
     * Verifică dacă un download este activ
     * @param fileName Numele fișierului
     * @return true dacă download-ul este activ
     */
    public boolean isDownloadActive(String fileName) {
        return activeDownloads.containsKey(fileName);
    }
    
    // Metode pentru testare
    
    /**
     * Activează simularea întreruperii la un număr specific de bytes
     * UTILIZARE DOAR PENTRU TESTARE!
     * 
     * @param interruptAtBytes La câți bytes să se întrerupă transferul (0 = dezactivat)
     */
    public void enableInterruptionSimulation(long interruptAtBytes) {
        this.simulateInterruptionForTesting = (interruptAtBytes > 0);
        this.interruptAtBytes = interruptAtBytes;
        if (simulateInterruptionForTesting) {
            logger.warn("⚠️ MOD TESTARE: Întrerupere simulată activată la {} bytes", interruptAtBytes);
        }
    }
    
    /**
     * Șterge fișierele parțiale pentru un cleanup complet
     */
    public void cleanupPartialFiles() {
        try (var stream = Files.list(downloadFolder)) {
            stream.filter(path -> path.toString().endsWith(PARTIAL_SUFFIX))
                  .forEach(path -> {
                      try {
                          Files.delete(path);
                          logger.info("Șters fișier parțial: {}", path.getFileName());
                      } catch (IOException e) {
                          logger.warn("Nu s-a putut șterge {}", path.getFileName());
                      }
                  });
        } catch (IOException e) {
            logger.error("Eroare la cleanup fișiere parțiale", e);
        }
    }
}
