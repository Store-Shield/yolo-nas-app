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
    private static final int MAX_AGE = 20;  // 객체가 사라졌다고 판단하기 전 최대 프레임 수 즉 높을수록 일시적으로 가려져도 유지

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

        // 매칭을 위해 모든 객체에 대해 다음 위치 예측
        for (TrackedObject obj : trackedObjects.values()) {
            obj.predictForMatching();
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

                // IoU 계산 - 여기서는 예측된 위치를 사용
                float iou = calculateIoUWithPrediction(trackedObj, detection);

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
                // 일치하는 객체가 없으면 나이만 증가 (위치는 변경하지 않음)
                trackedObj.incrementAge();
            }
        }

        for (int i = 0; i < detections.size(); i++) {
            if (!matched[i]) {
                YoloImageProcessor.Detection detection = detections.get(i);

                // 이미 비슷한 위치에 같은 종류의 객체가 있는지 확인
                boolean duplicateFound = false;
                for (TrackedObject existingObj : trackedObjects.values()) {
                    // 같은 레이블의 객체만 확인
                    if (existingObj.getLabel().equals(detection.getLabel())) {
                        // 중심점 계산
                        float existingCenterX = (existingObj.getLeft() + existingObj.getRight()) / 2;
                        float existingCenterY = (existingObj.getTop() + existingObj.getBottom()) / 2;
                        float detCenterX = (detection.getLeft() + detection.getRight()) / 2;
                        float detCenterY = (detection.getTop() + detection.getBottom()) / 2;

                        // 거리 계산
                        float distance = (float) Math.sqrt(
                                Math.pow(existingCenterX - detCenterX, 2) +
                                        Math.pow(existingCenterY - detCenterY, 2));

                        // 객체 크기 계산
                        float existingWidth = existingObj.getRight() - existingObj.getLeft();
                        float detWidth = detection.getRight() - detection.getLeft();
                        float avgWidth = (existingWidth + detWidth) / 2;

                        // 너무 가까이 있으면 중복으로 간주
                        if (distance < avgWidth * 1.5) {
                            duplicateFound = true;
                            break;
                        }
                    }
                }

                // 중복이 아닌 경우에만 새 객체 추가
                if (!duplicateFound) {
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
        }

        detectCrossings();
        // 오래된 객체 제거
        removeOldObjects();

        // 현재 추적 중인 객체 목록 반환
        return new ArrayList<>(trackedObjects.values());
    }

    // 교차 상황 감지 및 처리 메서드
    private void detectCrossings() {
        List<TrackedObject> personObjects = new ArrayList<>();

        // 사람 객체 필터링
        for (TrackedObject obj : trackedObjects.values()) {
            if ("person".equals(obj.getLabel())) {
                personObjects.add(obj);
            }
        }

        // 사람 객체가 2개 이상일 때만 교차 검사
        if (personObjects.size() >= 2) {

            for (int i = 0; i < personObjects.size() - 1; i++) {
                TrackedObject person1 = personObjects.get(i);

                for (int j = i + 1; j < personObjects.size(); j++) {
                    TrackedObject person2 = personObjects.get(j);

                    // 두 사람의 중심점
                    float cx1 = (person1.getLeft() + person1.getRight()) / 2;
                    float cy1 = (person1.getTop() + person1.getBottom()) / 2;
                    float cx2 = (person2.getLeft() + person2.getRight()) / 2;
                    float cy2 = (person2.getTop() + person2.getBottom()) / 2;

                    // 거리 계산
                    float distance = (float) Math.sqrt(
                            Math.pow(cx1 - cx2, 2) + Math.pow(cy1 - cy2, 2)
                    );

                    // 두 사람의 너비/높이 평균
                    float avgWidth = ((person1.getRight() - person1.getLeft()) +
                            (person2.getRight() - person2.getLeft())) / 2;

                    // 두 사람이 충분히 가까우면 (겹치거나 거의 겹치는 경우)
                    if (distance < avgWidth * 1.2) {  // 120% 너비 이내 = 교차 중

                        Log.i("personcross","사람겹칩");

                        // 두 사람의 이동 방향
                        float vx1 = person1.getVelocityX();
                        float vy1 = person1.getVelocityY();
                        float vx2 = person2.getVelocityX();
                        float vy2 = person2.getVelocityY();

                        // 속도 크기
                        float speed1 = (float) Math.sqrt(vx1 * vx1 + vy1 * vy1);
                        float speed2 = (float) Math.sqrt(vx2 * vx2 + vy2 * vy2);

                        // 속도가 충분히 큰 경우에만 고려
                        if (speed1 > 0.5 && speed2 > 0.5) {
                            // 방향 내적 (음수면 서로 반대 방향)
                            float dirDot = vx1 * vx2 + vy1 * vy2;

                            // 서로 다른 방향으로 움직이는 경우 (교차 중)
                            if (dirDot < 0) {
                                // 방향 정보를 더 중요하게 사용하기 위해 두 객체의 속도 가중치 증가
                                person1.boostVelocity(1.5f);  // 50% 증가
                                person2.boostVelocity(1.5f);  // 50% 증가

                                Log.d(TAG, "교차 감지: ID " + person1.getId() + " ↔ ID " + person2.getId());
                            }
                        }
                    }
                }
            }
        }
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

                // IoU 계산 - 예측된 위치 사용
                float iou = calculateIoUWithPrediction(trackedObj, detection);

                // 크기 유사성 측정
                float trackedWidth = trackedObj.getRight() - trackedObj.getLeft();
                float trackedHeight = trackedObj.getBottom() - trackedObj.getTop();
                float detWidth = detection.getRight() - detection.getLeft();
                float detHeight = detection.getBottom() - detection.getTop();

                float sizeRatio = Math.min(trackedWidth / detWidth, detWidth / trackedWidth) *
                        Math.min(trackedHeight / detHeight, detHeight / trackedHeight);

                // 위치 유사성 (중심점 거리)
                float trackedCenterX = trackedObj.getPredictedCenterX();
                float trackedCenterY = trackedObj.getPredictedCenterY();
                float detCenterX = (detection.getLeft() + detection.getRight()) / 2;
                float detCenterY = (detection.getTop() + detection.getBottom()) / 2;

                float centerDistance = (float) Math.sqrt(
                        Math.pow(trackedCenterX - detCenterX, 2) +
                                Math.pow(trackedCenterY - detCenterY, 2));

                float maxDim = Math.max(
                        Math.max(trackedWidth, trackedHeight),
                        Math.max(detWidth, detHeight));

                float normDistance = Math.max(0, 1 - centerDistance / maxDim);

                // 방향 점수 계산
                float directionScore = calculateDirectionScore(trackedObj, detCenterX, detCenterY);

                float score;
                if ("person".equals(trackedObj.getLabel())) {
                    // 사람은 방향성에 더 높은 가중치 부여 (기존과 동일)
                    score = iou * 0.3f + sizeRatio * 0.1f + normDistance * 0.1f + directionScore * 0.5f;
                } else {
                    // 다른 객체는 방향성 가중치를 낮추고 IoU와 위치 유사성에 더 높은 가중치 부여
                    score = iou * 0.5f + sizeRatio * 0.2f + normDistance * 0.25f + directionScore * 0.05f;
                }

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

    // 방향 점수 계산 메서드
    private float calculateDirectionScore(TrackedObject obj, float detCenterX, float detCenterY) {
        float velX = obj.getVelocityX();
        float velY = obj.getVelocityY();

        // 속도 크기가 충분히 큰 경우에만 방향 고려
        float speedMagnitude = (float) Math.sqrt(velX * velX + velY * velY);

        // 0.5는 임계값 - 이 값보다 큰 속도에서만 방향 정보 고려
        if (speedMagnitude > 0.5f) {
            // 현재 위치의 중심점
            float currentCenterX = (obj.getLeft() + obj.getRight()) / 2;
            float currentCenterY = (obj.getTop() + obj.getBottom()) / 2;

            // 실제 이동 방향 벡터 (현재 위치 → 탐지된 위치)
            float actualDirX = detCenterX - currentCenterX;
            float actualDirY = detCenterY - currentCenterY;

            // 방향 벡터 내적 계산
            float dotProduct = velX * actualDirX + velY * actualDirY;

            // 실제 이동 벡터의 크기
            float actualMag = (float) Math.sqrt(actualDirX * actualDirX + actualDirY * actualDirY);

            // 방향 일치도 계산 (코사인 유사도)
            if (actualMag > 0) {
                float cosine = dotProduct / (speedMagnitude * actualMag);
                // -1~1 범위를 0~1로 변환 후 제곱하여 차이를 더 강조
                float normalizedScore = (cosine + 1) / 2;
                return normalizedScore * normalizedScore;  // 제곱하여 일치 시 더 높은 점수
            }
        }

        // 방향을 고려할 수 없는 경우
        return 0.3f;  // 낮은 기본값
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

                float iou = calculateIoUWithPrediction(trackedObj, detection);
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
     * 두 객체 간의 IoU(Intersection over Union)를 계산 - 예측된 위치 사용
     */
    private float calculateIoUWithPrediction(TrackedObject trackedObj, YoloImageProcessor.Detection detection) {
        // 교차 영역 계산 - 예측된 위치 사용
        float xLeft = Math.max(trackedObj.getPredictedLeft(), detection.getLeft());
        float yTop = Math.max(trackedObj.getPredictedTop(), detection.getTop());
        float xRight = Math.min(trackedObj.getPredictedRight(), detection.getRight());
        float yBottom = Math.min(trackedObj.getPredictedBottom(), detection.getBottom());

        // 교차 영역이 없으면 0 반환
        if (xRight < xLeft || yBottom < yTop) return 0;

        float intersectionArea = (xRight - xLeft) * (yBottom - yTop);

        // 각 영역 계산
        float trackedObjArea = (trackedObj.getPredictedRight() - trackedObj.getPredictedLeft()) *
                (trackedObj.getPredictedBottom() - trackedObj.getPredictedTop());
        float detectionArea = (detection.getRight() - detection.getLeft()) *
                (detection.getBottom() - detection.getTop());

        // IoU 계산
        return intersectionArea / (trackedObjArea + detectionArea - intersectionArea);
    }

    /**
     * 원래 IoU 계산 메서드 (디스플레이용)
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

        // 매칭 계산용 예측 위치 (화면에 표시되지 않음)
        private float predictedLeft;
        private float predictedTop;
        private float predictedRight;
        private float predictedBottom;

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

            // 초기 예측 위치는 현재 위치와 동일
            this.predictedLeft = left;
            this.predictedTop = top;
            this.predictedRight = right;
            this.predictedBottom = bottom;
        }

        public void boostVelocity(float factor) {
            this.velocityX *= factor;
            this.velocityY *= factor;
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

            // 예측 위치도 현재 위치로 업데이트
            this.predictedLeft = this.left;
            this.predictedTop = this.top;
            this.predictedRight = this.right;
            this.predictedBottom = this.bottom;
        }

        public void incrementAge() {
            this.age++;
        }


        // 매칭 계산용 위치 예측 (실제 표시되는 위치는 변경되지 않음)
        public void predictForMatching() {
            if (age > 0 && USE_VELOCITY_PREDICTION) {
                float width = right - left;
                float height = bottom - top;

                // 예측 위치만 업데이트
                predictedLeft = left + velocityX;
                predictedTop = top + velocityY;
                predictedRight = predictedLeft + width;
                predictedBottom = predictedTop + height;
            } else {
                // 예측이 필요 없으면 현재 위치 복사
                predictedLeft = left;
                predictedTop = top;
                predictedRight = right;
                predictedBottom = bottom;
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
        public float getVelocityX() { return velocityX; }
        public float getVelocityY() { return velocityY; }

        // 예측 위치 getter
        public float getPredictedLeft() { return predictedLeft; }
        public float getPredictedTop() { return predictedTop; }
        public float getPredictedRight() { return predictedRight; }
        public float getPredictedBottom() { return predictedBottom; }
        public float getPredictedCenterX() { return (predictedLeft + predictedRight) / 2; }
        public float getPredictedCenterY() { return (predictedTop + predictedBottom) / 2; }

        @Override
        public String toString() {
            return id + ": " + label + " (" + String.format("%.2f", confidence * 100) + "%), age=" + age;
        }
    }
}