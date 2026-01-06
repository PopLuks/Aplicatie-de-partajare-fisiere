package ro.facultate.sd.p2p.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
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
    @FXML private TableColumn<FileInfo, Void> networkProgressColumn;
    @FXML private TableColumn<FileInfo, Void> networkActionsColumn;
    @FXML private TableColumn<FileInfo, String> networkHashColumn;
    
    @FXML private Button addFileButton;
    @FXML private Button deleteFileButton;
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
    private final Map<String, Double> downloadProgress = new ConcurrentHashMap<>();
    private final Map<String, Boolean> downloadPaused = new ConcurrentHashMap<>();
    private final Map<String, Double> lastLoggedProgress = new ConcurrentHashMap<>(); // Pentru a loga doar la intervale
    
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
        deleteFileButton.setDisable(true);
        refreshSharedButton.setDisable(true);
        refreshNetworkButton.setDisable(true);
        
        // Activează butonul de ștergere când se selectează un fișier
        sharedFilesTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldSelection, newSelection) -> {
                deleteFileButton.setDisable(newSelection == null);
            });
        
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
            if (peerId != null) {
                // Verifică dacă e propriul peer
                if (discoveryService != null && peerId.equals(discoveryService.getPeerId())) {
                    return new SimpleStringProperty("Tu (local)");
                }
                return new SimpleStringProperty(peerId.substring(0, 8) + "...");
            }
            return new SimpleStringProperty("?");
        });
        networkHashColumn.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().getFileHash().substring(0, 12) + "..."));
        
        // Coloană cu ProgressBar - cu actualizare automată
        networkProgressColumn.setCellFactory(col -> new TableCell<FileInfo, Void>() {
            private final javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
            private final Label statusLabel = new Label("-");
            private final javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(2);
            private String currentFileName = null;
            
            {
                progressBar.setPrefWidth(120);
                progressBar.setPrefHeight(18);
                statusLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #666;");
                container.getChildren().addAll(progressBar, statusLabel);
                container.setAlignment(javafx.geometry.Pos.CENTER);
                
                // Timeline pentru actualizare automată la fiecare 100ms
                javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(100), e -> {
                        if (currentFileName != null) {
                            Double progress = downloadProgress.get(currentFileName);
                            if (progress != null && progress > 0) {
                                progressBar.setProgress(progress / 100.0);
                                statusLabel.setText(String.format("%.1f%%", progress));
                                progressBar.setStyle("-fx-accent: #4CAF50;");
                            }
                        }
                    })
                );
                timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
                timeline.play();
            }
            
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    currentFileName = null;
                } else {
                    FileInfo fileInfo = getTableRow().getItem();
                    currentFileName = fileInfo.getFileName();
                    Double progress = downloadProgress.get(currentFileName);
                    
                    if (progress != null) {
                        progressBar.setProgress(progress / 100.0);
                        statusLabel.setText(String.format("%.1f%%", progress));
                        progressBar.setStyle("-fx-accent: #4CAF50;");
                    } else {
                        progressBar.setProgress(0);
                        statusLabel.setText("-");
                        progressBar.setStyle("-fx-accent: #2196F3;");
                    }
                    setGraphic(container);
                }
            }
        });
        
        // Coloană cu butoane Descarcă/Pauză/Resume
        networkActionsColumn.setCellFactory(col -> new TableCell<FileInfo, Void>() {
            private final Button actionButton = new Button();
            
            {
                actionButton.setPrefWidth(70);
                actionButton.setOnAction(event -> {
                    FileInfo fileInfo = getTableRow().getItem();
                    if (fileInfo != null) {
                        handleDownloadAction(fileInfo);
                    }
                });
            }
            
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    FileInfo fileInfo = getTableRow().getItem();
                    String fileName = fileInfo.getFileName();
                    
                    // Verifică dacă e propriul fișier
                    boolean isOwnFile = discoveryService != null && 
                                       fileInfo.getOwnerPeerId() != null &&
                                       fileInfo.getOwnerPeerId().equals(discoveryService.getPeerId());
                    
                    if (isOwnFile) {
                        actionButton.setText("Local");
                        actionButton.setDisable(true);
                        actionButton.setStyle("-fx-background-color: #9E9E9E;");
                    } else {
                        Double progress = downloadProgress.get(fileName);
                        Boolean isPaused = downloadPaused.getOrDefault(fileName, false);
                        
                        if (progress != null && progress > 0 && progress < 100) {
                            if (isPaused) {
                                actionButton.setText("▶ Resume");
                                actionButton.setStyle("-fx-background-color: #4CAF50;");
                            } else {
                                actionButton.setText("⏸ Pauză");
                                actionButton.setStyle("-fx-background-color: #FF9800;");
                            }
                            actionButton.setDisable(false);
                        } else if (progress != null && progress >= 100) {
                            actionButton.setText("✓ Gata");
                            actionButton.setDisable(true);
                            actionButton.setStyle("-fx-background-color: #4CAF50;");
                        } else {
                            actionButton.setText("⬇ Descarcă");
                            actionButton.setDisable(false);
                            actionButton.setStyle("-fx-background-color: #2196F3;");
                        }
                    }
                    setGraphic(actionButton);
                }
            }
        });
        
        networkFilesTable.setItems(networkFiles);
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
            fileClient.setOnDownloadStart(fileName -> {
                // Resetează progresul la început de descărcare
                Platform.runLater(() -> {
                    downloadProgress.put(fileName, 0.1); // 0.1% pentru a indica că descărcarea a început
                    downloadPaused.put(fileName, false); // Setează explicit că nu e pe pauză
                    lastLoggedProgress.put(fileName, -1.0);
                    networkFilesTable.refresh();
                });
                log("📥 Începe descărcarea: " + fileName);
            });
            fileClient.setOnDownloadProgress((fileName, progress) -> 
                Platform.runLater(() -> {
                    // Asigură-te că progresul nu depășește 100%
                    double safeProgress = Math.min(progress, 100.0);
                    downloadProgress.put(fileName, safeProgress);
                    // NU mai facem refresh - progress bar-ul se actualizează automat prin Timeline
                    // NU mai logăm procentele - se vede vizual în progress bar
                }));
            fileClient.setOnDownloadComplete(fileName -> {
                log("✅ Descărcare completă: " + fileName);
                
                // Primul runLater: actualizează progresul la 100%
                Platform.runLater(() -> {
                    downloadProgress.put(fileName, 100.0);
                    downloadPaused.remove(fileName);
                    lastLoggedProgress.remove(fileName); // Cleanup
                    networkFilesTable.refresh(); // Refresh UI
                    
                    // Al doilea runLater: afișează dialogul DUPĂ ce UI-ul e actualizat
                    Platform.runLater(() -> {
                        showAlert("Descărcare Completă", 
                            "Fișierul " + fileName + " a fost descărcat cu succes!", Alert.AlertType.INFORMATION);
                    });
                });
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
                    
                // Marchează fișierele deja descărcate după ce avem lista completă
                markExistingDownloadsAsComplete();
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
     * Gestionează acțiunea de descărcare/pauză/resume din coloana de acțiuni
     */
    private void handleDownloadAction(FileInfo fileInfo) {
        String fileName = fileInfo.getFileName();
        Double progress = downloadProgress.get(fileName);
        Boolean isPaused = downloadPaused.getOrDefault(fileName, false);
        
        // Verifică dacă e propriul fișier
        if (discoveryService != null && fileInfo.getOwnerPeerId() != null &&
            fileInfo.getOwnerPeerId().equals(discoveryService.getPeerId())) {
            showAlert("Atenție", "Nu poți descărca propriul fișier!", Alert.AlertType.WARNING);
            return;
        }
        
        if (progress != null && progress > 0 && progress < 100) {
            // E în curs de descărcare - toggle pauză/resume
            if (isPaused) {
                // Resume download
                downloadPaused.put(fileName, false);
                fileClient.resumeDownload(fileName);
                log("▶️ Resume descărcare: " + fileName);
            } else {
                // Pause download
                downloadPaused.put(fileName, true);
                fileClient.pauseDownload(fileName);
                log("⏸️ Descărcare pusă pe pauză: " + fileName);
            }
            networkFilesTable.refresh(); // Refresh UI
        } else {
            // Start new download
            downloadProgress.put(fileName, 0.0);
            downloadPaused.put(fileName, false);
            log("⬇️ Începe descărcarea: " + fileName);
            fileClient.downloadFileAsync(fileInfo);
            networkFilesTable.refresh(); // Refresh UI
        }
    }
    
    /**
     * Șterge fișierul selectat din partajare
     */
    @FXML
    private void handleDeleteFile() {
        FileInfo selectedFile = sharedFilesTable.getSelectionModel().getSelectedItem();
        
        if (selectedFile == null) {
            showAlert("Atenție", "Selectează un fișier pentru ștergere!", 
                     Alert.AlertType.WARNING);
            return;
        }
        
        // Confirmă ștergerea
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Confirmare Ștergere");
        confirmAlert.setHeaderText("Ștergi fișierul: " + selectedFile.getFileName());
        confirmAlert.setContentText("Ești sigur că vrei să ștergi acest fișier?\n\nFișierul va fi șters din folderul P2P-Shared.");
        
        if (confirmAlert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                Path filePath = sharedFolder.resolve(selectedFile.getFileName());
                Files.deleteIfExists(filePath);
                
                // Actualizează lista
                fileServer.scanSharedFolder();
                updateSharedFilesList();
                
                log("🗑 Fișier șters: " + selectedFile.getFileName());
                showAlert("Succes", "Fișierul a fost șters cu succes!", 
                         Alert.AlertType.INFORMATION);
            } catch (IOException e) {
                log("❌ Eroare la ștergere: " + e.getMessage());
                showAlert("Eroare", "Nu s-a putut șterge fișierul:\n" + e.getMessage(), 
                         Alert.AlertType.ERROR);
            }
        }
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
        
        // Curăță progresul pentru fișierele șterse
        cleanupDeletedDownloads();
        
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
            Platform.runLater(() -> {
                // Marchează fișierele deja descărcate DUPĂ ce avem lista completă
                markExistingDownloadsAsComplete();
                log("✅ Actualizare completă");
            });
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
     * Curăță progresul pentru fișierele care au fost șterse din folderul de download
     */
    private void cleanupDeletedDownloads() {
        try {
            // Verifică fiecare fișier din downloadProgress
            downloadProgress.keySet().removeIf(fileName -> {
                Path filePath = downloadFolder.resolve(fileName);
                Path partialPath = downloadFolder.resolve(fileName + ".partial");
                
                // Dacă nu există nici fișierul final, nici cel parțial, resetează progresul
                if (!java.nio.file.Files.exists(filePath) && !java.nio.file.Files.exists(partialPath)) {
                    log("🗑️ Fișier șters detectat: " + fileName + " - resetez progresul");
                    downloadPaused.remove(fileName);
                    return true; // Șterge din map
                }
                return false;
            });
            
            // Refresh UI după cleanup
            networkFilesTable.refresh();
            
        } catch (Exception e) {
            logger.error("Eroare la curățarea progresului", e);
        }
    }
    
    /**
     * Scanează folderul P2P-Downloads și marchează fișierele existente ca fiind complete
     */
    private void markExistingDownloadsAsComplete() {
        try {
            log("📂 Scanez folder: " + downloadFolder.toAbsolutePath());
            
            if (Files.exists(downloadFolder)) {
                int markedCount = 0;
                
                // Scanează toate fișierele (inclusiv .partial)
                var allFiles = Files.list(downloadFolder)
                    .filter(Files::isRegularFile)
                    .toList();
                
                log("🔍 Scanez " + allFiles.size() + " fișiere în P2P-Downloads...");
                log("🌐 Fișiere în rețea: " + networkFiles.size());
                
                // Afișează toate fișierele din rețea
                for (FileInfo netFile : networkFiles) {
                    log("  🌍 În rețea: " + netFile.getFileName());
                }
                
                for (Path path : allFiles) {
                    String fileName = path.getFileName().toString();
                    String baseFileName = fileName.endsWith(".partial") 
                        ? fileName.substring(0, fileName.length() - 8) 
                        : fileName;
                    
                    // Verifică dacă fișierul (sau versiunea lui fără .partial) există în networkFiles
                    FileInfo matchingFile = networkFiles.stream()
                        .filter(fileInfo -> fileInfo.getFileName().equals(baseFileName))
                        .findFirst()
                        .orElse(null);
                    
                    if (matchingFile != null) {
                        if (fileName.endsWith(".partial")) {
                            // Fișier parțial - calculează progresul real
                            long partialSize = Files.size(path);
                            long totalSize = matchingFile.getFileSize();
                            double progress = (partialSize * 100.0) / totalSize;
                            
                            downloadProgress.put(baseFileName, Math.min(progress, 99.9)); // Max 99.9% pentru partial
                            log("⏸ Fișier parțial: " + baseFileName + " - " + String.format("%.1f%%", progress));
                        } else {
                            // Fișier complet
                            downloadProgress.put(fileName, 100.0);
                            markedCount++;
                            log("✓ Fișier deja descărcat: " + fileName);
                        }
                    } else {
                        log("  📄 " + fileName + " - în rețea: false");
                    }
                }
                
                log("✅ Marcat " + markedCount + " fișiere ca descărcate");
                    
                // Refresh UI pentru a afișa progresul
                networkFilesTable.refresh();
            } else {
                log("❌ Folder P2P-Downloads nu există!");
            }
        } catch (IOException e) {
            logger.error("Eroare la scanarea fișierelor descărcate", e);
            log("❌ Eroare la scanare: " + e.getMessage());
        }
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
