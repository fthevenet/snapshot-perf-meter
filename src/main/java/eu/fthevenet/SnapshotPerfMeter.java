/*
 *    Copyright 2020 Frederic Thevenet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package eu.fthevenet;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class SnapshotPerfMeter extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        StackPane root = new StackPane();
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
        displayInfo();
        if (!isOptionEnabled("--version")) {
            takeSnapshots(isOptionEnabled("--save"));
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void takeSnapshots(boolean saveImages) {
        var img = new Image(getClass().getResourceAsStream("/Duke_1024.png"));
        int step = 1;
        int runs = 10;
        for (int x = step; x <= 8 + step; x += step) {
            for (int y = step; y <= 8 + step; y += step) {
                // Invoke gc explicitly in order to minimize chances
                // it happens during the metered block
                System.gc();

                WritableImage snapImg = null;
                var node = new ImageView(img);
                node.getTransforms().add(Transform.scale(x, y));
                int width = (int) Math.ceil(x * img.getWidth());
                int height = (int) Math.ceil(y * img.getHeight());
                for (int i = 0; i < runs; i++) {
                    String elapsedMs = "";
                    try {
                        long start = System.nanoTime();
                        snapImg = node.snapshot(null, null);
                        long stopTime = System.nanoTime();
                        elapsedMs = ((stopTime - start) / 1000000.0) + " ms";
                    } catch (Exception e) {
                        // could not complete
                        elapsedMs = "!failed!";
                    }
                    System.out.println(String.format("Snapshot %dx%d (run %d): %s", width, height, i, elapsedMs));
                }
                if (saveImages) {
                    var p = Path.of("snapshot_" + width + "x" + height + ".png");
                    try {
                        Files.deleteIfExists(p);
                        ImageIO.write(
                                SwingFXUtils.fromFXImage(snapImg, null),
                                "png",
                                p.toFile());
                    } catch (Exception e) {
                        System.err.println("Could not save image " + p + ": " + e.getMessage());
                    }
                }
                System.out.println("--------");
            }
        }


    }

    private boolean isOptionEnabled(String option) {
        return getParameters().getUnnamed().contains(option);
    }

    private void displayInfo() {
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("JavaFX Version: " + System.getProperty("javafx.version"));
        System.out.println("Java VM name: " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.version") + ")");
        System.out.println("Operating System: " + System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")");
        System.out.println("System Architecture: " + System.getProperty("os.arch"));
        System.out.println("JVM Heap Stats: " + getHeapStats());
        System.out.println("Garbage Collectors: " + getGcNames());
        System.out.println();
    }

    private String getHeapStats() {
        Runtime rt = Runtime.getRuntime();
        double maxMB = rt.maxMemory() / 1024.0 / 1024.0;
        double committedMB = (double) rt.totalMemory() / 1024.0 / 1024.0;
        double usedMB = ((double) rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0;
        return String.format(
                "Max: %.0fMB | Committed: %.0fMB | Used: %.0fMB",
                maxMB,
                committedMB,
                usedMB
        );
    }

    private String getGcNames() {
        return ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .map(MemoryManagerMXBean::getName)
                .collect(Collectors.joining(", "));
    }


}
