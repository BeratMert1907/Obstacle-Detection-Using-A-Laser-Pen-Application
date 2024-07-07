package com.example.imagepro;

import imagepro.R;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Rect;
import java.util.ArrayList;
import java.util.List;

public class CameraActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final String TAG = "CameraActivity";
    private CameraBridgeViewBase mOpenCvCameraView;
    private ToneGenerator toneGenerator;
    private static final double YOUR_DISTANCE_THRESHOLD = 100;
    private static final double MIN_CONTOUR_AREA = 10;
    private static final double MAX_CONTOUR_AREA = 90;
    private static final double POTHOLE_DISTANCE_DROP_THRESHOLD = 90; // Threshold for pothole detection
    private static final int POTHOLE_DETECTION_WINDOW = 5; // Number of frames to consider for pothole detection
    private List<Double> distanceHistory = new ArrayList<>(); // History of distances to detect potholes
    private List<Double> distanceAveragingWindow = new ArrayList<>(); // History for averaging
    private static final int AVERAGING_WINDOW_SIZE = 30; // Number of frames to average distance over

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        mOpenCvCameraView = findViewById(R.id.camera_bridge_view);
        mOpenCvCameraView.setVisibility(CameraBridgeViewBase.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        // Initialize ToneGenerator for sound
        toneGenerator = new ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV initialization failed");
        } else {
            Log.d(TAG, "OpenCV initialization succeeded");
        }
        mOpenCvCameraView.enableView();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat rgba = inputFrame.rgba();

        // Middle of the frame (ROI - Region of Interest)
        int centerX = rgba.cols() / 2;
        int centerY = rgba.rows() / 2;
        int roiWidth = 280;
        int roiHeight = 80;
        Rect roi = new Rect(centerX - roiWidth / 2, centerY - roiHeight / 2, roiWidth, roiHeight);

        // Apply ROI
        Mat roiFrame = new Mat(rgba, roi);

        // Convert ROI to HSV color space
        Mat hsvImage = new Mat();
        Imgproc.cvtColor(roiFrame, hsvImage, Imgproc.COLOR_RGB2HSV);

        // Define the lower and upper bounds of red color in HSV
        Scalar lowerBound = new Scalar(160, 60, 100); // Lower bound for red (H, S, V)
        Scalar upperBound = new Scalar(173, 255, 255); // Upper bound for red (H, S, V)

        // Threshold the HSV image to get only red regions
        Mat mask = new Mat();
        Core.inRange(hsvImage, lowerBound, upperBound, mask);

        // Find contours
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        // Loop over contours
        for (MatOfPoint contour : contours) {
            // Find enclosing circle
            Point center = new Point();
            float[] radius = new float[1];
            Imgproc.minEnclosingCircle(new MatOfPoint2f(contour.toArray()), center, radius);

            // Calculate contour area
            double contourArea = Imgproc.contourArea(contour);

            // Check if contour area is within the specified range
            if (contourArea < MIN_CONTOUR_AREA || contourArea > MAX_CONTOUR_AREA) {
                continue; // Skip if contour area is outside the specified range
            }

            // Calculate distance
            double distance = calculateDistance(radius[0]);

            // Add distance to averaging window
            distanceAveragingWindow.add(distance);
            if (distanceAveragingWindow.size() > AVERAGING_WINDOW_SIZE) {
                distanceAveragingWindow.remove(0); // Remove oldest distance
            }

            // Calculate average distance
            double averageDistance = 0;
            for (double d : distanceAveragingWindow) {
                averageDistance += d;
            }
            averageDistance /= distanceAveragingWindow.size();

            // Draw circle
            Imgproc.circle(rgba, new Point(center.x + roi.x, center.y + roi.y), (int) radius[0], new Scalar(0,255,0), 3);

            // Draw distance text
            String distanceText = "Distance: " + (int) averageDistance + " cm";
            Point textOrg = new Point(centerX - 100, centerY - 75);

            Mat rotatedText = new Mat();
            Core.rotate(rgba, rotatedText, Core.ROTATE_90_CLOCKWISE);
            Imgproc.putText(rotatedText, distanceText, textOrg, Core.FONT_HERSHEY_SIMPLEX, 1, new Scalar(255, 255, 255), 2);
            Core.rotate(rotatedText, rgba, Core.ROTATE_90_COUNTERCLOCKWISE);

            // Add distance to history for pothole detection
            distanceHistory.add(distance);
            if (distanceHistory.size() > POTHOLE_DETECTION_WINDOW) {
                distanceHistory.remove(0); // Remove oldest distance
            }

            // Check for rapid distance drop to detect potholes
            if (distanceHistory.size() == POTHOLE_DETECTION_WINDOW) {
                double maxDistance = distanceHistory.get(0);
                double minDistance = distanceHistory.get(0);
                for (double d : distanceHistory) {
                    if (d > maxDistance) {
                        maxDistance = d;
                    }
                    if (d < minDistance) {
                        minDistance = d;
                    }
                }
                if (maxDistance - minDistance > POTHOLE_DISTANCE_DROP_THRESHOLD) {
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_SS, 300); // Play alarm sound for 300 milliseconds
                }
            }

            // Check if distance is below threshold and play sound
            if (averageDistance < YOUR_DISTANCE_THRESHOLD) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300); // Play alarm sound for 300 milliseconds
            }
        }

        return rgba;
    }

    private double calculateDistance(float radius) {
        // Calculate distance based on focal length and radius of laser
        final double FOCAL_LENGTH = 520.869; // Focal length in pixels obtained from Calibration class
        final double RADIUS_OF_LASER = 1.0; // Radius of laser in cm
        return (FOCAL_LENGTH * RADIUS_OF_LASER) / radius;
    }
}

