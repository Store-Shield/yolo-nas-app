package com.example.quantiztest;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SimpleTracker {
    private static final String TAG = "SimpleTracker";
    private static final float IOU_THRESHOLD = 0.25f;  // 같은 객체로 간주할 IoU 임계값 낮출수록 잘 추정
    private static final int MAX_AGE = 10;  // 객체가 사라졌다고 판단하기 전 최대 프레임 수 즉 높을수록 일시적으로 가려져도 유지

    private static final boolean USE_VELOCITY_PREDICTION = true;
    private static final float VELOCITY_WEIGHT = 0.7f;
    // 추적 중인 객체 목록
    private final Map<Integer, TrackedObject> trackedObjects = new HashMap<>();
    private int nextId = 0;

    /**
     * 현재 프레임에서 탐지된 객체를 이전 프레임의 추적 객체와 연결
     * @param detections 현재 프레임에서 탐지된 객체 목록
     * @return 추적 ID가 할당된 객체 목록
     */
    public List<TrackedObject> update(List<YoloImageProcessor.Detection> detections) {
        // 빈 탐지 목록이면 모든 추적 객체의 나이를 증가시키고 반환
        if (detections == null || detections.isEmpty()) {
            increaseAge();
            removeOldObjects();
            return new ArrayList<>(trackedObjects.values());
        }

        // 현재 프레임에서 탐지된 객체에 일치하는 추적 객체 찾기
        boolean[] matched = new boolean[detections.size()];

        // 1단계: 높은 IoU 기준으로 확실한 매칭부터 처리
        float highIoUThreshold = 0.5f;  // 높은 확신을 가진 매칭을 위한 임계값
        matchDetectionsWithHighIoU(detections, matched, highIoUThreshold);


        // 2단계: 남은 객체들에 대해 더 낮은 IoU와 추가 특성 기반 매칭
        matchRemainingDetections(detections, matched);

        // 모든 기존 추적 객체에 대해 반복
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            boolean matchFound = false;
            int bestMatchIdx = -1;
            float bestIoU = IOU_THRESHOLD;

            // 현재 탐지된 모든 객체와 비교
            for (int i = 0; i < detections.size(); i++) {
                if (matched[i]) continue;  // 이미 매칭된 객체 건너뛰기

                YoloImageProcessor.Detection detection = detections.get(i);

                // 라벨이 같은 객체만 비교
                if (!trackedObj.getLabel().equals(detection.getLabel())) continue;

                // IoU 계산
                float iou = calculateIoU(trackedObj, detection);

                // 최고 IoU 업데이트
                if (iou > bestIoU) {
                    bestIoU = iou;
                    bestMatchIdx = i;
                    matchFound = true;
                }
            }

            // 일치하는 객체가 있으면 추적 객체 업데이트
            if (matchFound && bestMatchIdx >= 0) {
                YoloImageProcessor.Detection matchedDetection = detections.get(bestMatchIdx);
                trackedObj.update(matchedDetection);
                matched[bestMatchIdx] = true;
            } else {
                // 일치하는 객체가 없으면 나이 증가
                trackedObj.incrementAge();
            }
        }

        // 매칭되지 않은 새 객체 추가
        for (int i = 0; i < detections.size(); i++) {
            if (!matched[i]) {
                YoloImageProcessor.Detection detection = detections.get(i);
                TrackedObject newTrackedObj = new TrackedObject(
                        nextId++,
                        detection.getLabel(),
                        detection.getConfidence(),
                        detection.getLeft(),
                        detection.getTop(),
                        detection.getRight(),
                        detection.getBottom()
                );
                trackedObjects.put(newTrackedObj.getId(), newTrackedObj);
            }
        }

        // 오래된 객체 제거
        removeOldObjects();

        // 현재 추적 중인 객체 목록 반환
        return new ArrayList<>(trackedObjects.values());
    }

    // 남은 객체들에 대해 더 낮은 IoU와 추가 특성 매칭
    private void matchRemainingDetections(List<YoloImageProcessor.Detection> detections, boolean[] matched) {
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            // 이미 매칭된 객체는 건너뛰기
            if (trackedObj.getLastMatchedTime() == 0) continue;

            int bestMatchIdx = -1;
            float bestScore = IOU_THRESHOLD;

            for (int i = 0; i < detections.size(); i++) {
                if (matched[i]) continue;

                YoloImageProcessor.Detection detection = detections.get(i);
                if (!trackedObj.getLabel().equals(detection.getLabel())) continue;

                // IoU 계산
                float iou = calculateIoU(trackedObj, detection);

                // 크기 유사성 측정
                float trackedWidth = trackedObj.getRight() - trackedObj.getLeft();
                float trackedHeight = trackedObj.getBottom() - trackedObj.getTop();
                float detWidth = detection.getRight() - detection.getLeft();
                float detHeight = detection.getBottom() - detection.getTop();

                float sizeRatio = Math.min(trackedWidth / detWidth, detWidth / trackedWidth) *
                        Math.min(trackedHeight / detHeight, detHeight / trackedHeight);

                // 위치 유사성 (중심점 거리)
                float trackedCenterX = (trackedObj.getLeft() + trackedObj.getRight()) / 2;
                float trackedCenterY = (trackedObj.getTop() + trackedObj.getBottom()) / 2;
                float detCenterX = (detection.getLeft() + detection.getRight()) / 2;
                float detCenterY = (detection.getTop() + detection.getBottom()) / 2;

                float centerDistance = (float) Math.sqrt(
                        Math.pow(trackedCenterX - detCenterX, 2) +
                                Math.pow(trackedCenterY - detCenterY, 2));

                float maxDim = Math.max(
                        Math.max(trackedWidth, trackedHeight),
                        Math.max(detWidth, detHeight));

                float normDistance = Math.max(0, 1 - centerDistance / maxDim);

                // 종합 점수 (IoU, 크기 유사성, 위치 유사성)
                float score = iou * 0.6f + sizeRatio * 0.2f + normDistance * 0.2f;

                if (score > bestScore) {
                    bestScore = score;
                    bestMatchIdx = i;
                }
            }

            if (bestMatchIdx >= 0) {
                YoloImageProcessor.Detection matchedDetection = detections.get(bestMatchIdx);
                trackedObj.update(matchedDetection);
                matched[bestMatchIdx] = true;
            } else {
                trackedObj.incrementAge();
            }
        }
    }

    // 높은 IoU 값으로 확실한 매칭 찾기
    private void matchDetectionsWithHighIoU(List<YoloImageProcessor.Detection> detections,
                                            boolean[] matched, float highIoUThreshold) {
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            int bestMatchIdx = -1;
            float bestIoU = highIoUThreshold;

            for (int i = 0; i < detections.size(); i++) {
                if (matched[i]) continue;

                YoloImageProcessor.Detection detection = detections.get(i);
                if (!trackedObj.getLabel().equals(detection.getLabel())) continue;

                float iou = calculateIoU(trackedObj, detection);
                if (iou > bestIoU) {
                    bestIoU = iou;
                    bestMatchIdx = i;
                }
            }

            if (bestMatchIdx >= 0) {
                YoloImageProcessor.Detection matchedDetection = detections.get(bestMatchIdx);
                trackedObj.update(matchedDetection);
                matched[bestMatchIdx] = true;
            }
        }
    }


    /**
     * 모든 추적 객체의 나이를 증가시킴
     */
    private void increaseAge() {
        for (TrackedObject obj : trackedObjects.values()) {
            obj.incrementAge();
        }
    }

    /**
     * 지정된 최대 나이보다 오래된 객체 제거
     */
    private void removeOldObjects() {
        Iterator<Map.Entry<Integer, TrackedObject>> it = trackedObjects.entrySet().iterator();
        while (it.hasNext()) {
            TrackedObject obj = it.next().getValue();
            if (obj.getAge() > MAX_AGE) {
                Log.d(TAG, "객체 제거: ID=" + obj.getId() + ", Label=" + obj.getLabel());
                it.remove();
            }
        }
    }

    /**
     * 두 객체 간의 IoU(Intersection over Union)를 계산
     */
    private float calculateIoU(TrackedObject trackedObj, YoloImageProcessor.Detection detection) {
        // 교차 영역 계산
        float xLeft = Math.max(trackedObj.getLeft(), detection.getLeft());
        float yTop = Math.max(trackedObj.getTop(), detection.getTop());
        float xRight = Math.min(trackedObj.getRight(), detection.getRight());
        float yBottom = Math.min(trackedObj.getBottom(), detection.getBottom());

        // 교차 영역이 없으면 0 반환
        if (xRight < xLeft || yBottom < yTop) return 0;

        float intersectionArea = (xRight - xLeft) * (yBottom - yTop);

        // 각 영역 계산
        float trackedObjArea = (trackedObj.getRight() - trackedObj.getLeft()) *
                (trackedObj.getBottom() - trackedObj.getTop());
        float detectionArea = (detection.getRight() - detection.getLeft()) *
                (detection.getBottom() - detection.getTop());

        // IoU 계산
        return intersectionArea / (trackedObjArea + detectionArea - intersectionArea);
    }

    /**
     * 추적 객체 클래스
     */
    public static class TrackedObject {
        private final int id;
        private String label;
        private float confidence;
        private float left;
        private float top;
        private float right;
        private float bottom;
        private int age;

        // 속도 추적을 위한 필드 추가
        private float velocityX;
        private float velocityY;
        private float prevCenterX;
        private float prevCenterY;
        private long lastMatchedTime;

        public TrackedObject(int id, String label, float confidence,
                             float left, float top, float right, float bottom) {
            this.id = id;
            this.label = label;
            this.confidence = confidence;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.age = 0;
            this.lastMatchedTime = System.currentTimeMillis();
            this.velocityX = 0;
            this.velocityY = 0;
            this.prevCenterX = (left + right) / 2;
            this.prevCenterY = (top + bottom) / 2;
        }

        /**
         * 새로운 탐지 결과로 추적 객체 업데이트
         */
        public void update(YoloImageProcessor.Detection detection) {
            // 속도 계산 (이전 중심점과 새 중심점 사용)
            float centerX = (detection.getLeft() + detection.getRight()) / 2;
            float centerY = (detection.getTop() + detection.getBottom()) / 2;

            // 속도 업데이트 (이동 평균 사용)
            velocityX = VELOCITY_WEIGHT * (centerX - prevCenterX) + (1-VELOCITY_WEIGHT) * velocityX;
            velocityY = VELOCITY_WEIGHT * (centerY - prevCenterY) + (1-VELOCITY_WEIGHT) * velocityY;


            this.confidence = detection.getConfidence();
            this.left = detection.getLeft();
            this.top = detection.getTop();
            this.right = detection.getRight();
            this.bottom = detection.getBottom();
            this.age = 0;  // 탐지되었으므로 나이 초기화

            // 중심점 저장
            this.prevCenterX = centerX;
            this.prevCenterY = centerY;

            // 나이 초기화 및 매칭 시간 업데이트
            this.age = 0;
            this.lastMatchedTime = System.currentTimeMillis();
        }

        public void incrementAge() {
            this.age++;
        }

        // 속도 기반 위치 예측
        public void predict() {
            if (age > 0 && USE_VELOCITY_PREDICTION) {
                float width = right - left;
                float height = bottom - top;

                left += velocityX;
                top += velocityY;
                right = left + width;
                bottom = top + height;

                // 중심점 업데이트
                prevCenterX += velocityX;
                prevCenterY += velocityY;
            }
        }

        // Getters
        public int getId() { return id; }
        public String getLabel() { return label; }
        public float getConfidence() { return confidence; }
        public float getLeft() { return left; }
        public float getTop() { return top; }
        public float getRight() { return right; }
        public float getBottom() { return bottom; }
        public int getAge() { return age; }
        public long getLastMatchedTime() { return lastMatchedTime; }
        @Override
        public String toString() {
            return id + ": " + label + " (" + String.format("%.2f", confidence * 100) + "%), age=" + age;
        }
    }
}