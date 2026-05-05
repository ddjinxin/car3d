package com.jingxin.car3d;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 悬浮窗 + OpenGL ES 渲染 3D 车模型（带纹理贴图）
 */
public class FloatingWindowService extends Service {

    private static final String TAG = "Car3D";
    private static final String CHANNEL_ID = "car3d_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View containerView;
    private WindowManager.LayoutParams params;

    // EGL
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    // OpenGL ES
    private int program;
    private int positionHandle;
    private int uvHandle;
    private int mvpMatrixHandle;
    private int textureHandle;
    private int[] textures = new int[1];

    // 模型数据
    private int vertexCount;
    private FloatBuffer vertexBuffer;
    private Bitmap baseColorBitmap;

    // 纹理视图
    private TextureView textureView;
    private int viewW = 0, viewH = 0;
    private volatile boolean surfaceReady = false;
    private boolean glInitialized = false;

    // 拖动
    private float dragStartX, dragStartY, dragInitX, dragInitY;
    private boolean dragging = false;

    // 3D 交互
    private float rotX = 0f, rotY = -30f, mScale = 1.8f;
    private float lastTX, lastTY, lastTDist;
    private int pointers = 0;

    // 自动旋转（绕Y轴，10秒一圈 = 36度/秒）
    private boolean autoRotateEnabled = false;
    private boolean autoRotatePaused = false; // 触摸时暂停
    private long lastFrameTime = 0;

    // 渲染循环
    private Choreographer choreographer;
    private volatile boolean running = false;
    private boolean settingsDialogOpen = false;

    // 双击关闭
    private long lastCloseClickTime = 0;

    // 双击中间圆点切换自动旋转
    private long lastAutoRotateClickTime = 0;

    // 模型切换
    private int currentModelIndex = 1; // 当前模型编号，默认 1.glb

    // 模型中心（用于居中显示）
    private float modelCenterX = 0.06f, modelCenterY = -0.1f, modelCenterZ = -0.01f;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        createFloatingWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ========== 通知 ==========

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "车辆3D", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("车辆3D").setContentText("3D模型展示中").setSmallIcon(android.R.drawable.ic_menu_compass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    // ========== 着色器 ==========

    // 顶点着色器：接收 position + UV，传出 UV 给片段着色器
    private static final String VERTEX_SHADER =
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMVPMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // 片段着色器：采样纹理贴图
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    vec4 texColor = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = texColor;\n" +
            "}\n";

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void initGL() {
        if (glInitialized) return;
        glInitialized = true;

        // 编译着色器
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(program));
            return;
        }

        GLES20.glUseProgram(program);

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

        // 加载 GLB 模型（含纹理 Bitmap）
        loadModelData();

        // 上传纹理到 GPU
        uploadTexture();

        // 启用深度测试
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        // 背景透明
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        Log.d(TAG, "OpenGL ES 初始化完成, program=" + program);
    }

    // ========== 模型加载（直接解析 GLB） ==========

    private void loadModelData() {
        String externalPath = "/sdcard/Download/" + currentModelIndex + ".glb";
        java.io.File externalFile = new java.io.File(externalPath);
        if (!externalFile.exists()) {
            Log.e(TAG, "模型文件不存在: " + externalPath);
            Toast.makeText(this, currentModelIndex + ".glb 不存在，请在 Download 目录放置", Toast.LENGTH_LONG).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() { public void run() { stopSelf(); } }, 5000);
            return;
        }
        try {
            GlbParser parser = new GlbParser();
            InputStream is = new java.io.FileInputStream(externalFile);
            if (!parser.parse(is)) {
                Log.e(TAG, "GLB 解析失败");
                is.close();
                Toast.makeText(this, "模型文件解析失败", Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() { public void run() { stopSelf(); } }, 5000);
                return;
            }
            is.close();

            vertexBuffer = parser.vertexBuffer;
            vertexCount = parser.vertexCount;
            baseColorBitmap = parser.baseColorBitmap;

            // 更新模型中心
            if (parser.boundsMin != null && parser.boundsMax != null) {
                modelCenterX = (parser.boundsMin[0] + parser.boundsMax[0]) / 2;
                modelCenterY = (parser.boundsMin[1] + parser.boundsMax[1]) / 2;
                modelCenterZ = (parser.boundsMin[2] + parser.boundsMax[2]) / 2;
            }

            // parser 内部的 bitmap 由我们管理纹理上传，这里不释放
            Log.d(TAG, "GLB 解析完成: " + vertexCount + " 顶点, 中心=(" +
                    modelCenterX + "," + modelCenterY + "," + modelCenterZ + ")");
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败", e);
        }
    }

    private void reloadModel() {
        // 重置 GL 状态
        program = 0;
        textures[0] = 0;
        vertexBuffer = null;
        vertexCount = 0;
        baseColorBitmap = null;
        modelCenterX = 0; modelCenterY = 0; modelCenterZ = 0;
        rotX = 0f; rotY = -30f; mScale = 1.8f;
        glInitialized = false;

        // 重新初始化 GL（会重新加载模型）
        if (eglDisplay != null && eglContext != null && eglSurface != null) {
            GLES20.glUseProgram(0);
            GLES20.glDeleteProgram(program);
            if (textures[0] != 0) {
                GLES20.glDeleteTextures(1, textures, 0);
            }
        }
        initGL();
    }

    private byte[] readAsset(String path) throws IOException {
        InputStream is = getAssets().open(path);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        while ((len = is.read(tmp)) != -1) buf.write(tmp, 0, len);
        is.close();
        return buf.toByteArray();
    }

    // ========== 纹理上传到 GPU ==========

    private void uploadTexture() {
        if (baseColorBitmap == null || baseColorBitmap.isRecycled()) {
            Log.e(TAG, "无纹理 Bitmap，跳过纹理上传");
            return;
        }
        try {
            Log.d(TAG, "纹理上传: " + baseColorBitmap.getWidth() + "x" + baseColorBitmap.getHeight());

            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, baseColorBitmap, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);

            baseColorBitmap.recycle();
            baseColorBitmap = null;

            Log.d(TAG, "纹理上传成功, texId=" + textures[0]);
        } catch (Exception e) {
            Log.e(TAG, "纹理上传失败", e);
        }
    }

    // ========== 矩阵运算 ==========

    private float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1.0f;
        return m;
    }

    private float[] perspective(float fovDeg, float aspect, float near, float far) {
        float fovRad = (float) Math.toRadians(fovDeg);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);
        float rangeInv = 1.0f / (near - far);
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (near + far) * rangeInv;
        m[11] = -1.0f;
        m[14] = 2.0f * near * far * rangeInv;
        return m;
    }

    private float[] lookAt(float eyeX, float eyeY, float eyeZ, float cX, float cY, float cZ, float upX, float upY, float upZ) {
        float fX = cX - eyeX, fY = cY - eyeY, fZ = cZ - eyeZ;
        float fLen = (float) Math.sqrt(fX * fX + fY * fY + fZ * fZ);
        fX /= fLen; fY /= fLen; fZ /= fLen;
        float sX = fY * upZ - fZ * upY;
        float sY = fZ * upX - fX * upZ;
        float sZ = fX * upY - fY * upX;
        float sLen = (float) Math.sqrt(sX * sX + sY * sY + sZ * sZ);
        sX /= sLen; sY /= sLen; sZ /= sLen;
        float uX = sY * fZ - sZ * fY;
        float uY = sZ * fX - sX * fZ;
        float uZ = sX * fY - sY * fX;
        float[] m = new float[16];
        m[0] = sX;  m[1] = uX;  m[2] = -fX; m[3] = 0;
        m[4] = sY;  m[5] = uY;  m[6] = -fY; m[7] = 0;
        m[8] = sZ;  m[9] = uZ;  m[10] = -fZ; m[11] = 0;
        m[12] = -(sX * eyeX + sY * eyeY + sZ * eyeZ);
        m[13] = -(uX * eyeX + uY * eyeY + uZ * eyeZ);
        m[14] = (fX * eyeX + fY * eyeY + fZ * eyeZ);
        m[15] = 1;
        return m;
    }

    private float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                r[i * 4 + j] = 0;
                for (int k = 0; k < 4; k++) {
                    r[i * 4 + j] += a[k * 4 + j] * b[i * 4 + k];
                }
            }
        }
        return r;
    }

    private float[] rotationMatrix(float angleXDeg, float angleYDeg, float scale) {
        float rx = (float) Math.toRadians(angleXDeg);
        float ry = (float) Math.toRadians(angleYDeg);
        float crx = (float) Math.cos(rx), srx = (float) Math.sin(rx);
        float cry = (float) Math.cos(ry), sry = (float) Math.sin(ry);

        float[] rotXMat = new float[16];
        rotXMat[0] = 1; rotXMat[5] = crx; rotXMat[6] = srx; rotXMat[9] = -srx; rotXMat[10] = crx; rotXMat[15] = 1;

        float[] rotYMat = new float[16];
        rotYMat[0] = cry; rotYMat[2] = -sry; rotYMat[5] = 1; rotYMat[8] = sry; rotYMat[10] = cry; rotYMat[15] = 1;

        float[] sc = new float[16];
        sc[0] = sc[5] = sc[10] = sc[15] = scale;

        float[] trans = identity();
        trans[12] = -modelCenterX * scale;
        trans[13] = -modelCenterY * scale;
        trans[14] = -modelCenterZ * scale;

        return multiply(multiply(rotXMat, rotYMat), multiply(sc, trans));
    }

    // ========== EGL ==========

    private boolean initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed");
            return false;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return false;
        }

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, getEGLConfig(),
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            return false;
        }

        Log.d(TAG, "EGL 初始化成功");
        return true;
    }

    private boolean createEGLSurface() {
        if (eglDisplay == null || eglContext == null) return false;
        android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) return false;

        if (eglSurface != null) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = null;
        }

        Surface surface = new Surface(st);
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay,
                getEGLConfig(), surface, new int[]{EGL14.EGL_NONE}, 0);

        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed: " + EGL14.eglGetError());
            return false;
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed: " + EGL14.eglGetError());
            return false;
        }

        Log.d(TAG, "EGL Surface 创建成功, viewport=" + viewW + "x" + viewH);
        return true;
    }

    private EGLConfig getEGLConfig() {
        int[] attribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0);
        return configs[0];
    }

    // ========== 悬浮窗 ==========

    @SuppressLint("ClickableViewAccessibility")
    private void createFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        float density = getResources().getDisplayMetrics().density;

        // 从 SharedPreferences 读取用户设置的宽高
        SharedPreferences sp = getSharedPreferences("car3d_settings", Context.MODE_PRIVATE);
        int widthDp = sp.getInt("width_dp", 300);
        int heightDp = sp.getInt("height_dp", 200);
        int wPx = (int) (widthDp * density), hPx = (int) (heightDp * density);
        int hBar = (int) (20 * density);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(wPx, hPx, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 200;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0x00000000);

        // 手柄
        FrameLayout handle = new FrameLayout(this);
        handle.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hBar));

        // 三个小圆点：左(切换模型) 中(拖动标识) 右(设置)
        LinearLayout dotBar = new LinearLayout(this);
        dotBar.setOrientation(LinearLayout.HORIZONTAL);
        dotBar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotBarP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        dotBarP.gravity = Gravity.CENTER;
        dotBar.setLayoutParams(dotBarP);

        // 左侧圆点 — 切换模型（3dp 圆点，15dp 触控区）
        View dotLeft = createDot(3, density, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = currentModelIndex + 1;
                java.io.File nextFile = new java.io.File("/sdcard/Download/" + next + ".glb");
                if (!nextFile.exists()) {
                    next = 1;
                }
                if (next != currentModelIndex) {
                    currentModelIndex = next;
                    reloadModel();
                    Toast.makeText(FloatingWindowService.this, "已切换到 " + currentModelIndex + ".glb", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(FloatingWindowService.this, "当前已是唯一模型", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dotBar.addView(dotLeft);

        // 中间间距 15dp
        View spacerMid1 = new View(this);
        spacerMid1.setLayoutParams(new LinearLayout.LayoutParams((int)(15*density), 1));
        dotBar.addView(spacerMid1);

        // 中间圆点 — 双击切换自动旋转（5dp 圆点，15dp 触控区）
        View dotCenter = createDot(5, density, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastAutoRotateClickTime < 500) {
                    // 双击切换
                    autoRotateEnabled = !autoRotateEnabled;
                    Toast.makeText(FloatingWindowService.this,
                            autoRotateEnabled ? "自动旋转已开启" : "自动旋转已关闭",
                            Toast.LENGTH_SHORT).show();
                }
                lastAutoRotateClickTime = now;
            }
        });
        dotBar.addView(dotCenter);

        // 中间间距 15dp
        View spacerMid2 = new View(this);
        spacerMid2.setLayoutParams(new LinearLayout.LayoutParams((int)(15*density), 1));
        dotBar.addView(spacerMid2);

        // 右侧圆点 — 设置（3dp 圆点，15dp 触控区）
        View dotRight = createDot(3, density, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
        dotBar.addView(dotRight);

        handle.addView(dotBar);

        handle.setOnTouchListener(handleTouchListener);
        container.addView(handle);

        // TextureView
        textureView = new TextureView(this);
        textureView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hPx - hBar));
        textureView.setOpaque(false);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                Log.d(TAG, "SurfaceTexture: " + w + "x" + h);
                viewW = w; viewH = h;
                if (!initEGL()) {
                    Log.e(TAG, "EGL init failed");
                    return;
                }
                if (!createEGLSurface()) {
                    Log.e(TAG, "EGL surface failed");
                    return;
                }
                initGL();
                surfaceReady = true;
                startRenderLoop();
            }
            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                Log.d(TAG, "resize: " + w + "x" + h);
                viewW = w; viewH = h;
                if (eglDisplay != null && eglContext != null) {
                    createEGLSurface();
                }
            }
            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                surfaceReady = false;
                running = false;
                destroyEGL();
                glInitialized = false;
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        textureView.setOnTouchListener(touch3DListener);
        container.addView(textureView);
        containerView = container;
        windowManager.addView(containerView, params);
        Log.d(TAG, "悬浮窗创建完成");
    }

    /**
     * 创建小圆点按钮
     * @param dotDp 圆点视觉直径(dp)，2或4
     * @param density 屏幕密度
     * @param clickListener 点击监听，null则不可点击（纯装饰）
     */
    private View createDot(final int dotDp, final float density, final View.OnClickListener clickListener) {
        // 15dp 触控区域
        final int touchSize = (int)(15 * density);
        // 圆点半径（像素）
        final float dotRadius = dotDp * density / 2f;

        View dot = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setAntiAlias(true);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                // 深灰填充
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(0xFF444444);
                canvas.drawCircle(cx, cy, dotRadius, paint);
                // 半透明白色描边 2dp
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setColor(0x66FFFFFF);
                paint.setStrokeWidth(2.0f * density);
                canvas.drawCircle(cx, cy, dotRadius, paint);
            }
        };
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(touchSize, touchSize);
        dot.setLayoutParams(lp);
        if (clickListener != null) {
            dot.setOnClickListener(clickListener);
        }
        return dot;
    }

    // ========== 渲染 ==========

    private void renderFrame() {
        if (!surfaceReady || eglSurface == null) return;
        if (vertexBuffer == null || program == 0) return;

        // 自动旋转：绕Y轴，36度/秒（10秒一圈）
        if (autoRotateEnabled && !autoRotatePaused) {
            long now = System.nanoTime();
            if (lastFrameTime > 0) {
                float dt = (now - lastFrameTime) / 1e9f; // 秒
                rotY += 36.0f * dt;
            }
            lastFrameTime = now;
        } else {
            lastFrameTime = 0;
        }

        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(program);

        // MVP 矩阵
        float aspect = (float) viewW / (float) Math.max(viewH, 1);
        float[] proj = perspective(45.0f, aspect, 0.01f, 100.0f);
        float[] view = lookAt(0, 0, 3.5f, 0, 0, 0, 0, 1, 0);
        float[] model = rotationMatrix(rotX, rotY, mScale);
        float[] mvp = multiply(proj, multiply(view, model));
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvp, 0);

        // 绑定纹理到纹理单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glUniform1i(textureHandle, 0);

        // 顶点数据: stride = 5 * 4 = 20 bytes (position(3) + uv(2))
        int stride = 5 * 4;
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(positionHandle);

        vertexBuffer.position(3);
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(uvHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void startRenderLoop() {
        running = true;
        choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long t) {
                if (running) {
                    renderFrame();
                    choreographer.postFrameCallback(this);
                }
            }
        });
    }

    // ========== 设置弹窗 ==========

    private void showSettingsDialog() {
        // 需要获取焦点才能输入
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(containerView, params);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        FrameLayout dialogBg = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(8 * density);
        bg.setColor(0xE0222222);
        dialogBg.setBackground(bg);
        dialogBg.setPadding(pad, pad, pad, pad);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        layout.setGravity(Gravity.CENTER);

        // 标题
        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleP.bottomMargin = (int) (12 * density);
        title.setLayoutParams(titleP);
        layout.addView(title);

        SharedPreferences sp = getSharedPreferences("car3d_settings", Context.MODE_PRIVATE);
        int currentWidth = sp.getInt("width_dp", 300);
        int currentHeight = sp.getInt("height_dp", 200);

        // 宽度
        TextView widthLabel = new TextView(this);
        widthLabel.setText("悬浮窗宽度 (dp)");
        widthLabel.setTextColor(Color.LTGRAY);
        widthLabel.setTextSize(13);
        layout.addView(widthLabel);

        android.widget.EditText widthInput = new android.widget.EditText(this);
        widthInput.setText(currentWidth + "");
        widthInput.setTextColor(Color.WHITE);
        widthInput.setTextSize(14);
        widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        widthInput.setSingleLine(true);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(4 * density);
        inputBg.setStroke(1, 0x66FFFFFF);
        inputBg.setColor(0x44444444);
        widthInput.setBackground(inputBg);
        widthInput.setPadding((int)(8*density), (int)(4*density), (int)(8*density), (int)(4*density));
        LinearLayout.LayoutParams wiP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wiP.bottomMargin = (int) (16 * density);
        widthInput.setLayoutParams(wiP);
        layout.addView(widthInput);

        // 高度
        TextView heightLabel = new TextView(this);
        heightLabel.setText("悬浮窗高度 (dp)");
        heightLabel.setTextColor(Color.LTGRAY);
        heightLabel.setTextSize(13);
        layout.addView(heightLabel);

        android.widget.EditText heightInput = new android.widget.EditText(this);
        heightInput.setText(currentHeight + "");
        heightInput.setTextColor(Color.WHITE);
        heightInput.setTextSize(14);
        heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        heightInput.setSingleLine(true);
        GradientDrawable inputBg2 = new GradientDrawable();
        inputBg2.setCornerRadius(4 * density);
        inputBg2.setStroke(1, 0x66FFFFFF);
        inputBg2.setColor(0x44444444);
        heightInput.setBackground(inputBg2);
        heightInput.setPadding((int)(8*density), (int)(4*density), (int)(8*density), (int)(4*density));
        LinearLayout.LayoutParams hiP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hiP.bottomMargin = (int) (16 * density);
        heightInput.setLayoutParams(hiP);
        layout.addView(heightInput);

        // 确定按钮
        TextView btnOk = new TextView(this);
        btnOk.setText("确定");
        btnOk.setTextColor(Color.WHITE);
        btnOk.setTextSize(15);
        btnOk.setTypeface(null, Typeface.BOLD);
        btnOk.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(6 * density);
        btnBg.setColor(0xFF3388FF);
        btnOk.setBackground(btnBg);
        btnOk.setPadding(24, 8, 24, 8);
        LinearLayout.LayoutParams btnP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnP.gravity = Gravity.CENTER;
        btnP.topMargin = (int) (4 * density);
        btnOk.setLayoutParams(btnP);
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int newW = Integer.parseInt(widthInput.getText().toString().trim());
                    int newH = Integer.parseInt(heightInput.getText().toString().trim());
                    if (newW < 100) newW = 100;
                    if (newH < 60) newH = 60;
                    if (newW > 800) newW = 800;
                    if (newH > 800) newH = 800;
                    sp.edit().putInt("width_dp", newW).putInt("height_dp", newH).apply();

                    // 恢复 FLAG_NOT_FOCUSABLE
                    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(containerView, params);

                    // 关闭弹窗
                    windowManager.removeView(dialogBg);
                    settingsDialogOpen = false;

                    // 重建悬浮窗（应用新尺寸）
                    running = false;
                    destroyEGL();
                    program = 0;
                    textures[0] = 0;
                    vertexBuffer = null;
                    vertexCount = 0;
                    glInitialized = false;
                    surfaceReady = false;
                    if (containerView != null) {
                        try { windowManager.removeView(containerView); } catch (Exception ignored) {}
                        containerView = null;
                    }
                    // 延迟重建，确保旧渲染循环和 Surface 完全销毁
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                public void run() { createFloatingWindow(); }
                            }, 200);
                } catch (Exception e) {
                    Toast.makeText(FloatingWindowService.this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(btnOk);

        dialogBg.addView(layout);
        settingsDialogOpen = true;
        windowManager.addView(dialogBg, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                params.type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT));
    }

    private void checkDoubleTapClose() {
        long now = System.currentTimeMillis();
        if (now - lastCloseClickTime < 500) {
            running = false;
            destroyEGL();
            if (containerView != null) {
                try { windowManager.removeView(containerView); } catch (Exception ignored) {}
            }
            stopForeground(true);
            stopSelf();
        } else {
            lastCloseClickTime = now;
        }
    }

    // ========== 手柄拖动 ==========

    private final View.OnTouchListener handleTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    checkDoubleTapClose();
                    dragStartX = e.getRawX(); dragStartY = e.getRawY();
                    dragInitX = params.x; dragInitY = params.y;
                    dragging = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - dragStartX, dy = e.getRawY() - dragStartY;
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragging = true;
                    if (dragging) {
                        params.x = (int)(dragInitX + dx); params.y = (int)(dragInitY + dy);
                        windowManager.updateViewLayout(containerView, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP: dragging = false; return true;
            }
            return false;
        }
    };

    // ========== 3D 触摸 ==========

    private final View.OnTouchListener touch3DListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    checkDoubleTapClose();
                    autoRotatePaused = true; // 触摸时暂停自动旋转
                    pointers = 1; lastTX = e.getX(0); lastTY = e.getY(0); return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    pointers++; if (pointers == 2) lastTDist = dist(e); return true;
                case MotionEvent.ACTION_MOVE:
                    if (pointers == 1) {
                        rotY += (e.getX(0) - lastTX) * 0.5f;
                        rotX += (e.getY(0) - lastTY) * 0.5f;
                        lastTX = e.getX(0); lastTY = e.getY(0);
                    } else if (pointers >= 2) {
                        float d = dist(e);
                        mScale *= (1.0f + (d - lastTDist) * 0.005f);
                        mScale = Math.max(0.2f, Math.min(5.0f, mScale));
                        lastTDist = d;
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP: pointers--; return true;
                case MotionEvent.ACTION_UP: autoRotatePaused = false; pointers = 0; return true;
            }
            return false;
        }
        private float dist(MotionEvent e) {
            float dx = e.getX(0)-e.getX(1), dy = e.getY(0)-e.getY(1);
            return (float)Math.sqrt(dx*dx+dy*dy);
        }
    };

    // ========== 销毁 ==========

    private void destroyEGL() {
        if (eglDisplay != null) {
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = null;
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = null;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (choreographer != null) try { choreographer.removeFrameCallback(null); } catch (Exception ignored) {}
        destroyEGL();
        if (containerView != null && windowManager != null) {
            try { windowManager.removeView(containerView); } catch (Exception ignored) {}
        }
        Log.d(TAG, "已销毁");
    }
}
