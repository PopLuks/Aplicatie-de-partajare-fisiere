package ro.facultate.sd.p2p.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ro.facultate.sd.p2p.model.FileInfo;
import ro.facultate.sd.p2p.model.PeerInfo;
import ro.facultate.sd.p2p.network.FileClient;
import ro.facultate.sd.p2p.network.FileServer;
import ro.facultate.sd.p2p.network.NodeDiscoveryService;

/**
 * Controller pentru fereastra principală
 */
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    
    @FXML private Label peerIdLabel;
    @FXML private Label statusLabel;
    @FXML private Label connectedPeersLabel;
    @FXML private Label sharedFilesLabel;
    @FXML private Label availableFilesLabel;
    @FXML private Label sharedFolderLabel;
    @FXML private Label downloadFolderLabel;
    
    @FXML private TableView<FileInfo> sharedFilesTable;
    @FXML private TableColumn<FileInfo, String> sharedNameColumn;
    @FXML private TableColumn<FileInfo, String> sharedSizeColumn;
    @FXML private TableColumn<FileInfo, String> sharedHashColumn;
    
    @FXML private TableView<FileInfo> networkFilesTable;
    @FXML private TableColumn<FileInfo, String> networkNameColumn;
    @FXML private TableColumn<FileInfo, String> networkSizeColumn;
    @FXML private TableColumn<FileInfo, String> networkOwnerColumn;
    @FXML private TableColumn<FileInfo, String> networkHashColumn;
    
    @FXML private Button addFileButton;
    @FXML private Button downloadButton;
    @FXML private Button refreshSharedButton;
    @FXML private Button refreshNetworkButton;
    
    @FXML private TextArea activityLog;
    
    private Stage primaryStage;
    private NodeDiscoveryService discoveryService;
    private FileServer fileServer;
    private FileClient fileClient;
    
    private final ObservableList<FileInfo> sharedFiles = FXCollections.observableArrayList();
    private final ObservableList<FileInfo> networkFiles = FXCollections.observableArrayList();
    private final Map<String, PeerInfo> connectedPeers = new ConcurrentHashMap<>();
    
    private int fileTransferPort = 8888;
    private Path sharedFolder;
    private Path downloadFolder;
    
    /**
     * Inițializare controller
     */
    @FXML
    public void initialize() {
        setupTables();
        setupFolders();
        
        // Dezactivează butoanele până când aplicația pornește
        addFileButton.setDisable(true);
        refreshSharedButton.setDisable(true);
        refreshNetworkButton.setDisable(true);
        
        log("Aplicație inițializată. Apasă pe butoane pentru a începe...");
    }
    
    /**
     * Configurează tabelele
     */
    private void setupTables() {
        // Tabel fișiere partajate
        sharedNameColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileName()));
        sharedSizeColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileSizeFormatted()));
        sharedHashColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileHash().substring(0, 12) + "..."));
        
        sharedFilesTable.setItems(sharedFiles);
        
        // Tabel fișiere din rețea
        networkNameColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileName()));
        networkSizeColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileSizeFormatted()));
        networkOwnerColumn.setCellValueFactory(data -> {
            String peerId = data.getValue().getOwnerPeerId();
            return new SimpleStringProperty(peerId != null ? peerId.substring(0, 8) + "..." : "?");
        });
        networkHashColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileHash().substring(0, 12) + "..."));
        
        networkFilesTable.setItems(networkFiles);
        
        // Activează butonul de descărcare când un fișier e selectat
        networkFilesTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> downloadButton.setDisable(newVal == null));
    }
    
    /**
     * Configurează folderele de lucru
     */
    private void setupFolders() {
        // Folosește directorul curent al proiectului
        String currentDir = System.getProperty("user.dir");
        sharedFolder = Paths.get(currentDir, "P2P-Shared");
        downloadFolder = Paths.get(currentDir, "P2P-Downloads");
        
        try {
            java.nio.file.Files.createDirectories(sharedFolder);
            java.nio.file.Files.createDirectories(downloadFolder);
            logger.info("Foldere create în: {}", currentDir);
        } catch (Exception e) {
            logger.error("Eroare la crearea folderelor", e);
        }
        
        sharedFolderLabel.setText("Folder: " + sharedFolder.toString());
        downloadFolderLabel.setText("Descărcări în: " + downloadFolder.toString());
    }
    
    /**
     * Pornește serviciile P2P
     */
    public void startServices() {
        try {
            // Găsește un port disponibil pentru transfer fișiere
            fileTransferPort = findAvailablePort(8888, 8900);
            log("🔌 Port transfer găsit: " + fileTransferPort);
            
            // Pornește serviciul de descoperire MAI ÎNTÂI
            discoveryService = new NodeDiscoveryService(fileTransferPort);
            discoveryService.setOnPeerDiscovered(this::onPeerDiscovered);
            discoveryService.setOnPeerLost(this::onPeerLost);
            discoveryService.setOnFileAdded(this::onFileAddedByPeer);
            discoveryService.start();
            
            // Pornește serverul de fișiere ȘI setează callback-urile ÎNAINTE de start
            fileServer = new FileServer(fileTransferPort, sharedFolder);
            fileServer.setOnFileRequested(fileName -> 
                log("📤 Cerere primită pentru: " + fileName));
            fileServer.setOnTransferComplete(fileName -> 
                log("✅ Transfer completat: " + fileName));
            
            // IMPORTANT: Setează callback FILE_ADDED ÎNAINTE de start
            fileServer.setOnFileAdded(fileInfo -> {
                // Setează owner peer ID
                fileInfo.setOwnerPeerId(discoveryService.getPeerId());
                // Trimite notificare în rețea
                discoveryService.broadcastFileAdded(fileInfo);
                log("📢 Broadcasting FILE_ADDED pentru: " + fileInfo.getFileName());
            });
            
            fileServer.start();
            
            // Pornește clientul
            fileClient = new FileClient(downloadFolder);
            fileClient.setOnDownloadStart(fileName -> 
                log("📥 Începe descărcarea: " + fileName));
            fileClient.setOnDownloadProgress((fileName, progress) -> 
                Platform.runLater(() -> log(String.format("📊 %s - %.1f%%", fileName, progress))));
            fileClient.setOnDownloadComplete(fileName -> {
                log("✅ Descărcare completă: " + fileName);
                Platform.runLater(() -> showAlert("Descărcare Completă", 
                    "Fișierul " + fileName + " a fost descărcat cu succes!", Alert.AlertType.INFORMATION));
            });
            fileClient.setOnDownloadError((fileName, error) -> 
                Platform.runLater(() -> showAlert("Eroare Descărcare", 
                    "Nu s-a putut descărca " + fileName + ":\n" + error, Alert.AlertType.ERROR)));
            
            // Actualizează UI
            Platform.runLater(() -> {
                String shortId = discoveryService.getPeerId().substring(0, 8);
                peerIdLabel.setText("Peer ID: " + shortId + "...");
                statusLabel.setText("● Online");
                statusLabel.getStyleClass().remove("status-offline");
                statusLabel.getStyleClass().add("status-online");
                
                addFileButton.setDisable(false);
                refreshSharedButton.setDisable(false);
                refreshNetworkButton.setDisable(false);
                
                updateSharedFilesList();
                updateStatistics();
            });
            
            log("🚀 Servicii P2P pornite cu succes!");
            log("📍 ID-ul tău: " + discoveryService.getPeerId().substring(0, 16) + "...");
            log("🔌 Port UDP (discovery): " + discoveryService.getDiscoveryPort());
            log("🔌 Port TCP (transfer): " + fileTransferPort);
            
        } catch (Exception e) {
            logger.error("Eroare la pornirea serviciilor", e);
            showAlert("Eroare", "Nu s-au putut porni serviciile P2P:\n" + e.getMessage(), 
                     Alert.AlertType.ERROR);
        }
    }
    
    /**
     * Callback când un peer nou e descoperit
     */
    private void onPeerDiscovered(PeerInfo peer) {
        connectedPeers.put(peer.getPeerId(), peer);
        log("🌐 Peer nou conectat: " + peer.getPeerId().substring(0, 8) + "... @ " + peer.getAddress());
        
        // Cere lista de fișiere de la noul peer
        new Thread(() -> {
            List<FileInfo> peerFiles = fileClient.requestFileList(peer);
            Platform.runLater(() -> {
                for (FileInfo file : peerFiles) {
                    if (!networkFiles.contains(file)) {
                        networkFiles.add(file);
                    }
                }
                updateStatistics();
                log("📋 Primite " + peerFiles.size() + " fișiere de la peer " + 
                    peer.getPeerId().substring(0, 8) + "...");
            });
        }, "FetchFiles-" + peer.getPeerId().substring(0, 8)).start();
        
        Platform.runLater(this::updateStatistics);
    }
    
    /**
     * Callback când un peer se deconectează
     */
    private void onPeerLost(String peerId) {
        connectedPeers.remove(peerId);
        
        // Elimină fișierele de la peer-ul deconectat
        Platform.runLater(() -> {
            networkFiles.removeIf(file -> peerId.equals(file.getOwnerPeerId()));
            updateStatistics();
        });
        
        log("❌ Peer deconectat: " + peerId.substring(0, 8) + "...");
    }
    
    /**
     * Callback când un peer adaugă un fișier nou (inclusiv propriile fișiere)
     */
    private void onFileAddedByPeer(FileInfo fileInfo) {
        Platform.runLater(() -> {
            // Verifică dacă fișierul nu există deja în networkFiles
            boolean exists = networkFiles.stream()
                .anyMatch(f -> f.getFileHash().equals(fileInfo.getFileHash()));
            
            if (!exists) {
                networkFiles.add(fileInfo);
                updateStatistics();
                
                // Determină dacă e fișierul propriu sau de la alt peer
                String ownerId = fileInfo.getOwnerPeerId();
                boolean isOwnFile = (ownerId != null && ownerId.equals(discoveryService.getPeerId()));
                
                if (isOwnFile) {
                    log("📋 Fișierul tău apare în Available Files: " + fileInfo.getFileName());
                } else {
                    log("✨ Fișier nou disponibil: " + fileInfo.getFileName() + 
                        " de la peer " + ownerId.substring(0, 8) + "...");
                }
            }
        });
    }
    
    /**
     * Adaugă un fișier la partajare
     */
    @FXML
    private void handleAddFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selectează Fișier pentru Partajare");
        File selectedFile = fileChooser.showOpenDialog(primaryStage);
        
        if (selectedFile != null) {
            boolean success = fileServer.addSharedFile(selectedFile.toPath());
            
            if (success) {
                updateSharedFilesList();
                log("➕ Fișier adăugat: " + selectedFile.getName());
                showAlert("Succes", "Fișierul a fost adăugat la partajare!", 
                         Alert.AlertType.INFORMATION);
                // Fișierul va apărea automat în networkFiles prin callback-ul onFileAdded
            } else {
                showAlert("Eroare", "Nu s-a putut adăuga fișierul!", 
                         Alert.AlertType.ERROR);
            }
        }
    }
    
    /**
     * Descarcă fișierul selectat
     */
    @FXML
    private void handleDownloadFile() {
        FileInfo selectedFile = networkFilesTable.getSelectionModel().getSelectedItem();
        
        if (selectedFile == null) {
            showAlert("Atenție", "Selectează un fișier pentru descărcare!", 
                     Alert.AlertType.WARNING);
            return;
        }
        
        log("⬇ Începe descărcarea: " + selectedFile.getFileName());
        fileClient.downloadFileAsync(selectedFile);
    }
    
    /**
     * Reîmprospătează lista de fișiere partajate
     */
    @FXML
    private void handleRefreshShared() {
        fileServer.scanSharedFolder();
        updateSharedFilesList();
        log("🔄 Listă fișiere partajate actualizată");
    }
    
    /**
     * Reîmprospătează lista de fișiere din rețea
     */
    @FXML
    private void handleRefreshNetwork() {
        log("🔄 Actualizare fișiere din rețea...");
        networkFiles.clear();
        
        // Adaugă mai întâi propriile fișiere
        networkFiles.addAll(sharedFiles);
        
        new Thread(() -> {
            for (PeerInfo peer : connectedPeers.values()) {
                List<FileInfo> peerFiles = fileClient.requestFileList(peer);
                Platform.runLater(() -> {
                    for (FileInfo file : peerFiles) {
                        if (!networkFiles.contains(file)) {
                            networkFiles.add(file);
                        }
                    }
                    updateStatistics();
                });
            }
            Platform.runLater(() -> log("✅ Actualizare completă"));
        }, "RefreshNetwork").start();
    }
    
    /**
     * Șterge log-ul de activitate
     */
    @FXML
    private void handleClearLog() {
        activityLog.clear();
    }
    
    /**
     * Actualizează lista de fișiere partajate
     */
    private void updateSharedFilesList() {
        sharedFiles.clear();
        sharedFiles.addAll(fileServer.getSharedFiles());
        
        // Setează owner peer ID pentru fișierele proprii și adaugă în lista de fișiere disponibile
        if (discoveryService != null) {
            for (FileInfo file : sharedFiles) {
                file.setOwnerPeerId(discoveryService.getPeerId());
                if (!networkFiles.contains(file)) {
                    networkFiles.add(file);
                }
            }
        }
        
        updateStatistics();
    }
    
    /**
     * Actualizează statisticile
     */
    private void updateStatistics() {
        connectedPeersLabel.setText("Peers conectați: " + connectedPeers.size());
        sharedFilesLabel.setText("Fișiere partajate: " + sharedFiles.size());
        availableFilesLabel.setText("Fișiere disponibile: " + networkFiles.size());
    }
    
    /**
     * Adaugă un mesaj în log
     */
    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            activityLog.appendText("[" + timestamp + "] " + message + "\n");
        });
    }
    
    /**
     * Afișează un dialog de alertă
     */
    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
    
    /**
     * Oprește serviciile la închiderea aplicației
     */
    public void shutdown() {
        log("🛑 Oprire servicii...");
        
        if (discoveryService != null) {
            discoveryService.stop();
        }
        
        if (fileServer != null) {
            fileServer.stop();
        }
        
        logger.info("Aplicație închisă");
    }
    
    /**
     * Găsește un port TCP disponibil în intervalul specificat
     */
    private int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port ocupat, încearcă următorul
            }
        }
        throw new RuntimeException("Nu s-a găsit niciun port disponibil între " + startPort + " și " + endPort);
    }
    
    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }
}
