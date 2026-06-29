package com.oney.WebRTCModule;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Handler;
import android.util.Range;
import android.hardware.Camera;
import android.view.Surface;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.Build;

import androidx.core.util.Consumer;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.oney.WebRTCModule.videoEffects.ProcessorProvider;
import com.oney.WebRTCModule.videoEffects.VideoEffectProcessor;
import com.oney.WebRTCModule.videoEffects.VideoFrameProcessor;

import org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import org.webrtc.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The implementation of {@code getUserMedia} extracted into a separate file in
 * order to reduce complexity and to (somewhat) separate concerns.
 */
class GetUserMediaImpl {
    /**
     * The {@link Log} tag with which {@code GetUserMediaImpl} is to log.
     */
    private static final String TAG = WebRTCModule.TAG;

    private static final int PERMISSION_REQUEST_CODE = (int) (Math.random() * Short.MAX_VALUE);

    private CameraEnumerator cameraEnumerator;
    private final ReactApplicationContext reactContext;

    /**
     * The application/library-specific private members of local
     * {@link MediaStreamTrack}s created by {@code GetUserMediaImpl} mapped by
     * track ID.
     */
    private final Map<String, TrackPrivate> tracks = new HashMap<>();

    private final WebRTCModule webRTCModule;

    private Promise displayMediaPromise;
    private Intent mediaProjectionPermissionResultData;
    private boolean createConfigForDefaultDisplay = false;
    private float resolutionScale = 1.0f;

    GetUserMediaImpl(WebRTCModule webRTCModule, ReactApplicationContext reactContext) {
        this.webRTCModule = webRTCModule;
        this.reactContext = reactContext;

        reactContext.addActivityEventListener(new BaseActivityEventListener() {
            @Override
            public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
                super.onActivityResult(activity, requestCode, resultCode, data);
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    if (resultCode != Activity.RESULT_OK) {
                        displayMediaPromise.reject("DOMException", "NotAllowedError");
                        displayMediaPromise = null;
                        return;
                    }

                    mediaProjectionPermissionResultData = data;

                    ThreadUtils.runOnExecutor(() -> {
                        MediaProjectionService.launch(activity);
                        createScreenStream();
                    });
                }
            }
        });
    }

    private AudioTrack createAudioTrack(ReadableMap constraints) {
        ReadableMap audioConstraintsMap = constraints.getMap("audio");

        Log.d(TAG, "getUserMedia(audio): " + audioConstraintsMap);

        String id = UUID.randomUUID().toString();
        PeerConnectionFactory pcFactory = webRTCModule.mFactory;
        MediaConstraints peerConstraints = webRTCModule.constraintsForOptions(audioConstraintsMap);

        // PeerConnectionFactory.createAudioSource will throw an error when mandatory constraints contain nulls.
        // so, let's check for nulls
        checkMandatoryConstraints(peerConstraints);

        AudioSource audioSource = pcFactory.createAudioSource(peerConstraints);
        AudioTrack track = pcFactory.createAudioTrack(id, audioSource);

        // surfaceTextureHelper is initialized for videoTrack only, so its null here.
        tracks.put(id, new TrackPrivate(track, audioSource, /* videoCapturer */ null, /* surfaceTextureHelper */ null));

        return track;
    }

    private void checkMandatoryConstraints(MediaConstraints peerConstraints) {
        ArrayList<MediaConstraints.KeyValuePair> valid = new ArrayList<>(peerConstraints.mandatory.size());

        for (MediaConstraints.KeyValuePair constraint : peerConstraints.mandatory) {
            if (constraint.getValue() != null) {
                valid.add(constraint);
            } else {
                Log.d(TAG, String.format("constraint %s is null, ignoring it", constraint.getKey()));
            }
        }

        peerConstraints.mandatory.clear();
        peerConstraints.mandatory.addAll(valid);
    }

    private CameraEnumerator getCameraEnumerator() {
        if (cameraEnumerator == null) {
            if (Camera2Enumerator.isSupported(reactContext)) {
                Log.d(TAG, "Creating camera enumerator using the Camera2 API");
                cameraEnumerator = new Camera2Enumerator(reactContext);
            } else {
                Log.d(TAG, "Creating camera enumerator using the Camera1 API");
                cameraEnumerator = new Camera1Enumerator(false);
            }
        }

        return cameraEnumerator;
    }

    ReadableArray enumerateDevices() {
        WritableArray array = Arguments.createArray();
        String[] devices = getCameraEnumerator().getDeviceNames();

        for (int i = 0; i < devices.length; ++i) {
            String deviceName = devices[i];
            boolean isFrontFacing;
            try {
                // This can throw an exception when using the Camera 1 API.
                isFrontFacing = getCameraEnumerator().isFrontFacing(deviceName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to check the facing mode of camera");
                continue;
            }
            WritableMap params = Arguments.createMap();
            params.putString("facing", isFrontFacing ? "front" : "environment");
            params.putString("deviceId", "" + i);
            params.putString("groupId", "");
            params.putString("label", deviceName);
            params.putString("kind", "videoinput");
            array.pushMap(params);
        }

        WritableMap audio = Arguments.createMap();
        audio.putString("deviceId", "audio-1");
        audio.putString("groupId", "");
        audio.putString("label", "Audio");
        audio.putString("kind", "audioinput");
        array.pushMap(audio);

        return array;
    }

    MediaStreamTrack getTrack(String id) {
        TrackPrivate private_ = tracks.get(id);

        return private_ == null ? null : private_.track;
    }

    /**
     * Implements {@code getUserMedia}. Note that at this point constraints have
     * been normalized and permissions have been granted. The constraints only
     * contain keys for which permissions have already been granted, that is,
     * if audio permission was not granted, there will be no "audio" key in
     * the constraints map.
     */
    void getUserMedia(final ReadableMap constraints, final Callback successCallback, final Callback errorCallback) {
        AudioTrack audioTrack = null;
        VideoTrack videoTrack = null;

        if (constraints.hasKey("audio")) {
            audioTrack = createAudioTrack(constraints);
        }

        if (constraints.hasKey("video")) {
            ReadableMap videoConstraintsMap = constraints.getMap("video");

            Log.d(TAG, "getUserMedia(video): " + videoConstraintsMap);

            Activity currentActivity = this.reactContext.getCurrentActivity();
            if (currentActivity == null) {
                errorCallback.invoke("Error", "No current Activity.");
                return;
            }

            CameraCaptureController cameraCaptureController = new CameraCaptureController(
                    currentActivity, getCameraEnumerator(), videoConstraintsMap);

            videoTrack = createVideoTrack(cameraCaptureController);
        }

        if (audioTrack == null && videoTrack == null) {
            // Fail with DOMException with name AbortError as per:
            // https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-getusermedia
            errorCallback.invoke("DOMException", "AbortError");
            return;
        }

        createStream(new MediaStreamTrack[] {audioTrack, videoTrack}, (streamId, tracksInfo) -> {
            WritableArray tracksInfoWritableArray = Arguments.createArray();

            for (WritableMap trackInfo : tracksInfo) {
                tracksInfoWritableArray.pushMap(trackInfo);
            }

            successCallback.invoke(streamId, tracksInfoWritableArray);
        });
    }

    void mediaStreamTrackSetEnabled(String trackId, final boolean enabled) {
        TrackPrivate track = tracks.get(trackId);
        if (track != null && track.videoCaptureController != null) {
            if (enabled) {
                track.videoCaptureController.startCapture();
            } else {
                track.videoCaptureController.stopCapture();
            }
        }
    }

    void disposeTrack(String id) {
        TrackPrivate track = tracks.remove(id);
        if (track != null) {
            track.dispose();
        }
    }

    void applyConstraints(String trackId, ReadableMap constraints, Promise promise) {
        TrackPrivate track = tracks.get(trackId);
        if (track != null && track.videoCaptureController instanceof AbstractVideoCaptureController) {
            AbstractVideoCaptureController captureController =
                    (AbstractVideoCaptureController) track.videoCaptureController;
            captureController.applyConstraints(constraints, new Consumer<Exception>() {
                public void accept(Exception e) {
                    if (e != null) {
                        promise.reject(e);
                        return;
                    }

                    promise.resolve(captureController.getSettings());
                }
            });
        } else {
            promise.reject(new Exception("Camera track not found!"));
        }
    }

    void setZoom(String trackId, float zoomLevel, Promise promise) {
        TrackPrivate track = tracks.get(trackId);
        if (track == null || track.videoCaptureController == null) {
            promise.reject(new Exception("Video track not found!"));
            return;
        }

        if (!(track.videoCaptureController instanceof CameraCaptureController)) {
            promise.reject(new Exception("Only camera tracks support zoom!"));
            return;
        }

        CameraCaptureController controller = (CameraCaptureController) track.videoCaptureController;

        controller.setZoomCallback(newZoomLevel -> {
            try {
                setZoomInternal(track.videoCaptureController.getVideoCapturer(), newZoomLevel);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set zoom", e);
            }
        });

        controller.setZoom(zoomLevel, e -> {
            if (e != null) {
                promise.reject(e);
            } else {
                promise.resolve(controller.getSettings());
            }
        });
    }

    private void setZoomInternal(VideoCapturer videoCapturer, float zoomLevel) {
        if (videoCapturer instanceof Camera2Capturer) {
            setCamera2Zoom((Camera2Capturer) videoCapturer, zoomLevel);
        } else if (videoCapturer instanceof Camera1Capturer) {
            setCamera1Zoom((Camera1Capturer) videoCapturer, zoomLevel);
        }
    }

    private void setCamera2Zoom(Camera2Capturer capturer, float zoomLevel) {
        try {
            CameraManager cameraManager = (CameraManager) getPrivateProperty(Camera2Capturer.class, capturer, "cameraManager");

            Object session = getPrivateProperty(Camera2Capturer.class.getSuperclass(), capturer, "currentSession");
            if (session == null) {
                Log.w(TAG, "Camera2 session is null, cannot set zoom");
                return;
            }

            CameraCaptureSession captureSession = (CameraCaptureSession)
                    getPrivateProperty(session.getClass(), session, "captureSession");
            CameraDevice cameraDevice = (CameraDevice)
                    getPrivateProperty(session.getClass(), session, "cameraDevice");
            Object captureFormatObj = getPrivateProperty(session.getClass(), session, "captureFormat");
            Integer fpsUnitFactor = (Integer) getPrivateProperty(session.getClass(), session, "fpsUnitFactor");
            Surface surface = (Surface) getPrivateProperty(session.getClass(), session, "surface");
            Handler cameraThreadHandler = (Handler) getPrivateProperty(session.getClass(), session, "cameraThreadHandler");

            if (captureSession == null || cameraDevice == null || surface == null || captureFormatObj == null || fpsUnitFactor == null || cameraThreadHandler == null) {
                Log.w(TAG, "Cannot get Camera2 internal properties for zoom");
                return;
            }

            CaptureFormat captureFormat = (CaptureFormat) captureFormatObj;

            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            Rect sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

            if (sensorSize != null && maxZoom != null) {
                float desiredZoom = Math.max(1.0f, Math.min(zoomLevel, maxZoom));
                float ratio = 1.0f / desiredZoom;

                int croppedWidth = (int) ((sensorSize.width() - sensorSize.width() * ratio) / 2);
                int croppedHeight = (int) ((sensorSize.height() - sensorSize.height() * ratio) / 2);

                Rect desiredRegion = new Rect(
                        croppedWidth,
                        croppedHeight,
                        sensorSize.width() - croppedWidth,
                        sensorSize.height() - croppedHeight
                );

                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, desiredRegion);
            }

            captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new Range<>(
                            captureFormat.framerate.min / fpsUnitFactor,
                            captureFormat.framerate.max / fpsUnitFactor
                    )
            );
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            captureRequestBuilder.addTarget(surface);

            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, cameraThreadHandler);

            Log.d(TAG, "Camera2 zoom set to: " + zoomLevel);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while setting zoom", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set Camera2 zoom", e);
        }
    }

    private void setCamera1Zoom(Camera1Capturer capturer, float zoomLevel) {
        try {
            Object session = getPrivateProperty(capturer.getClass().getSuperclass(), capturer, "currentSession");
            if (session == null) {
                Log.w(TAG, "Camera1 session is null, cannot set zoom");
                return;
            }

            Camera camera = (Camera)
                    getPrivateProperty(session.getClass(), session, "camera");

            if (camera == null) {
                Log.w(TAG, "Camera1 camera is null, cannot set zoom");
                return;
            }

            Camera.Parameters params = camera.getParameters();
            if (params.isZoomSupported()) {
                int maxZoom = params.getMaxZoom();
                int desiredZoom = (int) Math.max(0, Math.min(zoomLevel, maxZoom));
                params.setZoom(desiredZoom);
                camera.setParameters(params);
                Log.d(TAG, "Camera1 zoom set to: " + desiredZoom);
            } else {
                Log.w(TAG, "Camera1 zoom not supported");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set Camera1 zoom", e);
        }
    }

    public void getMaxZoomLevel(String trackId, Promise promise) {
        TrackPrivate track = tracks.get(trackId);
        if (track == null || track.videoCaptureController == null) {
            promise.reject(new Exception("Video track not found!"));
            return;
        }

        if (!(track.videoCaptureController instanceof CameraCaptureController)) {
            promise.reject(new Exception("Only camera tracks support zoom!"));
            return;
        }

        CameraCaptureController controller = (CameraCaptureController) track.videoCaptureController;
        VideoCapturer videoCapturer = controller.getVideoCapturer();

        if (videoCapturer instanceof Camera2Capturer) {
            try {
                Camera2Capturer capturer = (Camera2Capturer) videoCapturer;
                CameraManager cameraManager = (CameraManager) reactContext.getSystemService(Context.CAMERA_SERVICE);

                Object session = getPrivateProperty(capturer.getClass().getSuperclass(), capturer, "currentSession");
                if (session == null) {
                    promise.reject(new Exception("Camera2 session is null"));
                    return;
                }

                CameraDevice cameraDevice = (CameraDevice) getPrivateProperty(session.getClass(), session, "cameraDevice");
                if (cameraDevice == null) {
                    promise.reject(new Exception("Camera2 camera device is null"));
                    return;
                }

                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
                Float maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

                if (maxZoom == null) {
                    promise.reject(new Exception("Max zoom not available"));
                    return;
                }

                promise.resolve(maxZoom.doubleValue());
            } catch (Exception e) {
                promise.reject(e);
            }
        } else if (videoCapturer instanceof Camera1Capturer) {
            try {
                Camera1Capturer capturer = (Camera1Capturer) videoCapturer;

                Object session = getPrivateProperty(capturer.getClass().getSuperclass(), capturer, "currentSession");
                if (session == null) {
                    promise.reject(new Exception("Camera1 session is null"));
                    return;
                }

                Camera camera = (Camera) getPrivateProperty(session.getClass(), session, "camera");
                if (camera == null) {
                    promise.reject(new Exception("Camera1 camera is null"));
                    return;
                }

                Camera.Parameters params = camera.getParameters();
                if (!params.isZoomSupported()) {
                    promise.reject(new Exception("Zoom not supported"));
                    return;
                }

                int maxZoom = params.getMaxZoom();
                promise.resolve((double)maxZoom);
            } catch (Exception e) {
                promise.reject(e);
            }
        } else {
            promise.reject(new Exception("Unsupported capturer type"));
        }
    }

    private Object getPrivateProperty(Class<?> clazz, Object instance, String fieldName) throws Exception {
        java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(instance);
    }

    void initializeConstraints(ReadableMap constraints) {

        // Handle the incoming params

        ReadableMap androidConstraints = null;
        if (constraints.hasKey("android") && constraints.getType("android") == ReadableType.Map) {
            androidConstraints = constraints.getMap("android");
        }

        // Default values
        boolean createConfigForDefaultDisplay = false;
        float scale = 1.0f;

        if (androidConstraints != null) {
            // MediaProjectionConfig need API level 34
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && androidConstraints.hasKey("createConfigForDefaultDisplay")
                && androidConstraints.getType("createConfigForDefaultDisplay") == ReadableType.Boolean) {
                createConfigForDefaultDisplay = androidConstraints.getBoolean("createConfigForDefaultDisplay");
            }
            if (androidConstraints.hasKey("resolutionScale")
                && androidConstraints.getType("resolutionScale") == ReadableType.Number) {
                scale = (float) androidConstraints.getDouble("resolutionScale");
            }
        }

        this.createConfigForDefaultDisplay = createConfigForDefaultDisplay;
        // Force the value in [0, 1]
        this.resolutionScale = Math.max(0.0f, Math.min(1.0f, scale));

        Log.d(TAG, "initializeConstraints: createConfigForDefaultDisplay=" + this.createConfigForDefaultDisplay
            + " resolutionScale=" + this.resolutionScale);
    }

    void getDisplayMedia(final ReadableMap constraints, Promise promise) {
        if (this.displayMediaPromise != null) {
            promise.reject(new RuntimeException("Another operation is pending."));
            return;
        }

        Activity currentActivity = this.reactContext.getCurrentActivity();
        if (currentActivity == null) {
            promise.reject(new RuntimeException("No current Activity."));
            return;
        }

        this.initializeConstraints(constraints);

        this.displayMediaPromise = promise;

        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) currentActivity.getApplication().getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);

        if (mediaProjectionManager != null) {
            UiThreadUtil.runOnUiThread(new Runnable() {
                @Override
                public void run() {

                  if (createConfigForDefaultDisplay == true) {
                        //MediaProjectionConfig need API level 34
                        //Return mediaProjection which restricts the user to capturing the default display
                        currentActivity.startActivityForResult(
                            mediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), PERMISSION_REQUEST_CODE);
                    } else {
                        //Return mediaProjection which allows the user to decide which region is captured
                        currentActivity.startActivityForResult(
                            mediaProjectionManager.createScreenCaptureIntent(), PERMISSION_REQUEST_CODE);
                    }
                }
            });

        } else {
            promise.reject(new RuntimeException("MediaProjectionManager is null."));
        }
    }

    private void createScreenStream() {
        VideoTrack track = createScreenTrack();

        if (track == null) {
            displayMediaPromise.reject(new RuntimeException("ScreenTrack is null."));
        } else {
            createStream(new MediaStreamTrack[] {track}, (streamId, tracksInfo) -> {
                WritableMap data = Arguments.createMap();

                data.putString("streamId", streamId);

                if (tracksInfo.size() == 0) {
                    displayMediaPromise.reject(new RuntimeException("No ScreenTrackInfo found."));
                } else {
                    data.putMap("track", tracksInfo.get(0));
                    displayMediaPromise.resolve(data);
                }
            });
        }

        // Cleanup
        mediaProjectionPermissionResultData = null;
        displayMediaPromise = null;
    }

    void createStream(MediaStreamTrack[] tracks, BiConsumer<String, ArrayList<WritableMap>> successCallback) {
        String streamId = UUID.randomUUID().toString();
        MediaStream mediaStream = webRTCModule.mFactory.createLocalMediaStream(streamId);

        ArrayList<WritableMap> tracksInfo = new ArrayList<>();

        for (MediaStreamTrack track : tracks) {
            if (track == null) {
                continue;
            }

            if (track instanceof AudioTrack) {
                mediaStream.addTrack((AudioTrack) track);
            } else {
                mediaStream.addTrack((VideoTrack) track);
            }

            WritableMap trackInfo = Arguments.createMap();
            String trackId = track.id();

            trackInfo.putBoolean("enabled", track.enabled());
            trackInfo.putString("id", trackId);
            trackInfo.putString("kind", track.kind());
            trackInfo.putString("readyState", "live");
            trackInfo.putBoolean("remote", false);

            if (track instanceof VideoTrack) {
                TrackPrivate tp = this.tracks.get(trackId);
                AbstractVideoCaptureController vcc = tp.videoCaptureController;
                trackInfo.putMap("settings", vcc.getSettings());
            }

            if (track instanceof AudioTrack) {
                WritableMap settings = Arguments.createMap();
                settings.putString("deviceId", "audio-1");
                settings.putString("groupId", "");
                trackInfo.putMap("settings", settings);
            }

            tracksInfo.add(trackInfo);
        }

        Log.d(TAG, "MediaStream id: " + streamId);
        webRTCModule.localStreams.put(streamId, mediaStream);

        successCallback.accept(streamId, tracksInfo);
    }

    private VideoTrack createScreenTrack() {
        DisplayMetrics displayMetrics = DisplayUtils.getDisplayMetrics(reactContext.getCurrentActivity());
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;
        ScreenCaptureController screenCaptureController = new ScreenCaptureController(
                reactContext.getCurrentActivity(), width, height, mediaProjectionPermissionResultData, resolutionScale);
        return createVideoTrack(screenCaptureController);
    }

    VideoTrack createVideoTrack(AbstractVideoCaptureController videoCaptureController) {
        videoCaptureController.initializeVideoCapturer();

        VideoCapturer videoCapturer = videoCaptureController.videoCapturer;
        if (videoCapturer == null) {
            return null;
        }

        PeerConnectionFactory pcFactory = webRTCModule.mFactory;
        EglBase.Context eglContext = EglUtils.getRootEglBaseContext();
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext);

        if (surfaceTextureHelper == null) {
            Log.d(TAG, "Error creating SurfaceTextureHelper");
            return null;
        }

        String id = UUID.randomUUID().toString();

        TrackCapturerEventsEmitter eventsEmitter = new TrackCapturerEventsEmitter(webRTCModule, id);
        videoCaptureController.setCapturerEventsListener(eventsEmitter);

        VideoSource videoSource = pcFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, reactContext, videoSource.getCapturerObserver());

        VideoTrack track = pcFactory.createVideoTrack(id, videoSource);

        track.setEnabled(true);
        tracks.put(id, new TrackPrivate(track, videoSource, videoCaptureController, surfaceTextureHelper));

        videoCaptureController.startCapture();

        return track;
    }

    /**
     * Set video effects to the TrackPrivate corresponding to the trackId with the help of VideoEffectProcessor
     * corresponding to the names.
     * @param trackId TrackPrivate id
     * @param names VideoEffectProcessor names
     */
    void setVideoEffects(String trackId, ReadableArray names) {
        TrackPrivate track = tracks.get(trackId);

        if (track != null && track.videoCaptureController instanceof CameraCaptureController) {
            VideoSource videoSource = (VideoSource) track.mediaSource;
            SurfaceTextureHelper surfaceTextureHelper = track.surfaceTextureHelper;

            if (names != null) {
                List<VideoFrameProcessor> processors =
                        names.toArrayList()
                                .stream()
                                .filter(name -> name instanceof String)
                                .map(name -> {
                                    VideoFrameProcessor videoFrameProcessor =
                                            ProcessorProvider.getProcessor((String) name);
                                    if (videoFrameProcessor == null) {
                                        Log.e(TAG, "no videoFrameProcessor associated with this name: " + name);
                                    }
                                    return videoFrameProcessor;
                                })
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                VideoEffectProcessor videoEffectProcessor = new VideoEffectProcessor(processors, surfaceTextureHelper);
                videoSource.setVideoProcessor(videoEffectProcessor);

            } else {
                videoSource.setVideoProcessor(null);
            }
        }
    }

    /**
     * Application/library-specific private members of local
     * {@code MediaStreamTrack}s created by {@code GetUserMediaImpl}.
     */
    private static class TrackPrivate {
        /**
         * The {@code MediaSource} from which {@link #track} was created.
         */
        public final MediaSource mediaSource;

        public final MediaStreamTrack track;

        /**
         * The {@code VideoCapturer} from which {@link #mediaSource} was created
         * if {@link #track} is a {@link VideoTrack}.
         */
        public final AbstractVideoCaptureController videoCaptureController;

        private final SurfaceTextureHelper surfaceTextureHelper;

        /**
         * Whether this object has been disposed or not.
         */
        private boolean disposed;

        /**
         * Initializes a new {@code TrackPrivate} instance.
         *
         * @param track
         * @param mediaSource            the {@code MediaSource} from which the specified
         *                               {@code code} was created
         * @param videoCaptureController the {@code AbstractVideoCaptureController} from which the
         *                               specified {@code mediaSource} was created if the specified
         *                               {@code track} is a {@link VideoTrack}
         */
        public TrackPrivate(MediaStreamTrack track, MediaSource mediaSource,
                AbstractVideoCaptureController videoCaptureController, SurfaceTextureHelper surfaceTextureHelper) {
            this.track = track;
            this.mediaSource = mediaSource;
            this.videoCaptureController = videoCaptureController;
            this.surfaceTextureHelper = surfaceTextureHelper;
            this.disposed = false;
        }

        public void dispose() {
            if (!disposed) {
                if (videoCaptureController != null) {
                    if (videoCaptureController.stopCapture()) {
                        videoCaptureController.dispose();
                    }
                }

                /*
                 * As per webrtc library documentation - The caller still has ownership of {@code
                 * surfaceTextureHelper} and is responsible for making sure surfaceTextureHelper.dispose() is
                 * called. This also means that the caller can reuse the SurfaceTextureHelper to initialize a new
                 * VideoCapturer once the previous VideoCapturer has been disposed. */

                if (surfaceTextureHelper != null) {
                    surfaceTextureHelper.stopListening();
                    surfaceTextureHelper.dispose();
                }

                mediaSource.dispose();
                track.dispose();
                disposed = true;
            }
        }
    }

    public interface BiConsumer<T, U> {
        void accept(T t, U u);
    }
}
