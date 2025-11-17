package ro.facultate.sd.p2p.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.application.Platform;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MainController {

    @FXML private TextField portField;
    @FXML private TextField usernameField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Label peersCountLabel;
    
    @FXML private TableView<FileEntry> sharedFilesTable;
    @FXML private TableColumn<FileEntry, String> sharedFileNameColumn;
    @FXML private TableColumn<FileEntry, String> sharedFileSizeColumn;
    @FXML private TableColumn<FileEntry, String> sharedFileTypeColumn;
    @FXML private Button addFileButton;
    @FXML private Button removeFileButton;
    
    @FXML private TableView<PeerEntry> peersTable;
    @FXML private TableColumn<PeerEntry, String> peerNameColumn;
    @FXML private TableColumn<PeerEntry, String> peerIpColumn;
    @FXML private TableColumn<PeerEntry, String> peerStatusColumn;
    
    @FXML private TableView<FileEntry> availableFilesTable;
    @FXML private TableColumn<FileEntry, String> availableFileNameColumn;
    @FXML private TableColumn<FileEntry, String> availableFileSizeColumn;
    @FXML private TableColumn<FileEntry, String> availableFilePeerColumn;
    @FXML private Button downloadButton;
    
    @FXML private TextArea logArea;

    private ObservableList<FileEntry> sharedFiles = FXCollections.observableArrayList();
    private ObservableList<PeerEntry> peers = FXCollections.observableArrayList();
    private ObservableList<FileEntry> availableFiles = FXCollections.observableArrayList();
    
    private boolean isRunning = false;

    @FXML
    public void initialize() {
        // Setup Shared Files Table
        sharedFileNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        sharedFileSizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sharedFileTypeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        sharedFilesTable.setItems(sharedFiles);
        
        // Setup Peers Table
        peerNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        peerIpColumn.setCellValueFactory(new PropertyValueFactory<>("ip"));
        peerStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        peersTable.setItems(peers);
        
        // Setup Available Files Table
        availableFileNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        availableFileSizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        availableFilePeerColumn.setCellValueFactory(new PropertyValueFactory<>("peer"));
        availableFilesTable.setItems(availableFiles);
        
        // Initial state
        portField.setText("8080");
        usernameField.setText("User" + (int)(Math.random() * 1000));
        stopButton.setDisable(true);
        statusLabel.setText("●  Oprit");
        statusLabel.setStyle("-fx-text-fill: #e74c3c;");
        peersCountLabel.setText("0 peers conectați");
        
        addLog("Aplicație inițializată. Configurați setările și apăsați Start.");
    }

    @FXML
    private void handleStart() {
        isRunning = true;
        startButton.setDisable(true);
        stopButton.setDisable(false);
        portField.setDisable(true);
        usernameField.setDisable(true);
        
        statusLabel.setText("●  Rulează");
        statusLabel.setStyle("-fx-text-fill: #27ae60;");
        
        addLog("Server pornit pe portul " + portField.getText());
        addLog("Username: " + usernameField.getText());
        
        // Simulare descoperire peers
        simulateNetworkActivity();
    }

    @FXML
    private void handleStop() {
        isRunning = false;
        startButton.setDisable(false);
        stopButton.setDisable(true);
        portField.setDisable(false);
        usernameField.setDisable(false);
        
        statusLabel.setText("●  Oprit");
        statusLabel.setStyle("-fx-text-fill: #e74c3c;");
        
        peers.clear();
        availableFiles.clear();
        peersCountLabel.setText("0 peers conectați");
        
        addLog("Server oprit.");
    }

    @FXML
    private void handleAddFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selectează fișier de partajat");
        File file = fileChooser.showOpenDialog(addFileButton.getScene().getWindow());
        
        if (file != null) {
            String size = formatFileSize(file.length());
            String type = getFileExtension(file.getName());
            sharedFiles.add(new FileEntry(file.getName(), size, type, "Local"));
            addLog("Fișier adăugat: " + file.getName() + " (" + size + ")");
        }
    }

    @FXML
    private void handleRemoveFile() {
        FileEntry selected = sharedFilesTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            sharedFiles.remove(selected);
            addLog("Fișier șters din partajare: " + selected.getName());
        } else {
            showAlert("Selectați un fișier pentru a-l șterge.");
        }
    }

    @FXML
    private void handleDownload() {
        FileEntry selected = availableFilesTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            addLog("Descărcare inițiată: " + selected.getName() + " de la " + selected.getPeer());
            showAlert("Descărcare simulată pentru: " + selected.getName());
        } else {
            showAlert("Selectați un fișier pentru descărcare.");
        }
    }

    @FXML
    private void handleRefresh() {
        addLog("Actualizare listă fișiere disponibile...");
        if (isRunning) {
            simulateFileDiscovery();
        }
    }

    private void simulateNetworkActivity() {
        // Simulare descoperire peers
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                Platform.runLater(() -> {
                    peers.add(new PeerEntry("User123", "192.168.1.101:8080", "Conectat"));
                    peers.add(new PeerEntry("User456", "192.168.1.102:8081", "Conectat"));
                    peers.add(new PeerEntry("User789", "192.168.1.103:8082", "Conectat"));
                    peersCountLabel.setText(peers.size() + " peers conectați");
                    addLog("Descoperit " + peers.size() + " peers în rețea.");
                });
                
                Thread.sleep(1000);
                Platform.runLater(() -> simulateFileDiscovery());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void simulateFileDiscovery() {
        availableFiles.clear();
        availableFiles.add(new FileEntry("Document.pdf", "2.3 MB", "pdf", "User123"));
        availableFiles.add(new FileEntry("Prezentare.pptx", "5.1 MB", "pptx", "User123"));
        availableFiles.add(new FileEntry("Image.jpg", "1.8 MB", "jpg", "User456"));
        availableFiles.add(new FileEntry("Video.mp4", "45.2 MB", "mp4", "User789"));
        availableFiles.add(new FileEntry("Music.mp3", "3.5 MB", "mp3", "User456"));
        addLog("Găsite " + availableFiles.size() + " fișiere disponibile în rețea.");
    }

    private void addLog(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.appendText("[" + timestamp + "] " + message + "\n");
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("P2P File Sharing");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        else return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toUpperCase() : "FILE";
    }

    // Inner classes for table data
    public static class FileEntry {
        private String name;
        private String size;
        private String type;
        private String peer;

        public FileEntry(String name, String size, String type, String peer) {
            this.name = name;
            this.size = size;
            this.type = type;
            this.peer = peer;
        }

        public String getName() { return name; }
        public String getSize() { return size; }
        public String getType() { return type; }
        public String getPeer() { return peer; }
    }

    public static class PeerEntry {
        private String name;
        private String ip;
        private String status;

        public PeerEntry(String name, String ip, String status) {
            this.name = name;
            this.ip = ip;
            this.status = status;
        }

        public String getName() { return name; }
        public String getIp() { return ip; }
        public String getStatus() { return status; }
    }
}
