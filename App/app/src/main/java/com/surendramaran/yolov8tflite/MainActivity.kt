package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.Surface.ROTATION_90
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import yolov8tflite.R
import yolov8tflite.databinding.ActivityMainBinding
import com.surendramaran.yolov8tflite.MetaData
import com.surendramaran.yolov8tflite.MetaData.extractNamesFromLabelFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import ai.onnxruntime.*
import ai.onnxruntime.extensions.OrtxPackage
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import androidx.annotation.RequiresApi
import com.surendramaran.yolov8tflite.Constants.LABELS_AQI
import org.jetbrains.annotations.Unmodifiable
import org.tensorflow.lite.support.common.FileUtil
import java.nio.FloatBuffer
import java.nio.file.Files
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.EnumSet
import java.util.Map

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var ortEnv = OrtEnvironment.getEnvironment()
    private lateinit var ortSession: OrtSession

    private lateinit var cameraExecutor: ExecutorService
    private var labels = mutableListOf<String>()
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        Log.e("Emer", "CALLEDAGAIN!")
                binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }
        if(LABELS_PATH != null)
            labels.addAll(extractNamesFromLabelFile(baseContext, LABELS_PATH))

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        ortEnv = OrtEnvironment.getEnvironment()
        val sessionOpts = OrtSession.SessionOptions()
        sessionOpts.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        sessionOpts.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))

        ortSession = ortEnv.createSession(baseContext.assets.open("aqi.onnx").readAllBytes())
        bindListeners()

        binding.collectDataButton.setOnClickListener {
            val intent = Intent(this, CollectorActivity::class.java)
            startActivity(intent)
        }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        var rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()
            // Log.d("123","Rotatin degrees: "+imageProxy.imageInfo.rotationDegrees);
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)

        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
            
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        ortSession.close()
        ortEnv.close()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, vehicleCounts: IntArray) {
        var displayString = ""
        for(i in 0..4)
        {
            displayString = labels[i]+": "+vehicleCounts[i].toString()+"\n"+displayString
//                displayString = "\n"+displayString
        }
        var tmpString = binding.temp.text.toString()
        if(tmpString.isEmpty())
            tmpString = "0"
        var humString = binding.humidity.text.toString()
        if(humString.isEmpty())
            humString = "0"
        val inpTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatArrayOf(  vehicleCounts[0].toFloat(), vehicleCounts[1].toFloat(), vehicleCounts[2].toFloat(),
            vehicleCounts[4].toFloat(), vehicleCounts[3].toFloat(), tmpString.toFloat(), humString.toFloat(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toFloat())
            ), longArrayOf(1,8)
        )
        val infOut = ortSession.run(Collections.singletonMap("float_input", inpTensor), setOf("output_label","output_probability"))
        var infString = ""
        var labelsx =  infOut.get(0).value as LongArray
        var percentages = (infOut.get(1)?.value) as List<OnnxMap>
        for(percents in percentages[0].getValue())
        {
            infString = infString+ percents.key.toString() + ": "+percents.value.toString()+"\n"
       }
        infString+= LABELS_AQI[labelsx[0].toInt()]
        //Log.i("Infer", labelsx[0].toString() + ":" +"\n---\n"+infString)

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms\n${infString}"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
            binding.vehicleText.text = displayString
        }
    }
}
