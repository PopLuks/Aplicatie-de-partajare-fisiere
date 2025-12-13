package ro.facultate.sd.p2p.model;

import java.io.Serializable;
import java.util.List;

/**
 * Mesaj pentru comunicarea P2P între noduri
 */
public class P2PMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum MessageType {
        // Mesaje de descoperire (UDP)
        PEER_ANNOUNCE,      // "Salut, sunt aici!"
        PEER_RESPONSE,      // "Salut înapoi, iată-mă"
        REQUEST_FILE_LIST,  // "Ce fișiere ai?"
        FILE_LIST_RESPONSE, // "Iată lista mea de fișiere"
        
        // Mesaje de transfer (TCP)
        FILE_REQUEST,       // "Vreau să descarc fișierul X"
        FILE_ACCEPT,        // "OK, încep să trimit"
        FILE_REJECT,        // "Nu am fișierul sau sunt ocupat"
        FILE_CHUNK,         // Bucată de date din fișier
        FILE_COMPLETE,      // "Am terminat de trimis"
        
        // Mesaje de mentenanță
        PING,               // Verificare dacă peer-ul mai e activ
        PONG                // Răspuns la PING
    }
    
    private MessageType type;
    private PeerInfo senderInfo;
    private List<FileInfo> fileList;
    private String requestedFileName;
    private byte[] fileData;
    private String errorMessage;
    
    public P2PMessage() {
    }
    
    public P2PMessage(MessageType type) {
        this.type = type;
    }
    
    public P2PMessage(MessageType type, PeerInfo senderInfo) {
        this.type = type;
        this.senderInfo = senderInfo;
    }
    
    // Getters și Setters
    public MessageType getType() {
        return type;
    }
    
    public void setType(MessageType type) {
        this.type = type;
    }
    
    public PeerInfo getSenderInfo() {
        return senderInfo;
    }
    
    public void setSenderInfo(PeerInfo senderInfo) {
        this.senderInfo = senderInfo;
    }
    
    public List<FileInfo> getFileList() {
        return fileList;
    }
    
    public void setFileList(List<FileInfo> fileList) {
        this.fileList = fileList;
    }
    
    public String getRequestedFileName() {
        return requestedFileName;
    }
    
    public void setRequestedFileName(String requestedFileName) {
        this.requestedFileName = requestedFileName;
    }
    
    public byte[] getFileData() {
        return fileData;
    }
    
    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    @Override
    public String toString() {
        return "P2PMessage{" +
                "type=" + type +
                ", sender=" + (senderInfo != null ? senderInfo.getPeerId() : "null") +
                '}';
    }
}
