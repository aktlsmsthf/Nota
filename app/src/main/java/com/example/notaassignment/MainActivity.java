package com.example.notaassignment;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;


import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.notaassignment.ml.ClassificationModel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private String[] labelList;
    private Camera camera;
    private CameraPreview preview;

    private FrameLayout frameLayout;
    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //카메라 권한 체크
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA
            }, 0);
        }

        labelList = getLabelList();

        frameLayout = (FrameLayout) findViewById(R.id.preview);
        textView = (TextView) findViewById(R.id.resultText);

        camera = getCameraInstance();
        preview = new CameraPreview(this, camera);
        frameLayout.addView(preview);

        // 프리뷰 화면 클릭하여 포커스
        preview.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                camera.autoFocus(new Camera.AutoFocusCallback(){
                    public void onAutoFocus(boolean success, Camera camera){

                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch(requestCode){
            case 0:
                if(grantResults.length > 0 && grantResults[0]== PackageManager.PERMISSION_GRANTED ){

                }
        }
    }

    // Camera 객체 설
    public Camera getCameraInstance(){
        Camera c = null;
        try{
            c = Camera.open();

            //세로 화면으로 보이도록 화면 회
            c.setDisplayOrientation(90);

            //프리뷰로 프레임 별 화면 데이터를 받을 때 마다 물건 인식
            c.setPreviewCallback(new Camera.PreviewCallback(){

                public void onPreviewFrame(byte[] data, Camera camera){
                    try{
                        //이미지의 너비와 높이 결정
                        int w = camera.getParameters().getPreviewSize().width;
                        int h = camera.getParameters().getPreviewSize().height;

                        YuvImage yuvImage = new YuvImage(data, camera.getParameters().getPreviewFormat(), w, h, null);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        yuvImage.compressToJpeg(new Rect(0, 0, w, h), 100, baos);
                        byte [] imageData = baos.toByteArray();

                        int orientation = setCameraDisplayOrientation(MainActivity.this, Camera.CameraInfo.CAMERA_FACING_BACK, camera);

                        //byte array를 bitmap으로 변환
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        Bitmap bitmap = BitmapFactory.decodeByteArray( imageData, 0, imageData.length);

                        //이미지를 디바이스 방향으로 회전
                        Matrix matrix = new Matrix();
                        matrix.postRotate(orientation);
                        final Bitmap rotatedBitmap =  Bitmap.createBitmap(bitmap, 0, 0, Math.min(w, h), Math.min(w, h), matrix, true);

                        //인식도가 높은 라벨을 찾아 textView에 표
                        String label = getLabel(bitmap);
                        textView.setText(label);
                    }catch(Exception e){

                    }

                }

            });
        }catch(Exception e){
            e.printStackTrace();
        }

        return c;
    }

    //회전되어 들어오는 카메라 화면 데이터가 얼마나 회전되었는지 리턴
    public int setCameraDisplayOrientation(Activity activity,
                                                  int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }

        return result;
    }

    //전체 라벨 리스트 파싱
    private String[] getLabelList(){
        String[] result = null;
        try{
            InputStream is = getAssets().open("classification_model_label.txt");

            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();

            String labels = new String(buffer);
            result = labels.split("\n");
        }catch(Exception e){
            e.printStackTrace();
        }

        return result;
    }

    // 인식도가 가장 높은 라벨 리턴
    public String getLabel(Bitmap bitmap){
        String label = "";

        int cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());

        ImageProcessor imageProcessor =
                new ImageProcessor.Builder()
                        .add(new ResizeWithCropOrPadOp(cropSize, cropSize))
                        .add(new ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                        .build();

        TensorImage tImage = new TensorImage(DataType.UINT8);

        tImage.load(bitmap);
        tImage = imageProcessor.process(tImage);

        try {
            ClassificationModel model = ClassificationModel.newInstance(this);

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.UINT8);
            inputFeature0.loadBuffer(tImage.getBuffer());

            // Runs model inference and gets result.
            ClassificationModel.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            // Releases model resources if no longer used.
            model.close();

            float[] result = outputFeature0.getFloatArray();
            String r = "";
            int max = 0;
            for(int i=1; i<result.length; i++){
                if(result[i]>result[max]){
                    max = i;
                }
            }

            label = labelList[max] + " " + result[max];
        } catch (IOException e) {
            // TODO Handle the exception
        }

        return label;
    }
}