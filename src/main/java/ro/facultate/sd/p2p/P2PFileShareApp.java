package ro.facultate.sd.p2p;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class P2PFileShareApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/ro/facultate/sd/p2p/ui/MainWindow.fxml"));
        Parent root = loader.load();
        
        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(getClass().getResource("/ro/facultate/sd/p2p/ui/styles.css").toExternalForm());
        
        primaryStage.setTitle("P2P File Sharing - Aplicație de Partajare Fișiere");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
