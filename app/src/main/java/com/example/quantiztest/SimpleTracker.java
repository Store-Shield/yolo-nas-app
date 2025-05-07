package com.example.quantiztest;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SimpleTracker {
    private static final String TAG = "SimpleTracker";
    private static final float IOU_THRESHOLD = 0.25f; // 같은 객체로 간주할 IoU 임계값 낮출수록 잘 추정
    private static final int MAX_AGE = 10; // 객체가 사라졌다고 판단하기 전 최대 프레임 수 즉 높을수록 일시적으로 가려져도 유지
    private static final float OVERLAP_THRESHOLD = 1.2f; // 평균 너비의 120%
    private static final boolean USE_VELOCITY_PREDICTION = true;
    private static final float VELOCITY_WEIGHT = 0.7f;
    // 추적 중인 객체 목록
    private final Map<Integer, TrackedObject> trackedObjects = new HashMap<>();
    private int nextId = 0;

    /**
     * 현재 프레임에서 탐지된 객체를 이전 프레임의 추적 객체와 연결
     *
     * @param detections 현재 프레임에서 탐지된 객체 목록
     * @return 추적 ID가 할당된 객체 목록
     */
    public List<TrackedObject> update(List<YoloImageProcessor.Detection> detections,Bitmap currentBitmap) {
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
        float highIoUThreshold = 0.5f; // 높은 확신을 가진 매칭을 위한 임계값
        matchDetectionsWithHighIoU(detections, matched, highIoUThreshold);

        // 교차 감지 및 처리 - 확실한 매칭이 안 된 객체들만 대상으로
        detectCrossings(detections, matched);

        // 2단계: 남은 객체들에 대해 더 낮은 IoU와 추가 특성 기반 매칭
        matchRemainingDetections(detections, matched,currentBitmap);

        // 모든 기존 추적 객체에 대해 반복
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            boolean matchFound = false;
            int bestMatchIdx = -1;
            float bestIoU = IOU_THRESHOLD;

            // 현재 탐지된 모든 객체와 비교
            for (int i = 0; i < detections.size(); i++) {
                if (matched[i])
                    continue; // 이미 매칭된 객체 건너뛰기

                YoloImageProcessor.Detection detection = detections.get(i);

                // 라벨이 같은 객체만 비교
                if (!trackedObj.getLabel().equals(detection.getLabel()))
                    continue;

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
                if ("person".equals(trackedObj.getLabel()) && !trackedObj.hasColorInfo()) {
                    extractColorFeatures(trackedObj, currentBitmap);
                }
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
                            detection.getBottom());
                    trackedObjects.put(newTrackedObj.getId(), newTrackedObj);
                    // 사람 객체인 경우 색상 정보 추출
                    if ("person".equals(newTrackedObj.getLabel())) {
                        extractColorFeatures(newTrackedObj, currentBitmap);
                    }
                }
            }
        }


        // 오래된 객체 제거
        removeOldObjects();
        Log.d("personcheck", "===============================");


        // 현재 추적 중인 객체 목록 반환
        return new ArrayList<>(trackedObjects.values());
    }

    private void detectCrossings(List<YoloImageProcessor.Detection> detections, boolean[] matched) {
        List<TrackedObject> unmatchedPersons = new ArrayList<>();

        // 아직 매칭되지 않은 사람 객체만 필터링
        for (TrackedObject obj : trackedObjects.values()) {
            if ("person".equals(obj.getLabel())) {
                // 이 객체가 이미 매칭되었는지 확인
                boolean isMatched = false;
                for (int i = 0; i < detections.size(); i++) {
                    if (matched[i] && detections.get(i).getLabel().equals("person")) {
                        float iou = calculateIoUWithPrediction(obj, detections.get(i));
                        if (iou > 0.5f) { // 높은 IoU로 매칭된 경우
                            isMatched = true;
                            break;
                        }
                    }
                }

                // 매칭되지 않은 객체만 추가
                if (!isMatched) {
                    unmatchedPersons.add(obj);
                }
            }
        }

        // 매칭되지 않은 사람 객체가 2명 이상일 때만 교차 검사
        if (unmatchedPersons.size() >= 2) {
            for (int i = 0; i < unmatchedPersons.size() - 1; i++) {
                TrackedObject person1 = unmatchedPersons.get(i);

                for (int j = i + 1; j < unmatchedPersons.size(); j++) {
                    TrackedObject person2 = unmatchedPersons.get(j);

                    // 두 사람의 중심점
                    float cx1 = (person1.getLeft() + person1.getRight()) / 2;
                    float cy1 = (person1.getTop() + person1.getBottom()) / 2;
                    float cx2 = (person2.getLeft() + person2.getRight()) / 2;
                    float cy2 = (person2.getTop() + person2.getBottom()) / 2;

                    // 거리 계산
                    float distance = (float) Math.sqrt(
                            Math.pow(cx1 - cx2, 2) + Math.pow(cy1 - cy2, 2));

                    // 두 사람의 너비/높이 평균
                    float avgWidth = ((person1.getRight() - person1.getLeft()) +
                            (person2.getRight() - person2.getLeft())) / 2;

                    // 두 사람이 충분히 가까우면 (겹치거나 거의 겹치는 경우)
                    if (distance < avgWidth * OVERLAP_THRESHOLD) { // 통일된 임계값 사용
                        Log.i("personcross", "매칭되지 않은 사람들 겹침");

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
                                person1.boostVelocity(1.5f); // 50% 증가
                                person2.boostVelocity(1.5f); // 50% 증가

                                Log.d("personcheck", "교차 감지 (매칭되지 않은 객체): ID " + person1.getId() + " ↔ ID " + person2.getId());
                                Log.d("personcheck", person1.getId() + "," + person2.getId() + " 방향 가중치 증가");
                            }
                        }
                    }
                }
            }
        }
    }
    private void matchRemainingDetections(List<YoloImageProcessor.Detection> detections, boolean[] matched,Bitmap currentBitmap) {
        // 1. 사람 객체들 간의 겹침 여부를 미리 확인
        Map<Integer, Boolean> personOverlapStatus = new HashMap<>();

        // 추적 중인 사람 객체들을 모두 가져옴
        List<TrackedObject> personObjects = new ArrayList<>();
        for (TrackedObject obj : trackedObjects.values()) {
            if ("person".equals(obj.getLabel())) {
                personObjects.add(obj);
                personOverlapStatus.put(obj.getId(), false); // 기본값: 겹치지 않음
            }
        }

        // 사람 객체들 간의 겹침 확인
        if (personObjects.size() >= 2) {
            for (int i = 0; i < personObjects.size() - 1; i++) {
                TrackedObject person1 = personObjects.get(i);

                for (int j = i + 1; j < personObjects.size(); j++) {
                    TrackedObject person2 = personObjects.get(j);

                    // 두 사람의 중심점 간 거리 계산
                    float cx1 = (person1.getLeft() + person1.getRight()) / 2;
                    float cy1 = (person1.getTop() + person1.getBottom()) / 2;
                    float cx2 = (person2.getLeft() + person2.getRight()) / 2;
                    float cy2 = (person2.getTop() + person2.getBottom()) / 2;

                    float distance = (float) Math.sqrt(
                            Math.pow(cx1 - cx2, 2) + Math.pow(cy1 - cy2, 2));

                    // 두 사람의 너비 평균
                    float avgWidth = ((person1.getRight() - person1.getLeft()) +
                            (person2.getRight() - person2.getLeft())) / 2;

                    // 겹침 여부 판단 (평균 너비 이내면 겹침으로 간주)
                    if (distance < avgWidth * OVERLAP_THRESHOLD) {
                        personOverlapStatus.put(person1.getId(), true);
                        personOverlapStatus.put(person2.getId(), true);
                    }
                }
            }
        }

        // 2. 각 객체에 대해 매칭 수행
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            boolean alreadyMatched = false;
            for (int i = 0; i < detections.size(); i++) {
                if (matched[i] && detections.get(i).getLabel().equals(trackedObj.getLabel())) {
                    // 같은 라벨의 객체가 이미 매칭되었는지 IoU로 확인
                    float iou = calculateIoUWithPrediction(trackedObj, detections.get(i));
                    if (iou > 0.5) { // 높은 IoU라면 이미 매칭된 것으로 간주
                        alreadyMatched = true;
                        break;
                    }
                }
            }

            if (alreadyMatched)
                continue;

            int bestMatchIdx = -1;
            float bestScore = IOU_THRESHOLD;

            for (int i = 0; i < detections.size(); i++) {
                if (matched[i])
                    continue;

                YoloImageProcessor.Detection detection = detections.get(i);
                if (!trackedObj.getLabel().equals(detection.getLabel()))
                    continue;

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
                    float colorSimilarity = calculateColorSimilarity(trackedObj, detection, currentBitmap);
                    // 사람이면서 겹침 상황인 경우
                    if (personOverlapStatus.containsKey(trackedObj.getId()) &&
                            personOverlapStatus.get(trackedObj.getId())) {
                        // 겹침 상황: 방향성 가중치 증가
                        score = iou * 0.4f + sizeRatio * 0.1f +  colorSimilarity * 0.25f+directionScore * 0.25f;


                        //score = iou * 0.4f + sizeRatio * 0.05f + normDistance * 0.05f + colorSimilarity * 0.5f;
                        Log.d("personcheck", "사람 겹침 상황 - ID: " + trackedObj.getId() );
                    } else {

                        score = iou * 0.4f + sizeRatio * 0.1f +  colorSimilarity * 0.25f+directionScore * 0.25f;


                        // 일반 상황: 방향성 가중치 낮음
                        //score = iou * 0.4f + sizeRatio * 0.05f + normDistance * 0.05f + colorSimilarity * 0.5f;
                        Log.d("personcheck", "겹치지않을경우 " + trackedObj.getId() );

                    }
                } else {
                    // 다른 객체는 방향성 가중치를 매우 낮게 설정
                    score = iou * 0.6f + sizeRatio * 0.2f + normDistance * 0.15f + directionScore * 0.05f;
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
                return normalizedScore * normalizedScore; // 제곱하여 일치 시 더 높은 점수
            }
        }

        // 방향을 고려할 수 없는 경우
        return 0.3f; // 낮은 기본값
    }

    // 높은 IoU 값으로 확실한 매칭 찾기
    private void matchDetectionsWithHighIoU(List<YoloImageProcessor.Detection> detections,
                                            boolean[] matched, float highIoUThreshold) {
        for (TrackedObject trackedObj : new ArrayList<>(trackedObjects.values())) {
            int bestMatchIdx = -1;
            float bestIoU = highIoUThreshold;

            for (int i = 0; i < detections.size(); i++) {
                if (matched[i])
                    continue;

                YoloImageProcessor.Detection detection = detections.get(i);
                if (!trackedObj.getLabel().equals(detection.getLabel()))
                    continue;

                float iou = calculateIoUWithPrediction(trackedObj, detection);
                if (iou > bestIoU) {
                    bestIoU = iou;
                    bestMatchIdx = i;
                }
            }

            if (bestMatchIdx >= 0) {
                YoloImageProcessor.Detection matchedDetection = detections.get(bestMatchIdx);
                Log.d("personcheck","첫번째 탐지결과 " + trackedObj.getId() + "는 확실한 매칭완료");
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
        if (xRight < xLeft || yBottom < yTop)
            return 0;

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
        if (xRight < xLeft || yBottom < yTop)
            return 0;

        float intersectionArea = (xRight - xLeft) * (yBottom - yTop);

        // 각 영역 계산
        float trackedObjArea = (trackedObj.getRight() - trackedObj.getLeft()) *
                (trackedObj.getBottom() - trackedObj.getTop());
        float detectionArea = (detection.getRight() - detection.getLeft()) *
                (detection.getBottom() - detection.getTop());

        // IoU 계산
        return intersectionArea / (trackedObjArea + detectionArea - intersectionArea);
    }

    // SimpleTracker 클래스에 추가할 메서드
    private void extractColorFeatures(TrackedObject obj, Bitmap bitmap) {
        // 사람 객체만 처리
        if (!"person".equals(obj.getLabel())) {
            return;
        }

        int left = (int) obj.getLeft();
        int top = (int) obj.getTop();
        int right = (int) obj.getRight();
        int bottom = (int) obj.getBottom();

        // 경계 확인
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth() - 1, right);
        bottom = Math.min(bitmap.getHeight() - 1, bottom);

        int width = right - left;
        int height = bottom - top;

        // 객체가 너무 작으면 색상 추출 스킵
        if (width <= 0 || height <= 0 || width * height < 100) {
            return;
        }

        // 너비 조정 - 양쪽에서 20% 안쪽으로 들어옴
        int widthMargin = (int)(width * 0.2);
        int centeredLeft = left + widthMargin;
        int centeredRight = right - widthMargin;

        // 조정된 너비가 너무 작지 않은지 확인
        if (centeredRight - centeredLeft < 10) {
            // 최소 10픽셀 확보
            int center = (left + right) / 2;
            centeredLeft = center - 5;
            centeredRight = center + 5;

            // 경계 확인
            centeredLeft = Math.max(0, centeredLeft);
            centeredRight = Math.min(bitmap.getWidth() - 1, centeredRight);
        }

        // 상체와 하체 영역 계산 - 높이 기준
        int upperBodyTop = top + (int)(height * 0.25);
        int upperBodyBottom = top + (int)(height * 0.50);
        int lowerBodyTop = top + (int)(height * 0.60);
        int lowerBodyBottom = top + (int)(height * 0.85);

        // 영역이 너무 작으면 조정
        int minRegionHeight = 20;
        if (upperBodyBottom - upperBodyTop < minRegionHeight) {
            upperBodyBottom = upperBodyTop + minRegionHeight;
        }
        if (lowerBodyBottom - lowerBodyTop < minRegionHeight) {
            lowerBodyBottom = lowerBodyTop + minRegionHeight;
        }

        // 경계 확인
        upperBodyBottom = Math.min(upperBodyBottom, bottom);
        lowerBodyBottom = Math.min(lowerBodyBottom, bottom);

        // 각 영역의 평균 RGB 계산 - 중앙 부분만 사용
        float[] upperBodyColors = calculateAvgColor(bitmap, centeredLeft, upperBodyTop, centeredRight, upperBodyBottom);
        float[] lowerBodyColors = calculateAvgColor(bitmap, centeredLeft, lowerBodyTop, centeredRight, lowerBodyBottom);
        obj.setColorFeatures(upperBodyColors, lowerBodyColors);

        // 색상 정보 로그 출력
        Log.d("ColorInfo", String.format(
                "사람 ID %d 색상 정보: " +
                        "상체 [R:%.1f, G:%.1f, B:%.1f], " +
                        "하체 [R:%.1f, G:%.1f, B:%.1f]",
                obj.getId(),
                upperBodyColors[0], upperBodyColors[1], upperBodyColors[2],
                lowerBodyColors[0], lowerBodyColors[1], lowerBodyColors[2]
        ));


    }

    private float[] calculateAvgColor(Bitmap bitmap, int left, int top, int right, int bottom) {
        float[] avgColor = new float[3]; // R, G, B
        int pixelCount = 0;

        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                try {
                    int pixel = bitmap.getPixel(x, y);
                    avgColor[0] += (pixel >> 16) & 0xFF; // R
                    avgColor[1] += (pixel >> 8) & 0xFF;  // G
                    avgColor[2] += pixel & 0xFF;         // B
                    pixelCount++;
                } catch (Exception e) {
                    // 경계를 벗어난 픽셀 처리
                    Log.e(TAG, "색상 추출 중 오류: " + e.getMessage());
                }
            }
        }

        if (pixelCount > 0) {
            avgColor[0] /= pixelCount;
            avgColor[1] /= pixelCount;
            avgColor[2] /= pixelCount;
        }

        return avgColor;
    }
    private float calculateColorSimilarity(TrackedObject obj1, YoloImageProcessor.Detection detection, Bitmap bitmap) {
        if (!obj1.hasColorInfo()) {
            return 0.3f; // 기본값
        }

        // 현재 탐지된 객체의 바운딩 박스
        int left = (int) detection.getLeft();
        int top = (int) detection.getTop();
        int right = (int) detection.getRight();
        int bottom = (int) detection.getBottom();

        // 경계 확인
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth() - 1, right);
        bottom = Math.min(bitmap.getHeight() - 1, bottom);

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0 || width * height < 100) {
            return 0.3f; // 기본값
        }

        // 상체와 하체 영역 계산
        int upperBodyTop = top + (int)(height * 0.25);
        int upperBodyBottom = top + (int)(height * 0.50);
        int lowerBodyTop = top + (int)(height * 0.60);
        int lowerBodyBottom = top + (int)(height * 0.85);

        // 경계 확인
        upperBodyBottom = Math.min(upperBodyBottom, bottom);
        lowerBodyBottom = Math.min(lowerBodyBottom, bottom);

        // 각 영역의 평균 RGB 계산
        float[] upperBodyColors = calculateAvgColor(bitmap, left, upperBodyTop, right, upperBodyBottom);
        float[] lowerBodyColors = calculateAvgColor(bitmap, left, lowerBodyTop, right, lowerBodyBottom);

        // 색상 유사도 계산
        float upperSim = colorDistance(obj1.getUpperBodyColors(), upperBodyColors);
        float lowerSim = colorDistance(obj1.getLowerBodyColors(), lowerBodyColors);

        // 색상 유사도 로그
        Log.d("ColorSimilarity", String.format(
                "ID %d 와 후보 객체 색상 유사도: 상체 %.2f, 하체 %.2f",
                obj1.getId(), upperSim, lowerSim
        ));

        // 상체와 하체에 동일한 가중치 부여
        return (upperSim * 0.5f + lowerSim * 0.5f);
    }
    private float colorDistance(float[] color1, float[] color2) {
        // 유클리드 거리의 역수를 사용하여 유사도 계산 (0~1 범위)
        float distance = 0;
        for (int i = 0; i < 3; i++) {
            float diff = color1[i] - color2[i];
            distance += diff * diff;
        }
        distance = (float) Math.sqrt(distance);

        // 최대 거리는 약 441.7 (255*sqrt(3))
        float maxDistance = 441.7f;

        // 거리를 유사도로 변환 (거리가 클수록 유사도는 낮음)
        float similarity = Math.max(0, 1 - (distance / maxDistance));

        return similarity;
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

        // TrackedObject 클래스에 추가할 필드
        private float[] upperBodyColors; // 상체 영역 RGB 평균값 [R, G, B]
        private float[] lowerBodyColors; // 하체 영역 RGB 평균값 [R, G, B]
        private boolean hasColorInfo = false; // 색상 정보가 추출되었는지 여부

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





        public boolean hasColorInfo() {
            return hasColorInfo;
        }

        public void setColorFeatures(float[] upperBody, float[] lowerBody) {
            this.upperBodyColors = upperBody;
            this.lowerBodyColors = lowerBody;
            this.hasColorInfo = true;
        }

        // getter 메서드도 수정
        public float[] getUpperBodyColors() {
            return upperBodyColors;
        }

        public float[] getLowerBodyColors() {
            return lowerBodyColors;
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
            velocityX = VELOCITY_WEIGHT * (centerX - prevCenterX) + (1 - VELOCITY_WEIGHT) * velocityX;
            velocityY = VELOCITY_WEIGHT * (centerY - prevCenterY) + (1 - VELOCITY_WEIGHT) * velocityY;

            this.confidence = detection.getConfidence();
            this.left = detection.getLeft();
            this.top = detection.getTop();
            this.right = detection.getRight();
            this.bottom = detection.getBottom();
            this.age = 0; // 탐지되었으므로 나이 초기화

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
        public int getId() {
            return id;
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

        public int getAge() {
            return age;
        }

        public long getLastMatchedTime() {
            return lastMatchedTime;
        }

        public float getVelocityX() {
            return velocityX;
        }

        public float getVelocityY() {
            return velocityY;
        }

        // 예측 위치 getter
        public float getPredictedLeft() {
            return predictedLeft;
        }

        public float getPredictedTop() {
            return predictedTop;
        }

        public float getPredictedRight() {
            return predictedRight;
        }

        public float getPredictedBottom() {
            return predictedBottom;
        }

        public float getPredictedCenterX() {
            return (predictedLeft + predictedRight) / 2;
        }

        public float getPredictedCenterY() {
            return (predictedTop + predictedBottom) / 2;
        }

        @Override
        public String toString() {
            return id + ": " + label + " (" + String.format("%.2f", confidence * 100) + "%), age=" + age;
        }
    }
}