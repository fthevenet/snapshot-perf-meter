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
import javafx.scene.image.*;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import org.apache.commons.math.MathException;
import org.apache.commons.math.distribution.TDistributionImpl;
import org.apache.commons.math.stat.StatUtils;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

public class SnapshotPerfMeter extends Application {

    private int getX;
    private int getY;
    private int getStep;
    private int getRuns;
    private int getMax;
    private boolean isVerbose;
    private boolean isSaveImages;
    private ImageFormat getImageFormat;

    public enum ImageFormat{
        NONE,
        IMPLICIT,
        BYTE_BGRA,
        INT_ARGB
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        setParams();
        primaryStage.setScene(new Scene(new StackPane()));
        primaryStage.setWidth(320);
        primaryStage.setHeight(240);
        primaryStage.show();
        displayInfo();
        if (!isOptionEnabled("--version")) {
            var img = new Image(getClass().getResourceAsStream("/Duke_1024.png"));
            if (getX > 0 && getY > 0) {
                doSnapshot(img, getX, getY, getRuns);

            } else {
                takeSnapshots(img, getStep, getMax, getRuns, true);
            }
            System.out.println("JVM Heap Stats: " + getHeapStats());
        }
        Platform.exit();
    }

    private void setParams() {
        getX = getParamAsInt("x", -1);
        getY = getParamAsInt("y", -1);
        getStep = getParamAsInt("step", 1);
        getRuns = getParamAsInt("runs", 10);
        getMax = getParamAsInt("max", 9);
        getImageFormat = getParamAsEnum("imageFormat", ImageFormat.NONE);
        isSaveImages = isOptionEnabled("--save");
        isVerbose = isOptionEnabled("--verbose");
    }


    public static void main(String[] args) {
        launch(args);
    }

    private void takeSnapshots(Image img, int step, int max, int runs, boolean makeMarkdownTable) throws Exception {
        int imgWidth = (int) img.getWidth();
        int imgHeigt = (int) img.getHeight();
        double[/* snapshot height */][/* snapshot width */] averages = new double[max / step][max / step];
        for (int x = step; x <= max; x += step) {
            for (int y = step; y <= max; y += step) {
                var avg = doSnapshot(img, x, y, runs);
                averages[y - 1][x - 1] = avg.isPresent() ? avg.getAsDouble() : Double.NaN;
            }
        }
        if (makeMarkdownTable) {
            String header = "|    | ";
            for (int x = 0; x < averages[0].length; x++) {
                header += (x + 1) * imgWidth + " |";
            }
            System.out.println(header);
            String header2 = "|---|";
            for (int x = 0; x < averages[0].length; x++) {
                header2 += "---|";
            }
            System.out.println(header2);
            for (int y = 0; y < averages.length; y++) {
                String line = "| " + (y + 1) * imgHeigt + " | ";
                for (int x = 0; x < averages[y].length; x++) {
                    line += String.format("%f | ", averages[y][x]);
                }

                System.out.println(line);
            }
        }
    }

    private OptionalDouble doSnapshot(Image img, int x, int y, int runs ) {
        // Invoke gc explicitly, in order to minimize chances
        // it happens during the metered block
        System.gc();
        int imgWidth = (int) img.getWidth();
        int imgHeigt = (int) img.getHeight();
        var node = new ImageView(img);
        node.getTransforms().add(Transform.scale(x, y));
        int width = (int) Math.ceil(x * imgWidth);
        int height = (int) Math.ceil(y * imgHeigt);
        WritableImage snapImg = createWritableImage(width, height);
        List<Double> results = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            double elapsedMs;
            try {
                long start = System.nanoTime();
                if (snapImg == null ) {
                    snapImg = node.snapshot(null, null);
                }else{
                    node.snapshot(null, snapImg);
                }
                long stopTime = System.nanoTime();
                elapsedMs = (stopTime - start) / 1000000.0;
                results.add(elapsedMs);
            } catch (Exception e) {
                // could not complete
                elapsedMs = Double.NaN;
            }
            if (isVerbose) {
                System.out.println(
                        String.format("Snapshot %dx%d (run %d): %s",
                                width, height, i, Double.isNaN(elapsedMs) ? "!failed!" : elapsedMs + " ms"));
            }
        }

        var avg = computeCorrectedAverage(results);

        System.out.println(
                String.format("Snapshot %dx%d (corrected avg): %s",
                        width, height, avg.isPresent() ? avg.getAsDouble() + " ms" : "!failed!"));
        if (isSaveImages) {
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
        return avg;
    }

    private WritableImage createWritableImage(int width, int height) {
        switch (getImageFormat) {
            case IMPLICIT:
                return new WritableImage(width, height);
            case INT_ARGB:
                PixelBuffer<IntBuffer> ipb = new PixelBuffer<>(width, height, IntBuffer.allocate(width * height), WritablePixelFormat.getIntArgbPreInstance());
                return new WritableImage(ipb);
            case BYTE_BGRA:
                PixelBuffer<ByteBuffer> bpb = new PixelBuffer<>(width, height, ByteBuffer.allocate(width * height * 4), WritablePixelFormat.getByteBgraPreInstance());
                return new WritableImage(bpb);
            default:
            case NONE:
                return null;
        }
    }

    private OptionalDouble computeCorrectedAverage(List<Double> results) {
        try {
            Double outlier = getOutlier(results, 0.95);

            while (outlier != null) {
                results.remove(outlier);
                if (isVerbose) {
                    System.out.println("Pruned value " + outlier + " from average calculation");
                }
                outlier = getOutlier(results, 0.95);
            }
            return results.stream().mapToDouble(d -> d).average();
        } catch (MathException e) {
            return OptionalDouble.empty();
        }
    }

    private boolean isOptionEnabled(String option) {
        return getParameters().getUnnamed().contains(option);
    }

    private int getParamAsInt(String paramName, int defaultValue) {
        try {
            return Integer.parseInt(getParameters().getNamed().get(paramName));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getParam(String paramName, String defaultValue) {
        String val = getParameters().getNamed().get(paramName);
        return val == null? defaultValue : val;
    }


    private <E extends Enum<?>>  E getParamAsEnum(String paramName, E defaultValue) {
        try {
            return (E)E.valueOf(defaultValue.getClass(), getParameters().getNamed().get(paramName));
        } catch (Exception e){
            return defaultValue;
        }
    }

    private void displayInfo() {
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("JavaFX Version: " + System.getProperty("javafx.version"));
        System.out.println("Java VM name: " + System.getProperty("java.vm.name") +
                " (" + System.getProperty("java.vm.version") + ")");
        System.out.println("Operating System: " + System.getProperty("os.name") +
                " (" + System.getProperty("os.version") + ")");
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


    public Double getOutlier(List<Double> values, double significanceLevel) throws MathException {
        double outlier = Double.NaN;
        double[] array = values.stream().mapToDouble(d -> d).toArray();
        double mean = StatUtils.mean(array);
        double stddev = Math.sqrt(StatUtils.variance(array));
        double maxDev = 0;
        for (var d : values) {
            if (Math.abs(mean - d) > maxDev) {
                maxDev = Math.abs(mean - d);
                outlier = d;
            }
        }
        double grubbs = maxDev / stddev;
        double size = values.size();
        if (size < 3) {
            return null;
        }
        TDistributionImpl t = new TDistributionImpl(size - 2.0);

        double criticalValue = t.inverseCumulativeProbability((1.0 - significanceLevel) / (2.0 * size));
        double criticalValueSquare = criticalValue * criticalValue;
        double grubbsCompareValue = ((size - 1) / Math.sqrt(size)) *
                Math.sqrt((criticalValueSquare) / (size - 2.0 + criticalValueSquare));
        if (grubbs > grubbsCompareValue) {
            return outlier;
        } else {
            return null;
        }
    }

}
