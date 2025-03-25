package com.example.quantiztest;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.socket.client.IO;
import io.socket.client.Socket;


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
      // 이미지 선택 버튼
    private Button btnStartCamera;    // 카메라 시작 버튼

    //TextureView는 실시간 화면이 나오도록하는것!!
    private TextureView textureView;  // 카메라 프리뷰를 표시할 TextureView
    private ImageView imageView;      // 선택된 이미지를 표시할 ImageView
    private TextView tvResult;        // 탐지 결과를 표시할 TextView
    private SurfaceView overlayView;
    private SurfaceHolder overlayHolder;

    // 카메라 관련 변수
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);
    private boolean isProcessingFrame = false;
    private boolean isCameraMode = false;
    //ip변경부분
    final String connectUrl="https://ab03-175-214-112-154.ngrok-free.app";
    private Socket mSocket;

    // 지금까지 본 모든 사람 ID들
    private Map<Integer, Integer> personIdCountMap = new HashMap<>(); // 사람 ID와 미싱 카운트를 저장
    private static final int DISAPPEARANCE_THRESHOLD = 10; // 약 2초 (100ms 간격으로 20프레임)



    // 키오스크 영역 관련 변수 (대각선 가상 선 관련 변수 아래 부분에 추가)
    private float kioskLeft, kioskTop, kioskRight, kioskBottom; // 키오스크 영역 좌표
    private boolean showKioskArea = true; // 키오스크 영역 표시 여부


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


        // 키오스크 영역 초기화 (640x640 기준)
        kioskLeft = 30;
        kioskTop = 70;
        kioskRight = 100;
        kioskBottom = 140;


// 객체 추적 맵 초기화
        previousDistanceToLine = new HashMap<>();
        lastEventTime = new HashMap<>();



        // 웹소켓 설정
        setupSocket();

        // UI 요소 초기화 - ID로 뷰 찾기

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

//웹소켓
private void setupSocket() {
    try {
        mSocket = IO.socket(connectUrl);  // Socket.IO 서버 URL
    } catch (Exception e) {
        e.printStackTrace();
    }
    mSocket.connect();  // 연결 시작
    Log.d("socket", "웹소켓 연결시도");
    // 연결 성공 시
    mSocket.on(Socket.EVENT_CONNECT, args -> {
        Log.d("socket", "웹소켓 연결 성공");
        try {
            JSONObject connectMsg = new JSONObject();
            connectMsg.put("type", "connect");
            connectMsg.put("message", "Android app connected");
            mSocket.emit("message", connectMsg.toString());  // 서버로 메시지 전송
            //event =message이거이므로 서버에있는 @socketio.on('message')이거랑 매칭이 된다.
            Log.d("socket", "연결 메시지 전송 완료");
        } catch (Exception e) {
            Log.e("socket", "연결 메시지 전송 실패: " + e.getMessage());
        }
    });

    // 연결 종료 시
    mSocket.on(Socket.EVENT_DISCONNECT, args -> Log.d("socket", "웹소켓 연결 종료"));

    // 메시지 수신
    mSocket.on("response", args -> {
        Log.d("socket", "서버로부터 메시지 수신: " + args[0].toString());
    });


    // 가장 가까운 사람 찾기 요청 처리
    mSocket.on("find_nearest_person", args -> {
        try {
            JSONObject data = new JSONObject(args[0].toString());
            String kioskId = data.getString("kioskId");
            Log.d("socket", "키오스크 " + kioskId + "에서 가장 가까운 사람 찾기 요청 수신");

            // 현재 프레임에서 키오스크에 가장 가까운 사람 찾기 요청
            findNearestPersonToKiosk();
        } catch (Exception e) {
            Log.e("socket", "가장 가까운 사람 찾기 요청 처리 오류: " + e.getMessage());
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
    //1)이미지 전송
    private void sendImageViaWebSocket(Bitmap bitmap) {
        new Thread(() -> {
            try {
                Log.d("socket", "이미지 인코딩 시작 - 크기: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                long startTime = System.currentTimeMillis();

                // 이미지 크기 감소 (성능 향상을 위해)
                Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap,
                        bitmap.getWidth() / 2,
                        bitmap.getHeight() / 2, true);

                // JPEG으로 압축 및 Base64 인코딩
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream);
                long compressTime = System.currentTimeMillis();
                Log.d("socket", "이미지 압축 완료 - 소요시간: " + (compressTime - startTime) + "ms");

                resizedBitmap.recycle(); // 리사이즈된 비트맵 메모리 해제

                byte[] byteArray = byteArrayOutputStream.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.DEFAULT);
                long encodeTime = System.currentTimeMillis();
                Log.d("socket", "이미지 인코딩 완료 - 크기: " + byteArray.length + "바이트, 소요시간: " +
                        (encodeTime - compressTime) + "ms");

                // 이미지 데이터 JSON 구성
                JSONObject imageData = new JSONObject();
                imageData.put("type", "image");
                imageData.put("image", base64Image);
                imageData.put("timestamp", System.currentTimeMillis());

                // 이미지 데이터 전송

                mSocket.emit("message", imageData.toString());//mSocket이용하여 데이터 전송
                Log.d("socket","이미지 전송 완료");
            } catch (Exception e) {
                Log.e("socket", "웹소켓 이미지 전송 중 오류: " + e.getMessage());
                e.printStackTrace(); // 상세 스택 트레이스 출력
            }
        }).start();
    }
    //2)person이벤트전송 : (사람이 탐지될 때, 사람의 id를 전송하기)
    // 새로 등장한 사람 이벤트 전송
    private void sendPersonAppearanceEvent(Set<Integer> newPersonIds) {
        try {
            // 이벤트 데이터 JSON 구성
            JSONObject eventData = new JSONObject();
            eventData.put("type", "personAppearance");
            eventData.put("timestamp", System.currentTimeMillis());

            // 새로 등장한 사람들의 정보 배열 생성
            JSONArray personsArray = new JSONArray();
            for (Integer id : newPersonIds) {
                personsArray.put(id);  // 직접 ID 값만 추가
            }
            eventData.put("personIds", personsArray);  // 키 이름도 일관성 있게 변경
            /*
            {
                    "type": "personAppearance",
                    "timestamp": 1711012345678,
                    "personsIds": [3,5]
            }
            */
            // 이벤트 데이터 전송
            mSocket.emit("message", eventData.toString());
            Log.d("socket", "사람 등장 이벤트 전송: " + eventData.toString());

        } catch (Exception e) {
            Log.e("socket", "웹소켓 이벤트 전송 중 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 사라진 사람 이벤트 전송
    private void sendPersonDisappearanceEvent(Set<Integer> disappearedIds) {
        try {
            // 이벤트 데이터 JSON 구성
            JSONObject eventData = new JSONObject();
            eventData.put("type", "personDisappearance");
            eventData.put("timestamp", System.currentTimeMillis());

            // 사라진 사람들의 ID 배열 생성
            JSONArray idsArray = new JSONArray();
            for (Integer id : disappearedIds) {
                idsArray.put(id);
            }
            eventData.put("personIds", idsArray);

            // 이벤트 데이터 전송
            mSocket.emit("message", eventData.toString());
            Log.d("socket", "사람 사라짐 이벤트 전송: " + eventData.toString());

        } catch (Exception e) {
            Log.e("socket", "웹소켓 이벤트 전송 중 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    //3)action이벤트 전송 : (특정 사람이 무엇을 집거나/놓았을 때 전송하기)


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
                        backgroundHandler.postDelayed(this, 50); // 100ms 간격으로 수정
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

                Bitmap workingCopy = bitmap.copy(bitmap.getConfig(), true);
                final Bitmap resultBitmap = drawDetectionsDirectly(workingCopy, trackedObjects);

                sendImageViaWebSocket(resultBitmap);

                // 현재 프레임에서 감지된 사람 ID 수집
                Set<Integer> currentPersonIds = new HashSet<>();
                for (SimpleTracker.TrackedObject obj : trackedObjects) {
                    if (obj.getConfidence() >= 0.7f && "person".equals(obj.getLabel())) {
                        currentPersonIds.add(obj.getId());
                    }
                }
                // 로깅
                Log.d("person", "현재 프레임 사람들: " + currentPersonIds);
                Log.d("person", "기존 관리 중인 사람들: " + personIdCountMap.keySet());

                // 1. 현재 프레임에 있는 사람 처리
                Set<Integer> newPersons = new HashSet<>();
                for (Integer id : currentPersonIds) {
                    if (personIdCountMap.containsKey(id)) {
                        // 기존에 있던 사람은 카운트 초기화
                        personIdCountMap.put(id, 0);
                    } else {
                        // 새로 등장한 사람
                        newPersons.add(id);
                        personIdCountMap.put(id, 0);
                    }
                }

                // 새 사람 등장 이벤트 발생
                if (!newPersons.isEmpty()) {
                    sendPersonAppearanceEvent(newPersons);
                    Log.d("person", "새로 등장한 사람들: " + newPersons);
                }

                // 2. 현재 프레임에 없는 사람 처리 (카운트 증가)
                Set<Integer> missingPersons = new HashSet<>(personIdCountMap.keySet());
                missingPersons.removeAll(currentPersonIds);

                // 사라진 사람들의 카운트 증가
                for (Integer id : missingPersons) {
                    int count = personIdCountMap.get(id);
                    count++;
                    personIdCountMap.put(id, count);
                    Log.d("person", "사람 ID " + id + " 카운트 증가: " + count);
                }

                // 3. 임계값 초과한 사람 확인 (실제로 사라진 사람)
                Set<Integer> actuallyDisappeared = new HashSet<>();
                for (Map.Entry<Integer, Integer> entry : personIdCountMap.entrySet()) {
                    if (entry.getValue() >= DISAPPEARANCE_THRESHOLD) {
                        actuallyDisappeared.add(entry.getKey());
                    }
                }

                // 사라짐 이벤트 발생 및 목록에서 제거
                if (!actuallyDisappeared.isEmpty()) {
                    sendPersonDisappearanceEvent(actuallyDisappeared);
                    Log.d("person", "실제로 사라진 사람들: " + actuallyDisappeared);

                    // 사라진 사람은 목록에서 제거
                    for (Integer id : actuallyDisappeared) {
                        personIdCountMap.remove(id);
                    }
                }

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


    private Bitmap drawDetectionsDirectly(Bitmap bitmap, List<SimpleTracker.TrackedObject> trackedObjects) {
        // 원본 이미지를 변형하지 않기 위해 복사본 생성
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        // 여기서는 기존의 drawMultipleBoxOptions 메서드와 비슷한 로직 사용
        // 바운딩 박스, 텍스트 등을 그림

        // 다양한 스케일 옵션 정의
        float[] scaleOptions = {1.0f};

        // 페인트 객체 생성
        Paint boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);
        boxPaint.setColor(Color.RED);

        // 텍스트 그리기 위한 Paint 객체
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30);
        textPaint.setAntiAlias(true);

        // 텍스트 배경 Paint 객체
        Paint textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(Color.BLACK);
        textBackgroundPaint.setAlpha(180);

        // 가상 선 그리기 (옵션)
        Paint linePaint = new Paint();
        linePaint.setColor(Color.MAGENTA);
        linePaint.setStrokeWidth(5);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setPathEffect(new DashPathEffect(new float[] {20, 10}, 0)); // 점선 효과

        int imageWidth = bitmap.getWidth();
        int imageHeight = bitmap.getHeight();

        // 가상 선 그리기
        float scaledStartX = virtualLineStartX * imageWidth / 640f;
        float scaledStartY = virtualLineStartY * imageHeight / 640f;
        float scaledEndX = virtualLineEndX * imageWidth / 640f;
        float scaledEndY = virtualLineEndY * imageHeight / 640f;

        canvas.drawLine(scaledStartX, scaledStartY, scaledEndX, scaledEndY, linePaint);
        // 키오스크 영역 그리기
        if (showKioskArea) {
            Paint kioskPaint = new Paint();
            kioskPaint.setStyle(Paint.Style.STROKE);
            kioskPaint.setStrokeWidth(10);
            kioskPaint.setColor(Color.GREEN);

            // 캔버스 크기에 맞게 키오스크 좌표 변환
            float canvasKioskLeft = kioskLeft * imageWidth / 640f;
            float canvasKioskTop = kioskTop * imageHeight / 640f;
            float canvasKioskRight = kioskRight * imageWidth / 640f;
            float canvasKioskBottom = kioskBottom * imageHeight / 640f;

            // 키오스크 영역 그리기
            canvas.drawRect(canvasKioskLeft, canvasKioskTop, canvasKioskRight, canvasKioskBottom, kioskPaint);

            // 키오스크 라벨 그리기
            Paint kioskTextPaint = new Paint();
            kioskTextPaint.setColor(Color.GREEN);
            kioskTextPaint.setTextSize(40);
            kioskTextPaint.setAntiAlias(true);

            // 키오스크 라벨 배경
            Paint kioskTextBgPaint = new Paint();
            kioskTextBgPaint.setColor(Color.BLACK);
            kioskTextBgPaint.setAlpha(180);

            String kioskLabel = "키오스크";
            Rect textBounds = new Rect();
            kioskTextPaint.getTextBounds(kioskLabel, 0, kioskLabel.length(), textBounds);

            canvas.drawRect(
                    canvasKioskLeft,
                    canvasKioskTop - textBounds.height() - 10,
                    canvasKioskLeft + textBounds.width() + 20,
                    canvasKioskTop,
                    kioskTextBgPaint
            );

            canvas.drawText(kioskLabel, canvasKioskLeft + 10, canvasKioskTop - 5, kioskTextPaint);
        }



        // 모든 추적 객체에 대해 처리
        for (SimpleTracker.TrackedObject obj : trackedObjects) {
            if (obj.getConfidence() >= 0.7f) {
                // 바운딩 박스 좌표
                float left = obj.getLeft();
                float top = obj.getTop();
                float right = obj.getRight();
                float bottom = obj.getBottom();

                // 이미지 경계 내로 제한
                left = Math.max(0, Math.min(left, imageWidth));
                top = Math.max(0, Math.min(top, imageHeight));
                right = Math.max(0, Math.min(right, imageWidth));
                bottom = Math.max(0, Math.min(bottom, imageHeight));

                // ID에 따라 색상 변경
                boxPaint.setColor(getColorForId(obj.getId()));

                // 바운딩 박스 그리기
                canvas.drawRect(left, top, right, bottom, boxPaint);

                // 객체 정보 텍스트 그리기
                String labelText = "ID " + obj.getId() + ": " + obj.getLabel() +
                        " (" + String.format("%.1f", obj.getConfidence() * 100) + "%)";

                Rect textBounds = new Rect();
                textPaint.getTextBounds(labelText, 0, labelText.length(), textBounds);

                canvas.drawRect(
                        left,
                        top - textBounds.height() - 5,
                        left + textBounds.width() + 10,
                        top,
                        textBackgroundPaint
                );

                canvas.drawText(labelText, left + 5, top - 2, textPaint);
            }
        }

        return mutableBitmap;
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
                    // 키오스크 영역 그리기
                    if (showKioskArea) {
                        Paint kioskPaint = new Paint();
                        kioskPaint.setStyle(Paint.Style.STROKE);
                        kioskPaint.setStrokeWidth(10);
                        kioskPaint.setColor(Color.GREEN);

                        // 캔버스 크기에 맞게 키오스크 좌표 변환
                        float canvasKioskLeft = kioskLeft * canvasWidth / 640f;
                        float canvasKioskTop = kioskTop * canvasHeight / 640f;
                        float canvasKioskRight = kioskRight * canvasWidth / 640f;
                        float canvasKioskBottom = kioskBottom * canvasHeight / 640f;

                        // 키오스크 영역 그리기
                        canvas.drawRect(canvasKioskLeft, canvasKioskTop, canvasKioskRight, canvasKioskBottom, kioskPaint);

                        // 키오스크 라벨 그리기
                        Paint kioskTextPaint = new Paint();
                        kioskTextPaint.setColor(Color.GREEN);
                        kioskTextPaint.setTextSize(40);
                        kioskTextPaint.setAntiAlias(true);

                        // 키오스크 라벨 배경
                        Paint kioskTextBgPaint = new Paint();
                        kioskTextBgPaint.setColor(Color.BLACK);
                        kioskTextBgPaint.setAlpha(180);

                        String kioskLabel = "키오스크";
                        Rect textBounds = new Rect();
                        kioskTextPaint.getTextBounds(kioskLabel, 0, kioskLabel.length(), textBounds);

                        canvas.drawRect(
                                canvasKioskLeft,
                                canvasKioskTop - textBounds.height() - 10,
                                canvasKioskLeft + textBounds.width() + 20,
                                canvasKioskTop,
                                kioskTextBgPaint
                        );

                        canvas.drawText(kioskLabel, canvasKioskLeft + 10, canvasKioskTop - 5, kioskTextPaint);
                    }


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


                        try {
                            JSONObject actionEventData = new JSONObject();
                            actionEventData.put("type", "action");
                            actionEventData.put("personId", personId);
                            actionEventData.put("object", obj.getLabel());
                            actionEventData.put("act", wasAbove == false && isAbove == true ? 0 : 1);
                            // 0 이면 -> 내려놓기 1이면 -> 집기*****
                            mSocket.emit("message", actionEventData.toString());
                        }catch (Exception e){

                        }

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

    private void findNearestPersonToKiosk() {
        new Thread(() -> {
            try {
                // 최신 프레임을 기다림
                Thread.sleep(100);

                if (textureView.isAvailable()) {
                    runOnUiThread(() -> {
                        Bitmap bitmap = textureView.getBitmap();
                        if (bitmap != null) {
                            // 현재 추적 중인 객체 가져오기
                            List<YoloImageProcessor.Detection> detections = imageProcessor.processImage(bitmap);
                            List<SimpleTracker.TrackedObject> trackedObjects = tracker.update(detections);

                            // 키오스크 영역 중심점 계산
                            float kioskCenterX = (kioskLeft + kioskRight) / 2f;
                            float kioskCenterY = (kioskTop + kioskBottom) / 2f;

                            // 키오스크에 가장 가까운 사람 찾기
                            SimpleTracker.TrackedObject nearestPerson = findNearestPerson(trackedObjects, kioskCenterX, kioskCenterY);

                            // 결과 처리
                            if (nearestPerson != null) {
                                int personId = nearestPerson.getId();

                                // 사람과 키오스크 간의 거리 계산
                                float personCenterX = (nearestPerson.getLeft() + nearestPerson.getRight()) / 2f;
                                float personCenterY = (nearestPerson.getTop() + nearestPerson.getBottom()) / 2f;
                                float distance = calculateDistance(kioskCenterX, kioskCenterY, personCenterX, personCenterY);

                                Log.d("kiosk", "키오스크에 가장 가까운 사람 ID: " + personId + ", 거리: " + distance);

                                // 서버에 응답 전송
                                try {
                                    JSONObject responseData = new JSONObject();
                                    responseData.put("type", "nearest_person_found");
                                    responseData.put("personId", personId);
                                    responseData.put("distance", distance);

                                    mSocket.emit("nearest_person_found", responseData);

                                    // 이벤트 표시
                                    String eventText = "ID " + personId + ": 키오스크에서 가장 가까운 사람 감지";
                                    runOnUiThread(() -> {
                                        tvEvent.setText(eventText);
                                        tvEvent.setVisibility(View.VISIBLE);
                                        tvEvent.setBackgroundColor(Color.YELLOW);
                                        new Handler().postDelayed(() -> {
                                            tvEvent.setBackgroundColor(Color.parseColor("#22000000"));
                                        }, 3000);
                                    });
                                } catch (Exception e) {
                                    Log.e("kiosk", "응답 전송 오류: " + e.getMessage());
                                }
                            } else {
                                // 가까운 사람이 없는 경우
                                Log.d("kiosk", "키오스크 근처에 사람이 없습니다.");

                                // 서버에 응답 전송 (사람 없음)
                                try {
                                    JSONObject responseData = new JSONObject();
                                    responseData.put("type", "nearest_person_found");
                                    responseData.put("personId", null);
                                    responseData.put("distance", 0);

                                    mSocket.emit("nearest_person_found", responseData);
                                } catch (Exception e) {
                                    Log.e("kiosk", "응답 전송 오류: " + e.getMessage());
                                }
                            }

                            bitmap.recycle();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("kiosk", "가장 가까운 사람 찾기 오류: " + e.getMessage());
            }
        }).start();
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