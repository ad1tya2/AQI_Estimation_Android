package com.surendramaran.yolov8tflite

import ai.onnxruntime.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_AQI
import yolov8tflite.databinding.CollectorMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.URL
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object ThingSpeakClient {

    // private const val THINGSPEAK_URL = "https://thingspeak.mathworks.com/channels/2740981/private_show"
    private const val API_KEY = "" // Replace with your ThingSpeak Write API Key

    fun sendVehicleData(vehicleCounts: IntArray, aqival: String) {
        if (vehicleCounts.size != 5) {
            Log.e("ThingSpeak", "Error: List size must be exactly 5!")
            return
        }

        val client = OkHttpClient()
        val sumofvehicles = (vehicleCounts[0] + vehicleCounts[1] + vehicleCounts[2] + vehicleCounts[3] + vehicleCounts[4]).toString()
        // Build the form body with the vehicle counts for 5 fields
        val v1 = vehicleCounts[0].toString()
        val v2 = vehicleCounts[1].toString()
        val v3 = vehicleCounts[2].toString()
        val v4 = vehicleCounts[3].toString()
        val v5 = vehicleCounts[4].toString()
        val request = Request.Builder()
            .url("https://api.thingspeak.com/update?api_key=$API_KEY&field1=$sumofvehicles&field2=$v4&field3=$v1&field4=$v5&field5=$v2&field6=$v3&field7=$aqival")
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("ThingSpeak", "Data uploaded successfully: ${response.body?.string()}")
                } else {
                    Log.e("ThingSpeak", "Failed to upload data: ${response.message}")
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e("ThingSpeak", "Network error: ${e.message}")
            }
        }.start()
    }
}

class CollectorActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: CollectorMainBinding
    private lateinit var cameraProvider: ProcessCameraProvider
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ScheduledExecutorService
    private lateinit var detector: Detector
    private lateinit var csvWriter: FileWriter
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private var labels = mutableListOf<String>()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // val pol = StrictMode.ThreadPolicy.Builder().permitAll().build()
        // StrictMode.setThreadPolicy(pol)
        // Inflate layout
        binding = CollectorMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize detector and labels
        ortEnv = OrtEnvironment.getEnvironment()
        ortSession = ortEnv.createSession(baseContext.assets.open("aqi.onnx").readAllBytes())
        labels.addAll(MetaData.extractNamesFromLabelFile(baseContext, Constants.LABELS_PATH))
        detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this) { Log.d("Detector", it) }

        // Initialize CSV writer
        val collectedDataDir = File(filesDir, "CollectedData")
        if (!collectedDataDir.exists()) collectedDataDir.mkdirs()
        val csvFile = File(collectedDataDir, "inference_data.csv")
        csvWriter = FileWriter(csvFile, true)
        csvWriter.append("Timestamp,motorcycle,autorickshaw,car,bus,truck,predictedAqiClass,actualAqiClass,pm2.5,pm10,aqi_Cat_int,temp,absoluteHumidity,aqi_score\n") // CSV header
        csvWriter.flush()
        cameraExecutor = Executors.newSingleThreadScheduledExecutor()

        // Check permissions and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.backButton.setOnClickListener { finish() }

        // Start auto capture every 20 seconds
        startAutoCapture()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Set up preview and image capture use cases
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(filesDir, "CollectedData/${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d("CollectorActivity", "Image saved: ${photoFile.absolutePath}")
                    runInference(photoFile.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CollectorActivity", "Image capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun startAutoCapture() {
        cameraExecutor.scheduleWithFixedDelay({
            captureImage()
        }, 0, 4, TimeUnit.SECONDS)
    }

    private fun runInference(imagePath: String) {
        // Load image and pass to detector
        val bitmap = BitmapFactory.decodeFile(imagePath)
        val matrix = Matrix().apply {
            postRotate(270f)
//                postScale(
//                    -1f,
//                    1f,
//                    bitmap.width.toFloat(),
//                    bitmap.height.toFloat()
//                )

        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            matrix, true
        )
        //Log.i("Inf","${bitmap.height} ${bitmap.width} ")
        //val rotationDegrees = binding.viewFinder.display.rotation

        // Apply rotation transformation to match the display orientation
        //val matrix = Matrix().apply { postRotate(-90f) }
        //val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        detector.detect(rotatedBitmap)
    }

    override fun onEmptyDetect() {
        TODO("Not yet implemented")
    }
    private var lastDetect = 0L;
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long, vehicleCounts: IntArray) {
        Thread {
            Log.i("Info", "Inference time ${inferenceTime}")
            // changeInference time to be time between captures
            val currTime = System.currentTimeMillis()
            val infTime = currTime - lastDetect
            lastDetect = currTime

            Log.d(null, "DETECTION WORKED!!!!")
            var displayString = labels.zip(vehicleCounts.toList())
                .joinToString("\n") { "${it.first}: ${it.second}" }

            val sensorDataRaw =
                MetaData.getBlockingHttpResponse("http://" + binding.ipLine.text.toString() + "")
            Log.w("RawReq", sensorDataRaw ?: "")
            var infString = "Awaiting esp connection."
            if (sensorDataRaw != null) {
                val vals = sensorDataRaw.split(",");
                val humidity = vals[4].toFloat()
                val temp = vals[3].toFloat()
                val aqiCat = vals[2].toInt()

                val inpTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(
                        vehicleCounts.map { it.toFloat() }.toFloatArray() + floatArrayOf(
                            temp,
                            humidity,
                            Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toFloat()
                        )
                    ),
                    longArrayOf(1, 8)
                )

                // Run AQI inference

                val infOut = ortSession.run(
                    mapOf("float_input" to inpTensor),
                    setOf("output_label", "output_probability")
                )
                infString = buildInferenceString(infOut)

                infString += "\nActual Aqi: " + LABELS_AQI[aqiCat] + "\nHum: ${humidity} Temp: ${temp}"
                displayString += "\nPm2.5: ${vals[0]} Pm10: ${vals[1]}\nAqiScore: ${vals[5]}"

                val timeStamp =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        .toString()
                csvWriter.write(
                    "$timeStamp,${vehicleCounts[0]},${vehicleCounts[1]},${vehicleCounts[2]},${vehicleCounts[3]},${vehicleCounts[4]},${
                        getPredStr(
                            infOut
                        )
                    },${LABELS_AQI[aqiCat]},${sensorDataRaw}\n"
                )
                csvWriter.flush()
                Log.d(
                    "ThingSpeak",
                    "Calling sendVehicleData with: ${vehicleCounts.joinToString(", ")}"
                )
                ThingSpeakClient.sendVehicleData(vehicleCounts, vals[5])
            }
            runOnUiThread {
                binding.inferenceTime.text = "$infTime ms\n$infString"
                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }
                binding.vehicleText.text = displayString
            }
        }.start()
    }

    private fun getPredStr(infOut: OrtSession.Result): String{
        return Constants.LABELS_AQI[(infOut.get(0).value as LongArray)[0].toInt()]
    }
    private fun buildInferenceString(infOut: OrtSession.Result): String {
        var infString = ""
        var labelsx =  infOut.get(0).value as LongArray
        var percentages = (infOut.get(1)?.value) as List<OnnxMap>
        for(percents in percentages[0].getValue())
        {
            infString = infString+ percents.key.toString() + ": "+percents.value.toString()+"\n"
        }
        infString+= "Pred AQI: "+LABELS_AQI[labelsx[0].toInt()]
        return infString
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        csvWriter.close()
        detector.close()
        ortSession.close()
        ortEnv.close()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
