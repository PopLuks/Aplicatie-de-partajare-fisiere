package ro.facultate.sd.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ro.facultate.sd.p2p.ui.MainController;

/**
 * Aplicație principală P2P File Sharing
 * Proiect pentru cursul de Sisteme Distribuite
 */
public class P2PFileShareApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(P2PFileShareApp.class);
    private MainController controller;
    
    @Override
    public void start(Stage primaryStage) {
        try {
            // Încarcă interfața FXML
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/ro/facultate/sd/p2p/ui/MainWindow.fxml")
            );
            Parent root = loader.load();
            
            // Obține controller-ul
            controller = loader.getController();
            controller.setPrimaryStage(primaryStage);
            
            // Configurează fereastra
            Scene scene = new Scene(root);
            primaryStage.setTitle("P2P File Sharing - Sisteme Distribuite");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            
            // Handler pentru închiderea aplicației
            primaryStage.setOnCloseRequest(event -> {
                controller.shutdown();
                Platform.exit();
                System.exit(0);
            });
            
            // Afișează fereastra
            primaryStage.show();
            
            // Pornește serviciile P2P după ce fereastra e vizibilă
            Platform.runLater(() -> controller.startServices());
            
            logger.info("Aplicație pornită cu succes");
            
        } catch (Exception e) {
            logger.error("Eroare la pornirea aplicației", e);
            e.printStackTrace();
            Platform.exit();
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
