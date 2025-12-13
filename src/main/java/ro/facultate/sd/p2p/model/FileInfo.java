package ro.facultate.sd.p2p.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Reprezintă informații despre un fișier partajat în rețeaua P2P
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String fileName;
    private long fileSize;
    private String fileHash; // MD5 hash pentru verificare integritate
    private String ownerPeerId; // ID-ul peer-ului care deține fișierul
    private String ownerAddress; // Adresa IP a peer-ului
    private int ownerPort; // Portul TCP pentru transfer
    
    public FileInfo() {
    }
    
    public FileInfo(String fileName, long fileSize, String fileHash) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
    }
    
    // Getters și Setters
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getFileSizeFormatted() {
        if (fileSize < 1024) {
            return fileSize + " B";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.2f KB", fileSize / 1024.0);
        } else if (fileSize < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", fileSize / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public String getOwnerPeerId() {
        return ownerPeerId;
    }
    
    public void setOwnerPeerId(String ownerPeerId) {
        this.ownerPeerId = ownerPeerId;
    }
    
    public String getOwnerAddress() {
        return ownerAddress;
    }
    
    public void setOwnerAddress(String ownerAddress) {
        this.ownerAddress = ownerAddress;
    }
    
    public int getOwnerPort() {
        return ownerPort;
    }
    
    public void setOwnerPort(int ownerPort) {
        this.ownerPort = ownerPort;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(fileName, fileInfo.fileName) && 
               Objects.equals(fileHash, fileInfo.fileHash);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fileName, fileHash);
    }
    
    @Override
    public String toString() {
        return fileName + " (" + getFileSizeFormatted() + ")";
    }
}
