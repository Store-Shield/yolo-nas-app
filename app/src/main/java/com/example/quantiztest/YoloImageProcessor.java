package com.example.quantiztest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YoloImageProcessor {
    private static final String TAG = "YoloImageProcessor";
    private static final int INPUT_SIZE = 640; // YOLONas 모델의 입력 크기, 모델에 맞게 조정 필요
    private static final int NUM_DETECTIONS = 8400; // 모델 출력 형상에 맞게 수정 (8400개 탐지)
    private static final int NUM_CLASSES = 80; // COCO 데이터셋 클래스 수

    private Interpreter interpreter;
    private List<String> labels;
    private Context context;

    public YoloImageProcessor(Context context, Interpreter interpreter) {
        this.context = context;
        this.interpreter = interpreter;
        try {
            this.labels = loadLabels();
        } catch (IOException e) {
            Log.e(TAG, "라벨 파일을 로드하는 중 오류 발생: " + e.getMessage());
            this.labels = new ArrayList<>();
        }
    }

    /**
     * assets 폴더에서 labels.txt 파일을 로드합니다.
     */
    private List<String> loadLabels() throws IOException {
        List<String> labels = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("labels.txt")));
        String line;
        while ((line = reader.readLine()) != null) {
            labels.add(line);
        }
        reader.close();
        Log.d(TAG, "로드된 라벨 수: " + labels.size());
        return labels;
    }

    /**
     * 비트맵 이미지를 처리하고 객체 탐지를 수행합니다.
     * @param bitmap 처리할 이미지
     * @return 탐지된 객체 목록
     */
    public List<Detection> processImage(Bitmap bitmap) {
        // 텐서 정보 출력
        Log.d(TAG, "입력 텐서 수: " + interpreter.getInputTensorCount());
        Log.d(TAG, "출력 텐서 수: " + interpreter.getOutputTensorCount());

        for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
            Log.d(TAG, "출력 텐서 #" + i + " 타입: " + interpreter.getOutputTensor(i).dataType());
            Log.d(TAG, "출력 텐서 #" + i + " 형상: " + java.util.Arrays.toString(interpreter.getOutputTensor(i).shape()));
        }

        // 입력 이미지 준비
        Bitmap resizedBitmap = resizeBitmap(bitmap, INPUT_SIZE, INPUT_SIZE);

        // 양자화된 모델용 UINT8 입력 버퍼 준비 (ByteBuffer 사용)
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3);
        inputBuffer.order(ByteOrder.nativeOrder());

        // 이미지 데이터를 입력 버퍼에 복사
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resizedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int i = 0; i < INPUT_SIZE * INPUT_SIZE; ++i) {
            int pixel = pixels[i];
            // RGB 채널 추출 (양자화된 모델은 0-255 범위 사용)
            inputBuffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            inputBuffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            inputBuffer.put((byte) (pixel & 0xFF));         // B
        }

        inputBuffer.rewind(); // 버퍼 위치를 처음으로 되돌림

        // 출력 버퍼 준비 (UINT8 타입)
        // YOLONas 모델의 출력은 3개의 텐서입니다:
        // 1. 바운딩 박스 좌표 [1, 8400, 4]
        // 2. 신뢰도 점수 [1, 8400]
        // 3. 클래스 인덱스 [1, 8400]

        // 양자화된 모델이므로 UINT8로 출력을 준비하고, 나중에 dequantize 합니다
        byte[][][] outputBoxes = new byte[1][NUM_DETECTIONS][4];
        byte[][] outputScores = new byte[1][NUM_DETECTIONS];
        byte[][] outputClasses = new byte[1][NUM_DETECTIONS];

        // 모델 실행을 위한 입출력 매핑
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, outputBoxes);
        outputs.put(1, outputScores);
        outputs.put(2, outputClasses);

        // 모델 실행
        try {
            Log.d(TAG, "모델 입력 텐서 타입: " + interpreter.getInputTensor(0).dataType());
            Log.d(TAG, "모델 입력 텐서 형상: " + java.util.Arrays.toString(interpreter.getInputTensor(0).shape()));

            long startTime = System.currentTimeMillis();
            interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);
            long endTime = System.currentTimeMillis();

            Log.d("timecheck", "모델 추론 시간: " + (endTime - startTime) + "ms");

            // 출력 처리
            List<Detection> detections = new ArrayList<>();
            float confidenceThreshold = 0.4f;

            // 바운딩 박스 양자화 파라미터 (제대로 된 값으로 설정)
            float boxScale = interpreter.getOutputTensor(0).quantizationParams().getScale();
            int boxZeroPoint = interpreter.getOutputTensor(0).quantizationParams().getZeroPoint();

// 값이 0이거나 NaN이면 하드코딩된 값 사용
            if (boxScale == 0 || Float.isNaN(boxScale)) {
                boxScale = 0.01f;
                boxZeroPoint = 0;
            } else {
                Log.d(TAG, "실제 바운딩 박스 양자화 스케일 사용: " + boxScale);
            }

// 신뢰도 스케일
            float scoreScale = interpreter.getOutputTensor(1).quantizationParams().getScale();
            int scoreZeroPoint = interpreter.getOutputTensor(1).quantizationParams().getZeroPoint();
            if (scoreScale == 0 || Float.isNaN(scoreScale)) {
                scoreScale = 0.004f;
                scoreZeroPoint = 0;
            }

// 클래스 인덱스에 대한 양자화 파라미터
            float classScale = interpreter.getOutputTensor(2).quantizationParams().getScale();
            int classZeroPoint = interpreter.getOutputTensor(2).quantizationParams().getZeroPoint();
            if (classScale == 0 || Float.isNaN(classScale)) {
                classScale = 1.0f;
                classZeroPoint = 0;
            }
            // 모든 탐지 결과를 저장할 리스트
            List<Detection> allDetections = new ArrayList<>();

            // 각 탐지 결과 처리
            for (int i = 0; i < NUM_DETECTIONS; ++i) {
                // 신뢰도 점수 dequantize (UINT8 -> float)
                float confidence = ((outputScores[0][i] & 0xFF) - scoreZeroPoint) * scoreScale;
                 confidence = Math.min(confidence, 1.0f);

                // 신뢰도 임계값 이상인 결과만 처리
                if (confidence > confidenceThreshold) {
// 클래스 인덱스 dequantize
                    int classIndex = (int)(((outputClasses[0][i] & 0xFF) - classZeroPoint) * classScale);
                    if (classIndex >= 0 && classIndex < labels.size()) {
                        String label = labels.get(classIndex);
                        if(!label.equals("cup") && !label.equals("person") && !label.equals("laptop") ){ //********************여기서 상품등록하기!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                            continue;
                        }

                        // 바운딩 박스 좌표 dequantize (UINT8 -> float)
                        float x1 = ((outputBoxes[0][i][0] & 0xFF) - boxZeroPoint) * boxScale;
                        float y1 = ((outputBoxes[0][i][1] & 0xFF) - boxZeroPoint) * boxScale;
                        float x2 = ((outputBoxes[0][i][2] & 0xFF) - boxZeroPoint) * boxScale;
                        float y2 = ((outputBoxes[0][i][3] & 0xFF) - boxZeroPoint) * boxScale;
                        // 모델에서 반환한 원시 좌표값 로그 출력
                        Log.d(TAG, "원시 좌표 (모델 출력): x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2);


                        // 정규화 (0~1 범위로)
                        x1 = x1 / INPUT_SIZE;
                        y1 = y1 / INPUT_SIZE;
                        x2 = x2 / INPUT_SIZE;
                        y2 = y2 / INPUT_SIZE;

                        float left = x1 * bitmap.getWidth();
                        float top = y1 * bitmap.getHeight();
                        float right = x2 * bitmap.getWidth();
                        float bottom = y2 * bitmap.getHeight();
                        // 변환된 좌표값 로그 출력
                        Log.d(TAG, "변환된 좌표 (이미지 크기 적용): left=" + left + ", top=" + top + ", right=" + right + ", bottom=" + bottom);

                        // 좌표가 유효한지 확인
                        if (left < 0) left = 0;
                        if (top < 0) top = 0;

                        // 바운딩 박스 크기가 유효한지 확인
                        if (right > left && bottom > top) {
                            Detection detection = new Detection(label, confidence, left, top, right, bottom);
                            allDetections.add(detection);
                            Log.d(TAG, "탐지: " + label + ", 신뢰도: " + confidence + ", 좌표: " + left + "," + top + "," + right + "," + bottom);
                        }
                    }
                }
            }

            return  applyNMS(allDetections, 0.8f);

        } catch (Exception e) {
            Log.e(TAG, "모델 실행 중 오류 발생: " + e.getMessage(), e);
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    private List<Detection> applyNMS(List<Detection> detections, float iouThreshold) {
        // 신뢰도가 없으면 빈 리스트 반환
        if (detections.isEmpty()) {
            return new ArrayList<>();
        }

        // 신뢰도 기준으로 내림차순 정렬
        List<Detection> sortedDetections = new ArrayList<>(detections);
        Collections.sort(sortedDetections, (d1, d2) -> Float.compare(d2.getConfidence(), d1.getConfidence()));

        List<Detection> selectedDetections = new ArrayList<>();
        boolean[] isRemoved = new boolean[sortedDetections.size()];

        // 사람 클래스에 대해 더 높은 IoU 임계값 사용
        float personIouThreshold = 0.75f;  // 사람 클래스에 대한 더 높은 임계값

        for (int i = 0; i < sortedDetections.size(); i++) {
            if (isRemoved[i]) continue;

            Detection current = sortedDetections.get(i);
            selectedDetections.add(current);

            for (int j = i + 1; j < sortedDetections.size(); j++) {
                if (isRemoved[j]) continue;

                Detection next = sortedDetections.get(j);

                // 같은 클래스의 객체만 비교
                if (!current.getLabel().equals(next.getLabel())) {
                    continue;
                }

                // IoU 계산
                float iou = calculateIoU(current, next);

                // 현재 클래스에 맞는 임계값 선택
                float threshold = current.getLabel().equals("person") ? personIouThreshold : iouThreshold;

                // IoU가 임계값보다 크면 중복으로 간주하고 제거
                if (iou > threshold) {
                    isRemoved[j] = true;
                }
            }
        }

        Log.d(TAG, "NMS 적용 전 탐지 수: " + detections.size() + ", 적용 후: " + selectedDetections.size());
        return selectedDetections;
    }

    /**
     * 두 바운딩 박스 간의 IoU(Intersection over Union)를 계산합니다.
     */
    private float calculateIoU(Detection d1, Detection d2) {
        // 겹치는 영역 계산
        float xLeft = Math.max(d1.getLeft(), d2.getLeft());
        float yTop = Math.max(d1.getTop(), d2.getTop());
        float xRight = Math.min(d1.getRight(), d2.getRight());
        float yBottom = Math.min(d1.getBottom(), d2.getBottom());

        // 겹치는 영역이 없으면 0 반환
        if (xRight < xLeft || yBottom < yTop) return 0;

        float intersectionArea = (xRight - xLeft) * (yBottom - yTop);

        // 각 바운딩 박스의 면적 계산
        float d1Area = (d1.getRight() - d1.getLeft()) * (d1.getBottom() - d1.getTop());
        float d2Area = (d2.getRight() - d2.getLeft()) * (d2.getBottom() - d2.getTop());

        // IoU 계산
        return intersectionArea / (d1Area + d2Area - intersectionArea);
    }




    /**
     * 비트맵을 지정된 크기로 리사이즈합니다.
     */
    private Bitmap resizeBitmap(Bitmap bitmap, int width, int height) {
        float scaleWidth = ((float) width) / bitmap.getWidth();
        float scaleHeight = ((float) height) / bitmap.getHeight();
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
    }

    /**
     * 탐지 결과를 나타내는 클래스
     */
    public static class Detection {
        private final String label;
        private final float confidence;
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        public Detection(String label, float confidence, float left, float top, float right, float bottom) {
            this.label = label;
            this.confidence = confidence;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public String getLabel() {
            return label;
        }

        public float getConfidence() {
            return confidence;
        }

        public float getLeft() {
            return left;
        }

        public float getTop() {
            return top;
        }

        public float getRight() {
            return right;
        }

        public float getBottom() {
            return bottom;
        }

        @Override
        public String toString() {
            return label + " (" + String.format("%.2f", confidence * 100) + "%)";
        }
    }


}