package ro.facultate.sd.p2p.network;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.facultate.sd.p2p.model.FileInfo;
import ro.facultate.sd.p2p.model.P2PMessage;

/**
 * Server TCP pentru gestionarea cererilor de fișiere de la alți peers
 * Ascultă cereri și trimite fișiere către alți peers
 */
public class FileServer {
    private static final Logger logger = LoggerFactory.getLogger(FileServer.class);
    private static final int BUFFER_SIZE = 8192; // 8KB bucăți pentru transfer
    
    private final int port;
    private final Path sharedFolder;
    private final List<FileInfo> sharedFiles;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private Thread acceptThread;
    private volatile boolean running;
    
    private Consumer<String> onFileRequested;
    private Consumer<String> onTransferComplete;
    private Consumer<FileInfo> onFileAdded;
    
    public FileServer(int port, Path sharedFolder) {
        this.port = port;
        this.sharedFolder = sharedFolder;
        this.sharedFiles = new CopyOnWriteArrayList<>();
        
        // Creează folderul dacă nu există
        try {
            Files.createDirectories(sharedFolder);
        } catch (IOException e) {
            logger.error("Nu s-a putut crea folderul de partajare", e);
        }
    }
    
    /**
     * Pornește serverul
     */
    public void start() throws IOException {
        if (running) {
            logger.warn("FileServer este deja pornit");
            return;
        }
        
        serverSocket = new ServerSocket(port);
        executorService = Executors.newCachedThreadPool();
        running = true;
        
        // Scanează fișierele din folder
        scanSharedFolder();
        
        // Thread pentru acceptarea conexiunilor
        acceptThread = new Thread(this::acceptConnections, "FileServerAcceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
        
        logger.info("FileServer pornit pe portul {}, partajează {} fișiere", 
                    port, sharedFiles.size());
    }
    
    /**
     * Oprește serverul
     */
    public void stop() {
        running = false;
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Eroare la închiderea serverSocket", e);
        }
        
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        
        logger.info("FileServer oprit");
    }
    
    /**
     * Acceptă conexiuni de la clienți
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                logger.debug("Conexiune nouă de la {}", clientSocket.getRemoteSocketAddress());
                
                // Procesează cererea într-un thread separat
                executorService.submit(() -> handleClient(clientSocket));
                
            } catch (IOException e) {
                if (running) {
                    logger.error("Eroare la acceptarea conexiunii", e);
                }
            }
        }
    }
    
    /**
     * Procesează cererea unui client
     */
    private void handleClient(Socket socket) {
        try (socket;
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            
            // Citește cererea
            P2PMessage request = (P2PMessage) in.readObject();
            logger.debug("Cerere primită: {}", request.getType());
            
            switch (request.getType()) {
                case REQUEST_FILE_LIST:
                    handleFileListRequest(out);
                    break;
                    
                case FILE_REQUEST:
                    handleFileRequest(request, out);
                    break;
                    
                case PING:
                    handlePing(out);
                    break;
                    
                default:
                    logger.warn("Tip de mesaj necunoscut: {}", request.getType());
            }
            
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Eroare la procesarea clientului", e);
        }
    }
    
    /**
     * Trimite lista de fișiere partajate
     */
    private void handleFileListRequest(ObjectOutputStream out) throws IOException {
        P2PMessage response = new P2PMessage(P2PMessage.MessageType.FILE_LIST_RESPONSE);
        response.setFileList(new CopyOnWriteArrayList<>(sharedFiles));
        
        out.writeObject(response);
        out.flush();
        
        logger.debug("Listă de fișiere trimisă ({} fișiere)", sharedFiles.size());
    }
    
    /**
     * Trimite un fișier cerut (cu suport pentru resume de la offset)
     */
    private void handleFileRequest(P2PMessage request, ObjectOutputStream out) throws IOException {
        String fileName = request.getRequestedFileName();
        long resumeOffset = request.getResumeOffset();
        
        if (onFileRequested != null) {
            onFileRequested.accept(fileName);
        }
        
        // Caută fișierul
        FileInfo requestedFile = sharedFiles.stream()
            .filter(f -> f.getFileName().equals(fileName))
            .findFirst()
            .orElse(null);
        
        if (requestedFile == null) {
            // Fișier negăsit
            P2PMessage response = new P2PMessage(P2PMessage.MessageType.FILE_REJECT);
            response.setErrorMessage("Fișierul nu există");
            out.writeObject(response);
            out.flush();
            logger.warn("Fișier cerut negăsit: {}", fileName);
            return;
        }
        
        Path filePath = sharedFolder.resolve(fileName);
        
        if (!Files.exists(filePath)) {
            P2PMessage response = new P2PMessage(P2PMessage.MessageType.FILE_REJECT);
            response.setErrorMessage("Fișierul a fost șters");
            out.writeObject(response);
            out.flush();
            logger.warn("Fișierul a fost șters: {}", fileName);
            return;
        }
        
        // Acceptă cererea
        P2PMessage acceptMessage = new P2PMessage(P2PMessage.MessageType.FILE_ACCEPT);
        acceptMessage.setFileList(List.of(requestedFile));
        out.writeObject(acceptMessage);
        out.flush();
        
        if (resumeOffset > 0) {
            logger.info("🔄 RELUARE transfer fișier: {} de la byte {} (skip {}%)", 
                       fileName, resumeOffset, (resumeOffset * 100.0) / requestedFile.getFileSize());
        } else {
            logger.info("⬆️ Începe transfer NOU: {}", fileName);
        }
        
        // Trimite fișierul în bucăți (de la offset dacă e resume)
        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            // Skip bytes dacă reluăm transferul
            if (resumeOffset > 0) {
                long skipped = fis.skip(resumeOffset);
                if (skipped != resumeOffset) {
                    logger.warn("Nu s-au putut skip toți bytes: {} != {}", skipped, resumeOffset);
                }
            }
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalSent = resumeOffset; // Pornim de la offset
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                P2PMessage chunk = new P2PMessage(P2PMessage.MessageType.FILE_CHUNK);
                
                // Copiază doar bytes cititi efectiv
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);
                chunk.setFileData(data);
                
                out.writeObject(chunk);
                out.flush();
                
                totalSent += bytesRead;
            }
            
            // Trimite mesaj de completare
            P2PMessage complete = new P2PMessage(P2PMessage.MessageType.FILE_COMPLETE);
            out.writeObject(complete);
            out.flush();
            
            logger.info("Transfer complet: {} ({} bytes)", fileName, totalSent);
            
            if (onTransferComplete != null) {
                onTransferComplete.accept(fileName);
            }
        }
    }
    
    /**
     * Răspunde la PING
     */
    private void handlePing(ObjectOutputStream out) throws IOException {
        P2PMessage pong = new P2PMessage(P2PMessage.MessageType.PONG);
        out.writeObject(pong);
        out.flush();
    }
    
    /**
     * Scanează folderul de fișiere partajate
     */
    public void scanSharedFolder() {
        sharedFiles.clear();
        
        try (var stream = Files.list(sharedFolder)) {
            stream.filter(Files::isRegularFile)
                  .forEach(this::addFileToSharedList);
            
            logger.info("Scanare completă: {} fișiere găsite", sharedFiles.size());
            
        } catch (IOException e) {
            logger.error("Eroare la scanarea folderului", e);
        }
    }
    
    /**
     * Adaugă un fișier la lista de fișiere partajate
     */
    private FileInfo addFileToSharedList(Path filePath) {
        try {
            String fileName = filePath.getFileName().toString();
            long fileSize = Files.size(filePath);
            String hash = calculateFileHash(filePath);
            
            FileInfo fileInfo = new FileInfo(fileName, fileSize, hash);
            sharedFiles.add(fileInfo);
            
            logger.debug("Fișier adăugat: {}", fileName);
            return fileInfo;
            
        } catch (IOException e) {
            logger.error("Eroare la adăugarea fișierului: " + filePath, e);
            return null;
        }
    }
    
    /**
     * Calculează hash-ul MD5 al unui fișier
     */
    private String calculateFileHash(Path filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            logger.error("Eroare la calcularea hash-ului", e);
            return "unknown";
        }
    }
    
    /**
     * Adaugă manual un fișier la partajare
     */
    public boolean addSharedFile(Path sourceFile) {
        try {
            Path targetPath = sharedFolder.resolve(sourceFile.getFileName());
            
            // Copiază fișierul în folderul de partajare dacă nu e deja acolo
            if (!sourceFile.equals(targetPath)) {
                Files.copy(sourceFile, targetPath);
            }
            
            FileInfo fileInfo = addFileToSharedList(targetPath);
            
            // Notifică callback-ul cu fișierul adăugat
            if (fileInfo != null && onFileAdded != null) {
                onFileAdded.accept(fileInfo);
            }
            
            logger.info("Fișier adăugat la partajare: {}", sourceFile.getFileName());
            return true;
            
        } catch (IOException e) {
            logger.error("Eroare la adăugarea fișierului", e);
            return false;
        }
    }
    
    // Getters
    public List<FileInfo> getSharedFiles() {
        return new CopyOnWriteArrayList<>(sharedFiles);
    }
    
    public int getPort() {
        return port;
    }
    
    public Path getSharedFolder() {
        return sharedFolder;
    }
    
    // Callbacks
    public void setOnFileRequested(Consumer<String> callback) {
        this.onFileRequested = callback;
    }
    
    public void setOnTransferComplete(Consumer<String> callback) {
        this.onTransferComplete = callback;
    }
    
    public void setOnFileAdded(Consumer<FileInfo> callback) {
        this.onFileAdded = callback;
    }
}
