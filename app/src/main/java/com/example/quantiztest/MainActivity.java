package com.example.quantiztest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 메인 액티비티 클래스 - 앱의 진입점이자 사용자 인터페이스 및 상호작용을 담당합니다.
 */
public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    // 대각선 가상 선 관련 변수
    // 대각선 가상 선 관련 변수 부분에 추가
    // 객체가 선의 위에 있었는지 여부를 저장하는 맵
    private Map<Integer, Boolean> wasAboveLine;
    private float virtualLineStartX, virtualLineStartY; // 가상 선의 시작점
    private float virtualLineEndX, virtualLineEndY; // 가상 선의 끝점
    private Map<Integer, Float> previousDistanceToLine; // 객체별 이전 선과의 거리
    private Map<Integer, Long> lastEventTime; // 객체별 마지막 이벤트 발생 시간
    private TextView tvEvent; // 이벤트 표시용 TextView
    private static final long EVENT_COOLDOWN = 1000; // 이벤트 쿨다운 시간 (밀리초)

    // 대각선 가상 선 관련 변수 부분에 이전 위치 추적을 위한 맵 추가
    private Map<Integer, Float> previousCenterX; // 객체별 이전 X 좌표
    private Map<Integer, Float> previousCenterY; // 객체별 이전 Y 좌표

    // 로그 태그 상수 (디버깅 시 로그를 필터링하는 데 사용)
    private static final String TAG = "MainActivity";
    // 권한 요청 코드 (권한 요청 결과를 식별하는 데 사용)
    private static final int REQUEST_PERMISSIONS = 1;

    private SimpleTracker tracker;

    // TFLite 모델을 로드하고 관리하는 클래스 인스턴스
    private TFLiteLoader tfliteLoader;
    // 이미지 처리 및 객체 탐지를 담당하는 클래스 인스턴스
    private YoloImageProcessor imageProcessor;

    // UI 요소들
    private Button btnSelectImage;    // 이미지 선택 버튼
    private Button btnStartCamera;    // 카메라 시작 버튼

    //TextureView는 실시간 화면이 나오도록하는것!!
    private TextureView textureView;  // 카메라 프리뷰를 표시할 TextureView
    private ImageView imageView;      // 선택된 이미지를 표시할 ImageView
    private TextView tvResult;        // 탐지 결과를 표시할 TextView
    private SurfaceView overlayView;
    private SurfaceHolder overlayHolder;
    // 갤러리에서 이미지를 선택하기 위한 ActivityResultLauncher
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    // 카메라 관련 변수
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);
    private boolean isProcessingFrame = false;
    private boolean isCameraMode = false;

    /**
     * 액티비티가 생성될 때 호출되는 메서드
     * UI 초기화, 권한 확인, 모델 로딩 등 초기 설정을 수행합니다.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate((Bundle) savedInstanceState);
        // 레이아웃 설정
        setContentView(R.layout.activity_main);

        // 이벤트 표시용 TextView 추가
        tvEvent = new TextView(this);
        tvEvent.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        tvEvent.setPadding(16, 16, 16, 16);
        tvEvent.setTextColor(Color.RED);
        tvEvent.setTextSize(16);
        tvEvent.setText("이벤트가 발생하면 여기에 표시됩니다.");
        tvEvent.setBackgroundColor(Color.parseColor("#22000000")); // 반투명 배경

// FrameLayout에 TextView 추가
        FrameLayout previewContainer = findViewById(R.id.previewContainer);
        previewContainer.addView(tvEvent);

        // 이전 위치 맵 초기화
        previousCenterX = new HashMap<>();
        previousCenterY = new HashMap<>();


        // wasAboveLine 초기화
        wasAboveLine = new HashMap<>();
// 대각선 가상 선 초기화 (640x640 기준)
// 왼쪽 상단에서 오른쪽 하단으로 대각선
        // onCreate 메서드 내에서 선 초기화 부분 수정
// 방향을 반대로 바꿈
        virtualLineStartX = 640;
        virtualLineStartY = 550;
        virtualLineEndX = 0;
        virtualLineEndY = 550;
// 객체 추적 맵 초기화
        previousDistanceToLine = new HashMap<>();
        lastEventTime = new HashMap<>();




        // UI 요소 초기화 - ID로 뷰 찾기
        btnSelectImage = findViewById(R.id.btnSelectImage);
        btnStartCamera = findViewById(R.id.btnStartCamera);
        textureView = findViewById(R.id.textureView);
        imageView = findViewById(R.id.imageView);
        tvResult = findViewById(R.id.tvResult);
        overlayView = findViewById(R.id.overlayView);

        // 오버레이 뷰 초기화
        overlayHolder = overlayView.getHolder();
        overlayHolder.setFormat(PixelFormat.TRANSPARENT);
        overlayView.setZOrderOnTop(true);

        // TextureView 리스너 설정
        textureView.setSurfaceTextureListener(this);

        // imageView를 textureView 위에 위치시키기 위한 설정
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        imageView.setLayoutParams(params);
// imageView가 터치 이벤트를 textureView로 패스하도록 설정
        imageView.setOnTouchListener((v, event) -> false);

        // TextureView 리스너 설정
        textureView.setSurfaceTextureListener(this);

        // 초기화 코드 아래에 추가
        tracker = new SimpleTracker();

        // 초기 UI 상태 설정
        textureView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);

        // 필요한 권한(저장소 읽기/쓰기, 카메라) 확인 및 요청
        checkAndRequestPermissions();

        // TFLite 모델 로더 인스턴스 생성
        tfliteLoader = new TFLiteLoader(this);

        // assets 폴더에서 모델 로드 시도
        if (tfliteLoader.loadModelFromAssets()) {
            // 모델 로드 성공 시 로그 출력 및 토스트 메시지 표시
            Log.i(TAG, "YOLONas TFLite 모델이 성공적으로 로드되었습니다.");
            Toast.makeText(this, "모델 로드 성공!", Toast.LENGTH_SHORT).show();

            // 이미지 프로세서 초기화 - 로드된 인터프리터 전달
            imageProcessor = new YoloImageProcessor(this, tfliteLoader.getTfliteInterpreter());
        } else {
            // 모델 로드 실패 시 로그 출력 및 토스트 메시지 표시
            Log.e(TAG, "YOLONas TFLite 모델 로드에 실패했습니다.");
            Toast.makeText(this, "모델 로드 실패!", Toast.LENGTH_SHORT).show();
        }

        // 이미지 선택 결과를 처리하기 위한 ActivityResultLauncher 초기화
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 결과가 OK이고 데이터가 있는 경우
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        // 선택된 이미지의 Uri 가져오기
                        Uri selectedImageUri = result.getData().getData();
                        // 선택된 이미지 처리
                        handleSelectedImage(selectedImageUri);
                    }
                });

        // 이미지 선택 버튼 클릭 이벤트 설정
        btnSelectImage.setOnClickListener(v -> {
            if (isCameraMode) {
                // 카메라 모드일 경우 카메라 중지하고 갤러리 모드로 전환
                stopCamera();
            }
            openImagePicker();
        });

        // 카메라 시작 버튼 클릭 이벤트 설정
        btnStartCamera.setOnClickListener(v -> {
            if (isCameraMode) {
                // 카메라 중지
                stopCamera();
                btnStartCamera.setText("카메라 시작");
            } else {
                // 카메라 시작
                startCamera();
                btnStartCamera.setText("카메라 중지");
            }
        });
    }

    /**
     * 필요한 권한을 확인하고 없으면 요청하는 메서드
     */
    private void checkAndRequestPermissions() {
        // 확인할 권한 배열
        String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,  // 저장소 읽기 권한
                Manifest.permission.WRITE_EXTERNAL_STORAGE, // 저장소 쓰기 권한
                Manifest.permission.CAMERA                 // 카메라 권한
        };

        // 모든 권한이 허용되었는지 확인하는 플래그
        boolean allPermissionsGranted = true;

        // 각 권한을 확인하여 하나라도 허용되지 않은 경우 플래그를 false로 설정
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        // 모든 권한이 허용되지 않은 경우 권한 요청
        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    /**
     * 권한 요청 결과를 처리하는 메서드 (콜백)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 우리가 요청한 권한에 대한 결과인지 확인
        if (requestCode == REQUEST_PERMISSIONS) {
            // 모든 권한이 허용되었는지 확인하는 플래그
            boolean allGranted = true;

            // 각 권한의 결과를 확인
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            // 하나라도 거부된 경우 사용자에게 알림
            if (!allGranted) {
                Toast.makeText(this, "앱 실행에 필요한 권한이 거부되었습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 갤러리에서 이미지를 선택하기 위한 인텐트를 실행하는 메서드
     */
    private void openImagePicker() {
        // 갤러리 열기 인텐트 생성 - ACTION_PICK 액션과 이미지 미디어 URI 사용
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        // 인텐트 실행 (결과는 imagePickerLauncher에서 처리)
        imagePickerLauncher.launch(intent);
    }

    /**
     * 선택된 이미지를 처리하는 메서드
     * @param imageUri 선택된 이미지의 URI
     */
    private void handleSelectedImage(Uri imageUri) {
        try {
            // URI에서 비트맵 이미지 로드
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
            // 이미지뷰에 선택된 이미지 표시
            imageView.setImageBitmap(bitmap);

            // 이미지뷰 표시, 텍스처뷰 숨김
            textureView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);

            // 로드된 이미지를 객체 탐지를 위해 처리
            processImage(bitmap);
        } catch (IOException e) {
            // 이미지 로드 실패 시 로그 출력 및 사용자에게 알림
            Log.e(TAG, "이미지 로드 중 오류 발생: " + e.getMessage());
            Toast.makeText(this, "이미지를 로드할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 이미지를 처리하고 객체 탐지를 수행하는 메서드
     * @param bitmap 처리할 비트맵 이미지
     */

    private void processImage(Bitmap bitmap) {
        if (imageProcessor != null) {
            tvResult.setText("분석 중...");

            new Thread(() -> {
                if (bitmap != null) {
                    // 입력 이미지를 640x640으로 리사이징
                    Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true);

                    // 객체 탐지 및 추적 수행
                    final List<YoloImageProcessor.Detection> detections = imageProcessor.processImage(resizedBitmap);
                    final List<SimpleTracker.TrackedObject> trackedObjects = tracker.update(detections);


                    final Bitmap resultBitmap = drawMultipleBoxOptions(resizedBitmap, trackedObjects);

                    // UI 스레드에서 결과 표시
                    runOnUiThread(() -> {
                        if (trackedObjects.isEmpty()) {
                            tvResult.setText("객체를 찾을 수 없습니다.");
                        } else {
                            // 결과 텍스트 구성
                            StringBuilder resultTextBuilder = new StringBuilder();
                            resultTextBuilder.append("추적 중인 객체: ").append(trackedObjects.size()).append("개\n");

                            for (SimpleTracker.TrackedObject obj : trackedObjects) {
                                if (obj.getConfidence() >= 0.7f) {
                                    resultTextBuilder.append("ID ").append(obj.getId())
                                            .append(": ").append(obj.getLabel())
                                            .append(" (").append(String.format("%.1f", obj.getConfidence() * 100))
                                            .append("%)\n");
                                }
                            }

                            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                             imageView.setImageBitmap(resultBitmap);//추가
                            tvResult.setText(resultTextBuilder.toString());
                        }

                        // 여기가 중요: imageView의 scaleType을 CENTER로 변경
                        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                        // 또는 실제 크기 그대로 표시하려면:
                        // imageView.setScaleType(ImageView.ScaleType.MATRIX);

                        // 결과 이미지 표시
                        imageView.setImageBitmap(resultBitmap);
                        imageView.setVisibility(View.VISIBLE);

                        // 갤러리 모드에서는 textureView 숨기기
                        if (!isCameraMode) {
                            textureView.setVisibility(View.GONE);
                        }

                        // 다음 프레임 처리 가능하도록 플래그 설정
                        isProcessingFrame = false;
                    });

                    // 원본 리사이즈 비트맵 해제 (결과 비트맵은 화면에 표시되므로 해제하지 않음)
                    resizedBitmap.recycle();
                }
            }).start();
        } else {
            tvResult.setText("이미지 프로세서를 초기화할 수 없습니다.");
        }
    }



//test~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~```
    private Bitmap drawMultipleBoxOptions(Bitmap bitmap, List<SimpleTracker.TrackedObject> trackedObjects) {
        // 원본 이미지를 변형하지 않기 위해 복사본 생성
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        int imageWidth = bitmap.getWidth();  // 640
        int imageHeight = bitmap.getHeight(); // 640

        // 다양한 스케일 옵션 정의
        float[] scaleOptions = {1.0f, 2.0f, 3.0f, 4.0f};

        // 각 스케일 옵션에 따른 페인트 객체 생성 (색상 구분)
        Paint[] boxPaints = new Paint[scaleOptions.length];
        for (int i = 0; i < scaleOptions.length; i++) {
            boxPaints[i] = new Paint();
            boxPaints[i].setStyle(Paint.Style.STROKE);
            boxPaints[i].setStrokeWidth(2);

            // 각 스케일 옵션별로 다른 색상 사용
            switch (i) {
                case 0: boxPaints[i].setColor(Color.RED); break;     // 스케일 1.0
                case 1: boxPaints[i].setColor(Color.GREEN); break;   // 스케일 2.0
                case 2: boxPaints[i].setColor(Color.BLUE); break;    // 스케일 3.0
                case 3: boxPaints[i].setColor(Color.YELLOW); break;  // 스케일 4.0
            }
        }

        // 텍스트 그리기 위한 Paint 객체
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(20);
        textPaint.setAntiAlias(true);

        // 텍스트 배경 Paint 객체
        Paint textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(Color.BLACK);
        textBackgroundPaint.setAlpha(180);

        // 모든 추적 객체에 대해 처리
        for (SimpleTracker.TrackedObject obj : trackedObjects) {
            if (obj.getConfidence() >= 0.7f) {
                // 원본 좌표 가져오기
                float originalX1 = obj.getLeft() / imageWidth;  // 정규화된 x1 좌표로 변환
                float originalY1 = obj.getTop() / imageHeight;  // 정규화된 y1 좌표로 변환
                float originalX2 = obj.getRight() / imageWidth; // 정규화된 x2 좌표로 변환
                float originalY2 = obj.getBottom() / imageHeight; // 정규화된 y2 좌표로 변환

                Log.d(TAG, "객체 " + obj.getId() + " 정규화된 좌표: " +
                        originalX1 + "," + originalY1 + "," + originalX2 + "," + originalY2);

                // 네 가지 서로 다른 바운딩 박스 그리기 방법 적용

                // 1. 방법 1: 직접 변환 (스케일 적용 없음)
                float left1 = obj.getLeft();
                float top1 = obj.getTop();
                float right1 = obj.getRight();
                float bottom1 = obj.getBottom();

                // 이미지 경계 내로 제한
                left1 = Math.max(0, Math.min(left1, imageWidth));
                top1 = Math.max(0, Math.min(top1, imageHeight));
                right1 = Math.max(0, Math.min(right1, imageWidth));
                bottom1 = Math.max(0, Math.min(bottom1, imageHeight));

                canvas.drawRect(left1, top1, right1, bottom1, boxPaints[0]);


                // 객체 정보 텍스트 그리기
                String labelText = "ID " + obj.getId() + ": " + obj.getLabel() +
                        " (" + String.format("%.1f", obj.getConfidence() * 100) + "%)";

                Rect textBounds = new Rect();
                textPaint.getTextBounds(labelText, 0, labelText.length(), textBounds);

                canvas.drawRect(
                        left1,
                        top1 - textBounds.height() - 5,
                        left1 + textBounds.width() + 10,
                        top1,
                        textBackgroundPaint
                );

                canvas.drawText(labelText, left1 + 5, top1 - 2, textPaint);

                // 범례 그리기 (어떤 색상이 어떤 방법인지)
                drawLegend(canvas, scaleOptions, boxPaints);
            }
        }

        return mutableBitmap;
    }

    // 범례를 그리는 메서드
    private void drawLegend(Canvas canvas, float[] scaleOptions, Paint[] paints) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(16);
        textPaint.setAntiAlias(true);

        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.BLACK);
        bgPaint.setAlpha(200);

        // 범례 배경
        canvas.drawRect(10, 10, 200, 130, bgPaint);

        // 각 방법별 설명
        String[] methods = {
                "방법 1: 직접 좌표 (빨강)",
                "방법 2: 중심점+크기 (초록)",
                "방법 3: 역변환 (파랑)",
                "방법 4: 비율유지 (노랑)"
        };

        for (int i = 0; i < methods.length; i++) {
            // 색상 샘플
            canvas.drawRect(20, 25 + i * 25, 35, 40 + i * 25, paints[i]);

            // 설명 텍스트
            canvas.drawText(methods[i], 45, 35 + i * 25, textPaint);
        }
    }

    //색깔지정
    private int getColorForId(int id) {
        int[] colors = {
                Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA
        };
        return colors[id % colors.length];
    }
    // 캔버스에 직접 바운딩 박스 그리기 메서드
    private void drawBoundingBoxesOnCanvas(Canvas canvas, List<SimpleTracker.TrackedObject> trackedObjects) {
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();


        // 바운딩 박스 그리기용 페인트 객체
        Paint boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8);

        // 텍스트 설정
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40);
        textPaint.setAntiAlias(true);

        // 텍스트 배경
        Paint textBackgroundPaint = new Paint();
        textBackgroundPaint.setAlpha(180);
//사람이랑 컵만 인식하도록********************
        for (SimpleTracker.TrackedObject obj : trackedObjects) {
            if (obj.getConfidence() >= 0.7f) {
                // 객체 ID 기반으로 색상 선택
                boxPaint.setColor(getColorForId(obj.getId()));
                textBackgroundPaint.setColor(getColorForId(obj.getId()));


                // 캔버스 크기에 맞게 변환
                float left =obj.getLeft();
                float top = obj.getTop();
                float right = obj.getRight();
                float bottom = obj.getBottom();

                // 좌표가 화면 밖으로 나가지 않도록 보정
                left = Math.max(0, Math.min(left, canvasWidth - 1));
                top = Math.max(0, Math.min(top, canvasHeight - 1));
                right = Math.max(0, Math.min(right, canvasWidth - 1));
                bottom = Math.max(0, Math.min(bottom, canvasHeight - 1));

                // 바운딩 박스 그리기
                canvas.drawRect(left, top, right, bottom, boxPaint);

                // 라벨 텍스트
                String labelText = "ID " + obj.getId() + ": " + obj.getLabel() + " " +
                        String.format("%.1f", obj.getConfidence() * 100) + "%";

                // 텍스트 크기 측정
                Rect textBounds = new Rect();
                textPaint.getTextBounds(labelText, 0, labelText.length(), textBounds);

                // 텍스트 배경 그리기
                canvas.drawRect(
                        left,
                        top - textBounds.height() - 10,
                        left + textBounds.width() + 20,
                        top,
                        textBackgroundPaint
                );

                // 텍스트 그리기
                canvas.drawText(labelText, left + 10, top - 5, textPaint);
            }
        }
    }



    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * 카메라 관련 백그라운드 스레드 중지
     */
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "백그라운드 스레드 중지 중 오류: " + e.getMessage());
            }
        }
    }

    /**
     * 카메라 시작 메서드
     */
    private void startCamera() {
        if (textureView.isAvailable()) {
            openCamera();
        }

        // UI 변경
        textureView.setVisibility(View.VISIBLE);
        overlayView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);  // 이미지뷰는 숨기기  이미지뷰는 갤러리에서 선택할때
        isCameraMode = true;
    }
    private void stopCamera() {
        closeCamera();

        // UI 변경
        textureView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        imageView.setAlpha(1.0f); // 투명도 원복
        isCameraMode = false;
    }



    /**
     * 카메라 열기
     */
    @SuppressLint("MissingPermission")
    private void openCamera() {
        startBackgroundThread();

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            // 후면 카메라 찾기
            for (String camId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(camId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = camId;

                    // 카메라 해상도 설정
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
                    }
                    break;
                }
            }

            // 카메라 열기
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("카메라 열기 시간 초과");
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "카메라 액세스 오류: " + e.getMessage());
        } catch (InterruptedException e) {
            Log.e(TAG, "카메라 열기 중 인터럽트: " + e.getMessage());
        }
    }

    /**
     * 카메라 상태 콜백
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        //카메라가 열리면 자동 호출 onOpened
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "카메라 열림");
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
        }
    };

    /**
     * 카메라 프리뷰 생성 => 카메라 화면을 textureView에 보여준다.
     */
    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                return;
            }

            // 버퍼 크기 설정
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);

            // 캡처 요청 빌더 생성
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            // 세션 생성
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        return;
                    }

                    cameraCaptureSession = session;
                    updatePreview(); //호출
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "카메라 세션 구성 실패");
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "카메라 프리뷰 생성 중 오류: " + e.getMessage());
        }
    }

    /**
     * 카메라 프리뷰 업데이트
     */
    // updatePreview 메서드 수정
    private void updatePreview() {
        if (cameraDevice == null) {
            return;
        }

        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            cameraCaptureSession.setRepeatingRequest(
                    captureRequestBuilder.build(), null, backgroundHandler);

            // 일정 간격으로 프레임 처리
            backgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    //계속해서 카메라결과를 보낸다.
                    if (isCameraMode && !isProcessingFrame) {
                        isProcessingFrame = true;

                        // 현재 프리뷰 프레임을 비트맵으로 변환하여 객체 탐지
                        runOnUiThread(() -> {
                            if (textureView.isAvailable()) {
                                Bitmap bitmap = textureView.getBitmap();
                                if (bitmap != null) {
                                    // 객체 탐지 수행 - 결과만 가져오고 따로 그리진 않음
                                    processFrameForOverlay(bitmap);
                                } else {
                                    isProcessingFrame = false;
                                }
                            } else {
                                isProcessingFrame = false;
                            }
                        });
                    }

                    // 다음 프레임 처리 예약 (더 짧은 간격으로 처리 가능)
                    if (backgroundHandler != null) {
                        backgroundHandler.postDelayed(this, 100); // 100ms 간격으로 수정
                    }
                }
            });

        } catch (CameraAccessException e) {
            Log.e(TAG, "카메라 프리뷰 업데이트 중 오류: " + e.getMessage());
        }
    }

    // 프레임 처리 및 오버레이 업데이트하는 새로운 메서드
    private void processFrameForOverlay(Bitmap bitmap) {
        if (imageProcessor != null) {
            new Thread(() -> {
                // 객체 탐지 및 추적 수행
                final List<YoloImageProcessor.Detection> detections = imageProcessor.processImage(bitmap);
                final List<SimpleTracker.TrackedObject> trackedObjects = tracker.update(detections);

                // 오버레이 업데이트 (원본 비트맵은 변경하지 않음)
                updateCameraOverlay(trackedObjects);

                // 결과 텍스트 업데이트
                runOnUiThread(() -> {
                    if (trackedObjects.isEmpty()) {
                        tvResult.setText("객체를 찾을 수 없습니다.");
                    } else {
                        // 결과 텍스트 구성
                        StringBuilder resultTextBuilder = new StringBuilder();
                        resultTextBuilder.append("추적 중인 객체: ").append(trackedObjects.size()).append("개\n");

                        for (SimpleTracker.TrackedObject obj : trackedObjects) {
                            if (obj.getConfidence() >= 0.7f) {
                                resultTextBuilder.append("ID ").append(obj.getId())
                                        .append(": ").append(obj.getLabel())
                                        .append(" (").append(String.format("%.1f", obj.getConfidence() * 100))
                                        .append("%)\n");
                            }
                        }
                        tvResult.setText(resultTextBuilder.toString());
                    }

                    // 다음 프레임 처리 가능하도록 플래그 설정
                    isProcessingFrame = false;

                    // 이미지뷰는 표시하지 않고, 텍스처뷰와 오버레이만 표시
                    textureView.setVisibility(View.VISIBLE);
                    overlayView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.GONE);
                });

                // 처리 후 비트맵 해제
                bitmap.recycle();
            }).start();
        } else {
            isProcessingFrame = false;
        }
    }

    // 오버레이 업데이트 메서드 개선
    private void updateCameraOverlay(List<SimpleTracker.TrackedObject> trackedObjects) {
        if (overlayHolder != null) {
            Canvas canvas = overlayHolder.lockCanvas();
            if (canvas != null) {
                try {
                    // 캔버스 초기화
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                    // 캔버스 크기와 객체 탐지 이미지 크기 로깅
                    int canvasWidth = canvas.getWidth();
                    int canvasHeight = canvas.getHeight();

                    Log.d(TAG, "Canvas 크기: " + canvasWidth + "x" + canvasHeight);

                    // 대각선 가상 선 그리기
                    Paint linePaint = new Paint();
                    linePaint.setColor(Color.MAGENTA);
                    linePaint.setStrokeWidth(10);
                    linePaint.setStyle(Paint.Style.STROKE);
                    linePaint.setPathEffect(new DashPathEffect(new float[] {20, 10}, 0)); // 점선 효과

                    // 640x640 좌표계에서 캔버스 좌표계로 변환
                    float scaledStartX = virtualLineStartX * canvasWidth / 640f;
                    float scaledStartY = virtualLineStartY * canvasHeight / 640f;
                    float scaledEndX = virtualLineEndX * canvasWidth / 640f;
                    float scaledEndY = virtualLineEndY * canvasHeight / 640f;

                    canvas.drawLine(scaledStartX, scaledStartY, scaledEndX, scaledEndY, linePaint);



                    // 바운딩 박스 그리기
                    drawBoundingBoxesOnCanvas(canvas, trackedObjects);


                    // 가상 선과의 교차 감지
                    detectLineCrossing(trackedObjects, canvasWidth, canvasHeight);
                } finally {
                    overlayHolder.unlockCanvasAndPost(canvas);
                }
            }
        }
    }


    // detectLineCrossing 메서드 내에서 수정
    private void detectLineCrossing(List<SimpleTracker.TrackedObject> trackedObjects, int canvasWidth, int canvasHeight) {
        long currentTime = System.currentTimeMillis();
        StringBuilder eventBuilder = new StringBuilder();
        boolean eventDetected = false;

        // 각 객체에 대해 가상 선과의 교차 확인
        for (SimpleTracker.TrackedObject obj : trackedObjects) {
            if (obj.getConfidence() >= 0.7f && "cup".equals(obj.getLabel())) {
                int objectId = obj.getId();

                // 객체의 중심점 계산 캔버스에서의위치인것
                float objectCenterX = (obj.getLeft() + obj.getRight()) / 2;
                float objectCenterY = (obj.getTop() + obj.getBottom()) / 2;

                // 640x640에서의 위치로 바꾸기
                float normalizedCenterX = objectCenterX * 640f / canvasWidth;
                float normalizedCenterY = objectCenterY * 640f / canvasHeight;

                // 선 위/아래 판단
                boolean isAbove = signedDistanceFromPointToLine(
                        normalizedCenterX, normalizedCenterY,
                        virtualLineStartX, virtualLineStartY,
                        virtualLineEndX, virtualLineEndY
                );

                //*********isAbove가 true라면 아래!!
                //*******isAbove 가 false라면 위!!!!!

                // 이전 위치 가져오기
                Boolean wasAbove = wasAboveLine.get(objectId);

                // 교차 감지: 이전 위치가 있고, 선을 건넜을 때
                if (wasAbove != null && wasAbove != isAbove) {
                    // 이전 이벤트와 충분한 시간이 지났는지 확인
                    Long lastTime = lastEventTime.get(objectId);
                    if (lastTime == null || (currentTime - lastTime) > EVENT_COOLDOWN) {
                        // 이동 방향 결정
                        String direction="";
                        String movement="";




                        //false->true 위 ->아래
                        if (wasAbove==false && isAbove==true) {
                            direction = "위에서 아래로 이동";
                            movement = " 내려놓기";
                        } else if(wasAbove==true && isAbove==false) {
                            direction = "아래에서 위로 이동";
                            movement = " 집기";
                        }

                        //가장 가까운 사람 찾기
                        //지금은 기준이!! 캔버스로!!
                        //640 640이 아니다!!
                        SimpleTracker.TrackedObject nearestPerson = findNearestPerson(trackedObjects, objectCenterX, objectCenterY);
                        String personInfo = "";
                        if(nearestPerson==null){
                            Log.d("person","사람없음");
                        }else{
                            Log.d("person",nearestPerson.getId()+"발견");
                        }


                        if (nearestPerson != null) {
                            int personId = nearestPerson.getId();
                            float personDistance = calculateDistance(
                                    objectCenterX, objectCenterY,
                                    (nearestPerson.getLeft() + nearestPerson.getRight()) / 2,
                                    (nearestPerson.getTop() + nearestPerson.getBottom()) / 2
                            );

                            // 사람 정보 추가 (10픽셀 = 약 1.5cm 가정)
                            personInfo = String.format(" - 사람 ID %d가 컵을%s (거리: %.1fpx)",
                                    personId,
                                    wasAbove == false && isAbove == true ? " 내려놓았습니다" : " 집었습니다",
                                    personDistance);
                        }

                        // 이벤트 텍스트 생성
                        eventBuilder.append("ID ").append(objectId)
                                .append(": ").append(obj.getLabel())
                                .append(" - ").append(direction)
                                .append(personInfo)
                                .append("\n");

                        // 이벤트 시간 갱신
                        lastEventTime.put(objectId, currentTime);
                        eventDetected = true;
                    }
                }

                // 현재 위치 저장
                wasAboveLine.put(objectId, isAbove);
            }
        }


        // 이벤트가 발생했으면 UI 업데이트
        if (eventDetected) {
            final String eventText = eventBuilder.toString();
            runOnUiThread(() -> {
                tvEvent.setText(eventText);
                tvEvent.setVisibility(View.VISIBLE);
                tvEvent.setBackgroundColor(Color.YELLOW);
                new Handler().postDelayed(() -> {
                    tvEvent.setBackgroundColor(Color.parseColor("#22000000"));
                }, 1000);
            });
        }

        // 오래된 객체 데이터 정리
        cleanupOldObjects(trackedObjects);
    }

    private SimpleTracker.TrackedObject findNearestPerson(List<SimpleTracker.TrackedObject> trackedObjects,
                                                          float targetX, float targetY) {
        SimpleTracker.TrackedObject nearestPerson = null;
        float minDistance = Float.MAX_VALUE;

        for (SimpleTracker.TrackedObject obj : trackedObjects) {
            // person 객체이고 신뢰도가 충분히 높은 경우만 고려
            if (obj.getConfidence() >= 0.7f && "person".equals(obj.getLabel())) {
                // 사람 객체의 중심점 계산
                float personCenterX = (obj.getLeft() + obj.getRight()) / 2;
                float personCenterY = (obj.getTop() + obj.getBottom()) / 2;

                // 두 점 사이의 거리 계산
                float distance = calculateDistance(targetX, targetY, personCenterX, personCenterY);

                // 최소 거리 업데이트
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestPerson = obj;
                }
            }
        }


        return nearestPerson;
    }

    private float calculateDistance(float x1, float y1, float x2, float y2) {
        return (float) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }




//점이 선위에있는지 아래있는지
    /**
     * 점이 선의 위에 있는지 아래에 있는지 판단하는 함수
     * @return true: 점이 선의 위에 있음, false: 점이 선의 아래에 있음
    /**
     * 점이 대각선 위에 있는지 아래에 있는지 판단 (방향 벡터 사용)
     * @return true: 위, false: 아래
     */

    /**
     * 점과 직선 사이의 부호 있는 거리 계산
     * 양수: 선의 한 쪽에 있음 (보통 "위")
     * 음수: 선의 다른 쪽에 있음 (보통 "아래")
     * 0: 선 위에 있음
     */
    private boolean signedDistanceFromPointToLine(float pointX, float pointY,
                                                float lineStartX, float lineStartY,
                                                float lineEndX, float lineEndY) {
        float lineLength = (float) Math.sqrt(
                Math.pow(lineEndX - lineStartX, 2) + Math.pow(lineEndY - lineStartY, 2)
        );

        // 직선의 두 점과 주어진 점으로 이루어진 삼각형의 면적 (부호 있음)
        float signedArea = (lineEndY - lineStartY) * pointX -
                (lineEndX - lineStartX) * pointY +
                lineEndX * lineStartY - lineEndY * lineStartX;

        // 면적을 직선의 길이로 나누면 높이(부호 있는 거리)가 됨
        float signedDistance = signedArea / lineLength;

        // 수직 거리의 절대값 (선까지의 실제 거리)
        float absDistance = Math.abs(signedDistance);

        // 상세 로그 출력
        Log.d("LineTest", String.format(
                "점(%.1f,%.1f)→선까지 거리: %.1f (절대값: %.1f), 위치: %s",
                pointX, pointY, signedDistance, absDistance,
                (signedDistance > 0 ? "아래" : "위"))
        );

        return signedDistance > 0 ? true : false;
    }
    private void cleanupOldObjects(List<SimpleTracker.TrackedObject> currentObjects) {
        // 현재 존재하는 객체 ID 집합
        Set<Integer> currentIds = new HashSet<>();
        for (SimpleTracker.TrackedObject obj : currentObjects) {
            currentIds.add(obj.getId());
        }

        // 존재하지 않는 객체의 데이터 제거
        Iterator<Map.Entry<Integer, Float>> prevDistIt = previousDistanceToLine.entrySet().iterator();
        while (prevDistIt.hasNext()) {
            if (!currentIds.contains(prevDistIt.next().getKey())) {
                prevDistIt.remove();
            }
        }

        Iterator<Map.Entry<Integer, Float>> prevXIt = previousCenterX.entrySet().iterator();
        while (prevXIt.hasNext()) {
            if (!currentIds.contains(prevXIt.next().getKey())) {
                prevXIt.remove();
            }
        }

        Iterator<Map.Entry<Integer, Float>> prevYIt = previousCenterY.entrySet().iterator();
        while (prevYIt.hasNext()) {
            if (!currentIds.contains(prevYIt.next().getKey())) {
                prevYIt.remove();
            }
        }

        Iterator<Map.Entry<Integer, Long>> timeIt = lastEventTime.entrySet().iterator();
        while (timeIt.hasNext()) {
            if (!currentIds.contains(timeIt.next().getKey())) {
                timeIt.remove();
            }
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "카메라 닫기 중 인터럽트: " + e.getMessage());
        } finally {
            cameraOpenCloseLock.release();
        }

        stopBackgroundThread();
    }

    /**
     * TextureView.SurfaceTextureListener 인터페이스 구현 메서드들
     */
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (isCameraMode) {
            openCamera();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        // 크기 변경 시 필요한 처리
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // 텍스처 업데이트 될 때마다 호출
    }

    /**
     * 액티비티 생명주기 메서드들
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (isCameraMode && textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        if (isCameraMode) {
            closeCamera();
        }
        super.onPause();
    }

    /**
     * 액티비티가 파괴될 때 호출되는 메서드
     * 리소스 해제 및 정리 작업을 수행합니다.
     */
    @Override
    protected void onDestroy() {
        // TFLite 모델 리소스 해제
        if (tfliteLoader != null) {
            tfliteLoader.close();
        }
        super.onDestroy();
    }
}