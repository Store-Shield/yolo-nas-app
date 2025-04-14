package com.example.quantiztest;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class TFLiteLoader {
    private static final String TAG = "TFLiteLoader";
    private  String modelName;

    private Interpreter tflite;
    private MappedByteBuffer tfliteModel;
    private Context context;

    public TFLiteLoader(Context context,String modelName) {

        this.context = context;
        this.modelName=modelName;
    }

    /**
     * Assets 폴더에서 TFLite 모델을 로드합니다.
     */
    public boolean loadModelFromAssets() {
        Log.d(TAG, "Loading model from assets...");
        try {
            tfliteModel = loadModelFile(context, modelName);

            // 인터프리터 옵션 설정
            Interpreter.Options options = new Interpreter.Options();

            // CPU 스레드 수 최적화
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int optimalThreads = Math.min(4, availableProcessors);
            options.setNumThreads(optimalThreads);
            Log.d(TAG, "Using " + optimalThreads + " threads for " + modelName);

            // 성능 옵션
            options.setAllowFp16PrecisionForFp32(true);

            // CPU 기반 NNAPI 활성화 (API 27+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                options.setUseNNAPI(true);
                Log.d(TAG, "NNAPI enabled for " + modelName);
            }

            tflite = new Interpreter(tfliteModel, options);

            // 모델 텐서 정보 출력
            Log.d(TAG, "Model loaded successfully: " + modelName);
            Log.d(TAG, "Model input tensor count: " + tflite.getInputTensorCount());
            Log.d(TAG, "Model output tensor count: " + tflite.getOutputTensorCount());

            // 모든 입력 텐서 정보 출력
            for (int i = 0; i < tflite.getInputTensorCount(); i++) {
                Log.d(TAG, "Input tensor #" + i + " type: " + tflite.getInputTensor(i).dataType());
                Log.d(TAG, "Input tensor #" + i + " shape: " + java.util.Arrays.toString(tflite.getInputTensor(i).shape()));
            }

            // 모든 출력 텐서 정보 출력
            for (int i = 0; i < tflite.getOutputTensorCount(); i++) {
                Log.d(TAG, "Output tensor #" + i + " type: " + tflite.getOutputTensor(i).dataType());
                Log.d(TAG, "Output tensor #" + i + " shape: " + java.util.Arrays.toString(tflite.getOutputTensor(i).shape()));
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error loading model from assets: " + e.getMessage(), e);
            return false;
        }
    }
    /**
     * Assets에서 모델 파일을 읽어 MappedByteBuffer로 변환합니다.
     */
    private MappedByteBuffer loadModelFile(Context context, String modelFile) throws IOException {
        AssetManager assetManager = context.getAssets();
        InputStream is = assetManager.open(modelFile);

        // 임시 파일 생성
        File tempFile = File.createTempFile("tflite", null, context.getCacheDir());

        // 임시 파일에 assets 내용 복사
        copyInputStreamToFile(is, tempFile);

        // 임시 파일에서 MappedByteBuffer 생성
        FileInputStream input = new FileInputStream(tempFile);
        FileChannel fileChannel = input.getChannel();
        MappedByteBuffer result = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, tempFile.length());

        // 리소스 정리
        fileChannel.close();
        input.close();
        is.close();

        Log.d(TAG, "Model file loaded: " + modelFile + " Size: " + tempFile.length() + " bytes");
        return result;
    }

    /**
     * InputStream의 내용을 File로 복사합니다.
     */
    private void copyInputStreamToFile(InputStream in, File file) throws IOException {
        OutputStream out = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        out.close();
    }

    /**
     * TFLite 인터프리터를 반환합니다.
     */
    public Interpreter getTfliteInterpreter() {
        return tflite;
    }

    /**
     * 리소스를 해제합니다.
     */
    public void close() {
        if (tflite != null) {
            Log.d(TAG, "Closing TFLite interpreter");
            tflite.close();
            tflite = null;
        }
    }
}