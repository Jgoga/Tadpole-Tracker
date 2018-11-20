package sproj.analysis;

import java.awt.event.KeyEvent;
import java.util.*;
import java.io.File;
import java.io.IOException;

import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacv.*;
import org.deeplearning4j.nn.layers.objdetect.DetectedObject;
import sproj.util.BoundingBox;
import sproj.util.DetectionsParser;
import sproj.util.IOUtils;
import sproj.yolo.YOLOModelContainer;

import static org.bytedeco.javacpp.opencv_imgproc.CV_AA;
import static org.bytedeco.javacpp.opencv_imgproc.rectangle;
import static org.bytedeco.javacpp.opencv_imgproc.resize;

public class TrackerAccuracyEvaluator {

    private final boolean CHECK_TO_RESUME_PROGRESS = true;      // for resuming evaluation instead of restarting from beginning

    private boolean WRITE_TO_FILE;
    private boolean SHOW_LIVE_EVAL_DISPLAY;

    private FFmpegFrameGrabber grabber;
    private YOLOModelContainer yoloModelContainer;
    private DetectionsParser detectionsParser = new DetectionsParser();
    private OpenCVFrameConverter frameConverter = new OpenCVFrameConverter.ToMat();


    private void initializeGrabber(File videoFile) throws FrameGrabber.Exception {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET);           // Suppress verbose FFMPEG metadata output to console
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.start();        // open video file
    }

    private void initalizeModelContainer(File modelPath) throws IOException{
        yoloModelContainer = new YOLOModelContainer(modelPath);
    }

    public TrackerAccuracyEvaluator() {
        this(true, true);
    }

    public TrackerAccuracyEvaluator(boolean writeResultsToFile, boolean showVisualStream) {
        this.WRITE_TO_FILE = writeResultsToFile;
        this.SHOW_LIVE_EVAL_DISPLAY = showVisualStream;
    }

    private List<Double> evaluateModelOnVideo(File videoFile, int numbAnimals,
                                              opencv_core.Rect cropRectangle) throws IOException {
        initializeGrabber(videoFile);
        return evaluateOnVideo(numbAnimals, cropRectangle);
    }


    /**
     * By the time this function is called, the framegrabber has already been initialized on the
     * new video.
     *
     * Returns a list of detection accuracy evaluations, one for each frame of the video.
     *
     * Current metric for accuracy is just the number of detections / ground truth number of animals
     *
     * @param numbAnimals int
     * @param cropRect
     * @return
     * @throws IOException
     */
    private List<Double> evaluateOnVideo(int numbAnimals, opencv_core.Rect cropRect) throws IOException {

        int frameNo = 0;
        int totalFrames = grabber.getLengthInVideoFrames();

        List<DetectedObject> detectedObjects;
        List<Double> detectionAccuracies = new ArrayList<>(totalFrames);   // store one detection accuracy record per frame

        CanvasFrame canvasFrame = null;

        if (SHOW_LIVE_EVAL_DISPLAY) {
            canvasFrame = new CanvasFrame("Evaluation on video");
        }

        boolean exitLoop = false;
        Frame frame;
        opencv_core.Mat frameImg;
        KeyEvent keyEvent;

        long startTime = System.currentTimeMillis();

        while ((frame = grabber.grabImage()) != null && !exitLoop) {

            frameImg = new opencv_core.Mat(frameConverter.convertToMat(frame), cropRect);

            //todo test effects on accuracy of different frame filter algorithms

            detectedObjects = yoloModelContainer.runInference(frameImg);
            double accuracy = detectedObjects.size() / (double) numbAnimals;

            accuracy = Math.min(1.0, accuracy);

            /*
            if (COUNT_EXTRA_DETECTIONS_NEGATIVELY) {
                accuracy = accuracy > 1.0 ? 1 - Math.abs(1 - accuracy) : accuracy;     // count each extra detection as one negative detection from the score
            }*/

            detectionAccuracies.add(accuracy);

            frameNo = grabber.getFrameNumber();
            System.out.print("\r" + (frameNo + 1) + " of " + totalFrames + " frames processed");

            if (SHOW_LIVE_EVAL_DISPLAY && canvasFrame != null) {

                List<BoundingBox> boundingBoxes = detectionsParser.parseDetections(detectedObjects);

                resize(frameImg, frameImg, new opencv_core.Size(
                        YOLOModelContainer.IMG_WIDTH, YOLOModelContainer.IMG_HEIGHT)
                );

                for (BoundingBox box : boundingBoxes) {
                    rectangle(frameImg, new opencv_core.Point(box.topleftX, box.topleftY),
                            new opencv_core.Point(box.botRightX, box.botRightY),
                            opencv_core.Scalar.RED, 1, CV_AA, 0);

                }

                //resize(frameImg, frameImg, new opencv_core.Size(720, 720));

                canvasFrame.showImage(
                        frameConverter.convert(frameImg)
                );

                try {
                    keyEvent = canvasFrame.waitKey(10);
                } catch (InterruptedException ignored) {
                    continue;
                }
                if (keyEvent != null) {

                    char keyChar = keyEvent.getKeyChar();

                    switch(keyChar) {

                        case KeyEvent.VK_ESCAPE: exitLoop = true; break;      // hold escape key or 'q' to quit

                        case KeyEvent.VK_Q: {       // shift q to quit entirely
                            canvasFrame.dispose();
                            grabber.release();
                            System.exit(0);
                        }

                    }

                }
            }
        }


//        System.out.print("\r" + (frameNo + 1) + " of " + totalFrames +
//                " frames processed. Elapsed time: " + (System.currentTimeMillis()-startTime));
        long elapsedTime = System.currentTimeMillis()-startTime;

        System.out.println(String.format("\nElapsed time: %dm %.3fs",
                (int) Math.floor((int) (elapsedTime / 1000) / 60d),
                elapsedTime / 1000.0 % 60)
        );

        if (canvasFrame != null) {canvasFrame.dispose();}

        closeGrabber();

        return detectionAccuracies;
    }

    private void closeGrabber() {
        try {
            grabber.close();
        } catch (FrameGrabber.Exception ignored) {
        }
    }


    /**
     * metaVideoList is a meta list of lists of videos. Each string is a path to a text file,
     * which contains line separated video descriptions, which are
     * comma separated as follows:  full video path,number of animals in video, crop dimensions
     * eg:  /home/Videos/video1.mp4,5,230 10 720 720
     *
     * In addition, files should be organized such that each path in metaVideoList represents the complete set
     * of testing videos for a number group of animals.
     *
     * @param modelPath
     * @param metaVideoList
     * @param dataSaveName
     */
    private void evaluateModel(File modelPath, HashMap<Integer, String> metaVideoList,
                               String dataSaveName) throws IOException {

        initalizeModelContainer(modelPath);

        List<List<Double>> anmlGroupAccuracies = new ArrayList<>(metaVideoList.size()); // each inner list contains points for all videos for animal number

        Set<Integer> anmlGroupNumbs = metaVideoList.keySet();

        if (CHECK_TO_RESUME_PROGRESS) {
            if (new File(dataSaveName).exists() ||
                    new File(dataSaveName.split("\\.")[0] + anmlGroupNumbs.toArray()[0].toString() + ".eval").exists()) {
                System.out.println("Model already evaluated for current group");
                return;
            }
        }
//        for (String videosList : metaVideoList) {
        for (Integer anmlNumb : anmlGroupNumbs) {

            List<String> textLines = IOUtils.readLinesFromFile(new File(metaVideoList.get(anmlNumb)));
            List<Double> videoEvals = new ArrayList<>();       // each individual point represents accuracy over an entire video

            System.out.println("\nGroup " + anmlNumb + " videos");

            for (String individualVideo : textLines) {     // individual video file to evaluate on

                String[] split = individualVideo.split(",");

                if (split.length < 2) {
                    System.out.println(String.format("Skipping incorrectly formatted line: '%s'", individualVideo));
                    continue;
                }

                int numbAnimals;
                File videoFile;
                opencv_core.Rect cropRect;
                List<Integer> cropDims = new ArrayList<>();

                try {

                    videoFile = new File(split[0]);
                    assert videoFile.exists() && !videoFile.isDirectory();

                    numbAnimals = Integer.parseInt(split[1]);

                    // convert third string argument to 4 integers; the crop dimensions
                    Arrays.asList(split[2].split(" ")).forEach(s ->
                            cropDims.add(Integer.valueOf(s)));

                    assert cropDims.size() == 4;

                    cropRect = new opencv_core.Rect(
                            cropDims.get(0), cropDims.get(1), cropDims.get(2), cropDims.get(3)
                    );

                } catch (AssertionError | NumberFormatException ignored) {

                    System.out.println(String.format("Skipping invalid video path or incorrectly formatted line: '%s'", individualVideo));
                    continue;
                }

                System.out.println(String.format("\nVideo %d of %d", textLines.indexOf(individualVideo) + 1, textLines.size()));

                List<Double> dataPoints = evaluateModelOnVideo(videoFile, numbAnimals, cropRect);

                // one point for each video
                videoEvals.add(
                        dataPoints.stream().reduce(0.0, Double::sum) / dataPoints.size() // average
                );


                System.out.println(String.format("\nAverage accuracy: %.5f", dataPoints.stream().reduce(0.0, Double::sum) / dataPoints.size()));

                /* todo : if save data for all individual videos
                saveName = String.format("%s_%d.dat", savePrefix, textLines.indexOf(individualVideo) + 1);

                if (saveName == null) {
                    saveName = videoFile.toPath().getParent() + "/" + videoFile.getName().substring(0, videoFile.getName().length() - 4) + ".dat";
                }*/
            }
            anmlGroupAccuracies.add(videoEvals);

            if (WRITE_TO_FILE) {
//                for (List<Double> points : anmlGroupAccuracies) {
                IOUtils.writeDataToFile(videoEvals,
                        dataSaveName.split("\\.")[0] + anmlNumb.toString() + ".eval", "\n", true);
//                }
            }
        }

        /*if (WRITE_TO_FILE) {
            for (List<Double> points : anmlGroupAccuracies) {
                IOUtils.writeDataToFile(points, dataSaveName, "\n", true);
            }
        }*/

        anmlGroupAccuracies.forEach(lst ->
                System.out.println(String.format("Average accuracy on groups of %d: %.4f",
                        (Integer) anmlGroupNumbs.toArray()[anmlGroupAccuracies.indexOf(lst)],
                        lst.stream().reduce(0.0, Double::sum) / lst.size())
                )
        );
    }


    private List<List<String>> loadLabeledData(String fileName, int trueNumbAnmls) throws IOException {
        List<String> lines = IOUtils.readLinesFromFile(new File(fileName));
        List<List<String>> uniquePoints = new ArrayList<>(lines.size());

        // [211, 88],[257, 76],[279, 60],[421, 66],[0]
        for (String l : lines) {

            String[] split = l.split(",");

            List<String> points = new ArrayList<>(split.length);

            Set<String> temp = new LinkedHashSet<>(Arrays.asList(split));
            split = temp.toArray(new String[0]);

            for (int i=0; i<split.length; i++) {
                if (i<split.length-1 && split[i].contains("[") && split[i+1].contains("]")) {
                    points.add(split[i] + split[i+1]);
                } else if (split[i].contains("[") && split[i].contains("]")) {
                    points.add(split[i]);
                }
            }
            uniquePoints.add(points);
        }

        for (List<String> lst : uniquePoints) {
            System.out.println(lst.toString());
        }

        return uniquePoints;
    }

    public static void main(String[] args) throws IOException {

        TrackerAccuracyEvaluator evaluator = new TrackerAccuracyEvaluator();

        evaluator.loadLabeledData("/home/ah2166/Documents/sproj/java/" +
                "Tadpole-Tracker/data/labeledVideoPoints/4tads/IMG_5193_pts.dat", 4);
    }
}
