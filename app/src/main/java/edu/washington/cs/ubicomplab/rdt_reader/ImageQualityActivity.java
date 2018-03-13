package edu.washington.cs.ubicomplab.rdt_reader;

import android.content.Intent;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.Menu;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static edu.washington.cs.ubicomplab.rdt_reader.Constants.*;

public class ImageQualityActivity extends AppCompatActivity implements CvCameraViewListener2{

    private RDTCamera2View mOpenCvCameraView;
    private TextView mImageQualityFeedbackView;

    private final String NO_MSG = "";
    private final String BLUR_MSG = "PLACE RDT IN THE BOX<br>TRY TO STAY STILL<br>";
    private final String GOOD_MSG = "LOOKS GOOD!<br>";
    private final String OVER_EXP_MSG = "TOO BRIGHT ";
    private final String UNDER_EXP_MSG = "TOO DARK ";
    private final String SHADOW_MSG = "SHADOW IS VISIBLE!!<br>";

    private final String QUALITY_MSG_FORMAT = "SHARPNESS: %s <br> " +
                                                "BRIGHTNESS: %s <br>" +
                                                "NO SHADOW: %s ";


    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    private enum State {
        INITIALIZATION, ENV_FOCUS_INFINITY, ENV_FOCUS_MACRO, ENV_FOCUS_AUTO_CENTER, QUALITY_CHECK
    }

    private State mCurrentState = State.INITIALIZATION;
    private boolean mResetCameraNeeded = true;

    private double minBlur = Double.MAX_VALUE;
    private double maxBlur = Double.MIN_VALUE;

    /*Activity callbacks*/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_quality);

        setTitle("Image Quality Checker");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mOpenCvCameraView = findViewById(R.id.img_quality_check_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        mImageQualityFeedbackView = findViewById(R.id.img_quality_feedback_view);

        //test purposes
        Timer uploadCheckerTimer = new Timer(true);
        uploadCheckerTimer.scheduleAtFixedRate(
                new TimerTask() {
                    public void run() { setNextState(mCurrentState); }
                }, 5*1000, 5 * 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    /*Activity callbacks*/
    @Override
    protected void onPause() {
        super.onPause();
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    /*OpenCV JavaCameraView callbacks*/

    @Override
    public void onCameraViewStarted(int width, int height) {

    }

    @Override
    public void onCameraViewStopped() {

    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        if (mResetCameraNeeded)
            setupCameraParameters(mCurrentState);

        switch (mCurrentState) {
            case INITIALIZATION:
                break;
            case ENV_FOCUS_INFINITY:
            case ENV_FOCUS_MACRO:
            case ENV_FOCUS_AUTO_CENTER:
                final double currVal = calculateBurriness(inputFrame.rgba());

                if (currVal < minBlur)
                    minBlur = currVal;

                if (currVal > maxBlur)
                    maxBlur = currVal* BLUR_THRESHOLD;

                break;
            case QUALITY_CHECK:
                //result = drawContourUsingSobel(inputFrame.rgba());
                double blurVal = calculateBurriness(inputFrame.rgba());
                final boolean isBlur = blurVal < maxBlur;

                float[] histogram = calculateHistogram(inputFrame.gray());

                int maxWhite = 0;

                for (int i = 0; i < histogram.length; i++) {
                    if (histogram[i] > 0) {
                        maxWhite = i;
                    }
                }

                final boolean isOverExposed = maxWhite >= OVER_EXP_THRESHOLD;
                final boolean isUnderExposed = maxWhite < UNDER_EXP_THRESHOLD;


                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        displayQualityResult(isBlur, isOverExposed, isUnderExposed, false);
                    }
                });
                break;
        }

        //setNextState(mCurrentState);

        return inputFrame.rgba();
    }

    /*Private methods*/
    private void displayQualityResult (boolean isBlur, boolean isOverExposed, boolean isUnderExposed, boolean isShadow) {
        String message = String.format(QUALITY_MSG_FORMAT, !isBlur ? OK : NOT_OK,
                !isOverExposed && !isUnderExposed ? OK : (isOverExposed ? OVER_EXP_MSG + NOT_OK : UNDER_EXP_MSG + NOT_OK),
                !isShadow ? OK : NOT_OK);

        mImageQualityFeedbackView.setText(Html.fromHtml(message));
        if (!isBlur && !isOverExposed && !isUnderExposed && !isShadow)
            mImageQualityFeedbackView.setBackgroundColor(getResources().getColor(R.color.green_overlay));
        else
            mImageQualityFeedbackView.setBackgroundColor(getResources().getColor(R.color.red_overlay));
    }

    private void setNextState (State currentState) {
        switch (currentState) {
            case INITIALIZATION:
                mCurrentState = State.ENV_FOCUS_INFINITY;
                mResetCameraNeeded = true;
                break;
            case ENV_FOCUS_INFINITY:
                mCurrentState = State.ENV_FOCUS_MACRO;
                mResetCameraNeeded = true;
                break;
            case ENV_FOCUS_MACRO:
                mCurrentState = State.ENV_FOCUS_AUTO_CENTER;
                mResetCameraNeeded = true;
                break;
            case ENV_FOCUS_AUTO_CENTER:
                mCurrentState = State.QUALITY_CHECK;
                mResetCameraNeeded = true;
                break;
            case QUALITY_CHECK:
                mCurrentState = State.QUALITY_CHECK;
                mResetCameraNeeded = false;
                break;
        }
    }

    private void setupCameraParameters (State currentState) {
        try {
            CameraCharacteristics characteristics = mOpenCvCameraView.mCameraManager.getCameraCharacteristics(mOpenCvCameraView.mCameraID);

            switch (currentState) {
                case INITIALIZATION:
                case ENV_FOCUS_AUTO_CENTER:
                case QUALITY_CHECK:
                    final android.graphics.Rect sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                            new MeteringRectangle[]{new MeteringRectangle(sensor.width() / 2 - 50, sensor.height() / 2 - 50, 100, 100,
                                    MeteringRectangle.METERING_WEIGHT_MAX - 1)});
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                            new MeteringRectangle[]{new MeteringRectangle(sensor.width() / 2 - 50, sensor.height() / 2 - 50, 100, 100,
                                    MeteringRectangle.METERING_WEIGHT_MAX - 1)});
                    mOpenCvCameraView.mPreviewRequestBuilder.setTag("CENTER_AF_AE_TAG");
                    break;
                case ENV_FOCUS_INFINITY:
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
                    break;
                case ENV_FOCUS_MACRO:
                    float macroDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    //mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO);
                    mOpenCvCameraView.mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, macroDistance);
                    break;
            }
            mOpenCvCameraView.mCaptureSession.setRepeatingRequest(mOpenCvCameraView.mPreviewRequestBuilder.build(), null, null);
        } catch (Exception e) {

        }
    }

    private float[] calculateHistogram (Mat gray) {
        int mHistSizeNum =256;
        MatOfInt mHistSize = new MatOfInt(mHistSizeNum);
        Mat hist = new Mat();
        final float []mBuff = new float[mHistSizeNum];
        MatOfFloat histogramRanges = new MatOfFloat(0f, 256f);
        MatOfInt mChannels[] = new MatOfInt[] { new MatOfInt(0)};
        Size sizeRgba = gray.size();

        // GRAY
        for(int c=0; c<1; c++) {
            Imgproc.calcHist(Arrays.asList(gray), mChannels[c], new Mat(), hist,
                    mHistSize, histogramRanges);
            Core.normalize(hist, hist, sizeRgba.height/2, 0, Core.NORM_INF);
            hist.get(0, 0, mBuff);
        }

        hist.release();
        return mBuff;
    }

    private double calculateBurriness (Mat input) {
        Mat des = new Mat();
        Imgproc.Laplacian(input, des, CvType.CV_64F);

        MatOfDouble median = new MatOfDouble();
        MatOfDouble std= new MatOfDouble();

        Core.meanStdDev(des, median , std);

        double maxLap = Double.MIN_VALUE;

        for(int i = 0; i < std.cols(); i++) {
            for (int j = 0; j < std.rows(); j++) {
                if (maxLap < std.get(j, i)[0]) {
                    maxLap = std.get(j, i)[0];
                }
            }
        }

        double blurriness = Math.pow(maxLap,2);

        Log.d(TAG, String.format("Blurriness for state %s: %.5f", mCurrentState.toString(), blurriness));

        des.release();

        return blurriness;
    }

    private Mat drawContourUsingSobel(Mat input) {
        Mat sobelx = new Mat();
        Mat sobely = new Mat();
        Mat output = new Mat();
        Mat sharp = new Mat();

        //Imgproc.GaussianBlur(input, output, new Size(21, 21), 8);
        Imgproc.GaussianBlur(input, output, new Size(21, 21), 3);
        Imgproc.cvtColor(output, output, Imgproc.COLOR_RGB2GRAY);

        Imgproc.Sobel(output, sobelx, CvType.CV_32F, 0, 1); //ksize=5
        Imgproc.Sobel(output, sobely, CvType.CV_32F, 1, 0); //ksize=5

        Core.pow(sobelx, 2, sobelx);
        Core.pow(sobely, 2, sobely);

        Core.add(sobelx, sobely, output);

        output.convertTo(output, CvType.CV_32F);

        Core.pow(output, 0.5, output);
        Core.multiply(output, new Scalar(Math.pow(2, 0.5)),output);

        output.convertTo(output, CvType.CV_8UC1);

        Imgproc.GaussianBlur(output, sharp, new Size(0, 0), 3);
        Core.addWeighted(output, 1.5, sharp, -0.5, 0, output);
        Core.bitwise_not(output, output);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(output, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        Log.d(TAG, "contours: " + contours.size());

        //output.convertTo(output, CV_32F);

        //for(int idx = 0; idx >= 0; idx = (int) hierarchy.get(0, idx)[0]) {
        for( int idx = 0; idx < contours.size(); idx++ ) {
            MatOfPoint matOfPoint = contours.get(idx);
            Rect rect = Imgproc.boundingRect(matOfPoint);
            if(rect.size().width > 100 && rect.size().height > 100)
                Imgproc.rectangle(input, rect.tl(), rect.br(), new Scalar(255, 255, 255));
        }

        return input;

    }
}