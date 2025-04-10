package com.example.quantiztest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FaceDetector {
    private static final String TAG = "FaceDetector";
    private static final int INPUT_SIZE = 320; // 얼굴 검출 모델 입력 크기 (필요에 따라 조정)
    private static final int NUM_DETECTIONS = 100; // 최대 검출 수

    private Interpreter interpreter;
    private Context context;

    public FaceDetector(Context context, Interpreter interpreter) {
        this.context = context;
        this.interpreter = interpreter;
    }

    /**
     * 비트맵 이미지를 처리하고 얼굴 탐지를 수행합니다.
     * @param bitmap 처리할 이미지
     * @return 탐지된 얼굴 목록
     */

    public List<Face> detectFaces(Bitmap bitmap) {
        // 텐서 정보 출력
        Log.d(TAG, "입력 텐서 수: " + interpreter.getInputTensorCount());
        Log.d(TAG, "출력 텐서 수: " + interpreter.getOutputTensorCount());

        for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
            Log.d(TAG, "출력 텐서 #" + i + " 타입: " + interpreter.getOutputTensor(i).dataType());
            Log.d(TAG, "출력 텐서 #" + i + " 형상: " + java.util.Arrays.toString(interpreter.getOutputTensor(i).shape()));
        }

        // 입력 이미지 준비
        Bitmap resizedBitmap = resizeBitmap(bitmap, 640, 480);

        // 양자화된 모델용 UINT8 입력 버퍼 준비
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * 480 * 640);
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[480 * 640];
        resizedBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 480);

        for (int i = 0; i < 480 * 640; ++i) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int gray = (r + g + b) / 3;
            inputBuffer.put((byte) gray);
        }
        inputBuffer.rewind(); // 버퍼 위치를 처음으로 되돌림

        try {
            Log.d(TAG, "모델 입력 텐서 타입: " + interpreter.getInputTensor(0).dataType());
            Log.d(TAG, "모델 입력 텐서 형상: " + java.util.Arrays.toString(interpreter.getInputTensor(0).shape()));

            // 모델의 실제 출력 형식에 맞게 출력 버퍼 준비 - 마지막 차원 추가
            byte[][][][] outputHeatmap = new byte[1][60][80][1];  // 출력 텐서 #0: [1, 60, 80, 1]
            byte[][][][] outputBoxes = new byte[1][60][80][4];    // 출력 텐서 #1: [1, 60, 80, 4]
            byte[][][][] outputLandmarks = new byte[1][60][80][10]; // 출력 텐서 #2: [1, 60, 80, 10]

            // 모델 실행을 위한 입출력 매핑
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, outputHeatmap);
            outputs.put(1, outputBoxes);
            outputs.put(2, outputLandmarks);

            long startTime = System.currentTimeMillis();
            interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);
            long endTime = System.currentTimeMillis();
            Log.d(TAG, "얼굴 탐지 추론 시간: " + (endTime - startTime) + "ms");

            // 히트맵 및 박스 양자화 파라미터
            float heatmapScale = interpreter.getOutputTensor(0).quantizationParams().getScale();
            int heatmapZeroPoint = interpreter.getOutputTensor(0).quantizationParams().getZeroPoint();
            float boxScale = interpreter.getOutputTensor(1).quantizationParams().getScale();
            int boxZeroPoint = interpreter.getOutputTensor(1).quantizationParams().getZeroPoint();
            Log.d(TAG, "heatmapScale : "+ heatmapScale);
            Log.d(TAG, "heatmapZeroPoint : "+ heatmapZeroPoint);
            Log.d(TAG, "boxScale : "+ boxScale);
            Log.d(TAG, "boxZeroPoint : "+ boxZeroPoint);


            if (heatmapScale == 0 || Float.isNaN(heatmapScale)) {
                heatmapScale = 0.01f;
                heatmapZeroPoint = 0;
            }

            if (boxScale == 0 || Float.isNaN(boxScale)) {
                boxScale = 0.01f;
                boxZeroPoint = 0;
            }

            Log.d(TAG, "heatmapScale : "+ heatmapScale);
            Log.d(TAG, "heatmapZeroPoint : "+ heatmapZeroPoint);
            Log.d(TAG, "boxScale : "+ boxScale);
            Log.d(TAG, "boxZeroPoint : "+ boxZeroPoint);


            // 얼굴 탐지 결과를 저장할 리스트
            List<Face> allFaces = new ArrayList<>();
            float confidenceThreshold = 0.1f;

            // 히트맵 기반 얼굴 검출 부분 수정
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    // 히트맵 원시 값
                      //
                    int rawValue = outputHeatmap[0][y][x][0] & 0xFF;
                    float score = (rawValue - 192) * 0.039235096f;
                    float normalizedScore = (score + 7.530337f) / 10.00052f;


//                    float score = (rawValue - heatmapZeroPoint) * heatmapScale;

                    // Log.d(TAG, "rawValue : "+ rawValue + " -> score : "+score + "normalizedScore : "+normalizedScore);
                    // 신뢰도 계산 - 원시 값이 heatmapZeroPoint보다 큰 경우만 고려
                    if (normalizedScore > 0.8) {
                        // 박스 오프셋 구하기
                        float offsetX = ((outputBoxes[0][y][x][0] & 0xFF) - boxZeroPoint) * boxScale;
                        float offsetY = ((outputBoxes[0][y][x][1] & 0xFF) - boxZeroPoint) * boxScale;
                        float offsetW = ((outputBoxes[0][y][x][2] & 0xFF) - boxZeroPoint) * boxScale;
                        float offsetH = ((outputBoxes[0][y][x][3] & 0xFF) - boxZeroPoint) * boxScale;

                        // 중심점 계산 (그리드 위치 + 오프셋)
                        float centerX = (x + offsetX) / 80.0f;  // 그리드 위치를 0-1 범위로 정규화
                        float centerY = (y + offsetY) / 60.0f;

                        // 너비와 높이 계산
                        float width = offsetW / 80.0f;  // 너비도 0-1 범위로 정규화
                        float height = offsetH / 60.0f;

                        // 박스 좌표 계산
                        float left = Math.max(0, centerX - width/2) * bitmap.getWidth();
                        float top = Math.max(0, centerY - height/2) * bitmap.getHeight();
                        float right = Math.min(1, centerX + width/2) * bitmap.getWidth();
                        float bottom = Math.min(1, centerY + height/2) * bitmap.getHeight();

                        // 박스가 유효한지 확인
                        if (right > left && bottom > top) {
                            Face face = new Face(normalizedScore, left, top, right, bottom);
                            allFaces.add(face);
                            Log.d(TAG, "얼굴 탐지: 신뢰도=" + normalizedScore + ", 좌표=" + left + "," + top + "," + right + "," + bottom);
                        }
                    }
                }
            }



            // 중복 제거
            return applyNMS(allFaces, 0.2f);

        } catch (Exception e) {
            Log.e(TAG, "모델 실행 중 오류 발생: " + e.getMessage(), e);
            e.printStackTrace();
            return new ArrayList<>();
        } finally {
            // 리사이즈된 비트맵 메모리 해제
            if (resizedBitmap != null && resizedBitmap != bitmap) {
                resizedBitmap.recycle();
            }
        }
    }



    /**
     * 중복 탐지 제거를 위한 NMS 적용
     */
    private List<Face> applyNMS(List<Face> faces, float iouThreshold) {
        if (faces.isEmpty()) {
            return new ArrayList<>();
        }

        // 신뢰도 기준으로 내림차순 정렬
        List<Face> sortedFaces = new ArrayList<>(faces);
        Collections.sort(sortedFaces, (f1, f2) -> Float.compare(f2.getConfidence(), f1.getConfidence()));

        List<Face> selectedFaces = new ArrayList<>();
        boolean[] isRemoved = new boolean[sortedFaces.size()];

        for (int i = 0; i < sortedFaces.size(); i++) {
            if (isRemoved[i]) continue;

            Face current = sortedFaces.get(i);
            selectedFaces.add(current);

            for (int j = i + 1; j < sortedFaces.size(); j++) {
                if (isRemoved[j]) continue;

                Face next = sortedFaces.get(j);

                // IoU 계산
                float iou = calculateIoU(current, next);

                // IoU가 임계값보다 크면 중복으로 간주하고 제거
                if (iou > iouThreshold) {
                    isRemoved[j] = true;
                }
            }
        }

        Log.d(TAG, "NMS 적용 전 얼굴 수: " + faces.size() + ", 적용 후: " + selectedFaces.size());
        return selectedFaces;
    }

    /**
     * 두 바운딩 박스 간의 IoU(Intersection over Union)를 계산합니다.
     */
    private float calculateIoU(Face f1, Face f2) {
        // 겹치는 영역 계산
        float xLeft = Math.max(f1.getLeft(), f2.getLeft());
        float yTop = Math.max(f1.getTop(), f2.getTop());
        float xRight = Math.min(f1.getRight(), f2.getRight());
        float yBottom = Math.min(f1.getBottom(), f2.getBottom());

        // 겹치는 영역이 없으면 0 반환
        if (xRight < xLeft || yBottom < yTop) return 0;

        float intersectionArea = (xRight - xLeft) * (yBottom - yTop);

        // 각 바운딩 박스의 면적 계산
        float f1Area = (f1.getRight() - f1.getLeft()) * (f1.getBottom() - f1.getTop());
        float f2Area = (f2.getRight() - f2.getLeft()) * (f2.getBottom() - f2.getTop());

        // IoU 계산
        return intersectionArea / (f1Area + f2Area - intersectionArea);
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
     * 얼굴 탐지 결과를 나타내는 클래스
     */
    public static class Face {
        private final float confidence;
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        public Face(float confidence, float left, float top, float right, float bottom) {
            this.confidence = confidence;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
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
            return "Face (" + String.format("%.2f", confidence * 100) + "%)";
        }

        @Override
        public int hashCode() {
            // 고유한 ID 생성을 위한 해시코드
            int result = Float.floatToIntBits(confidence);
            result = 31 * result + Float.floatToIntBits(left);
            result = 31 * result + Float.floatToIntBits(top);
            result = 31 * result + Float.floatToIntBits(right);
            result = 31 * result + Float.floatToIntBits(bottom);
            return result;
        }
    }
}