# AQI_Estimation_Android
## Credits
- [IoT based AQI Estimation using Image Processing](https://github.com/omi-kron/IoT-based-AQI-Estimation-using-Image-Processing-and-Learning-Methods/)
- [YoLo android base code](https://github.com/surendramaran/YOLO)
## Contributors
- [Aditya](https://github.com/ad1tya2)
- [Saketh](https://github.com/Sakii0912)
- [Ronit](https://github.com/RonitJ7)
- [Shlok](https://github.com/Geekonatrip123)

## Docs

### Building the App
- Go to app directory, open it in android studio
- It will build automatically all requesite files are already present.
- Set the API key for thingspeak in the `CollectorActivity.kt` file if you want to upload to thingspeak (7 fields required)

### Training the model 
- We have used a dataset whose format can be very easily inferred from the provided python notebook code
- However the dataset we used to train our model cannot be made public by us since it is not ours. However you can contact the authors of the aforementioned paper if you really want it. We recommend going and collecting some data yourself. It is actually possible to collect your own data via the app itself using `Collect`
- Run most of the cells till onnx
- Export to onnx as aqi.onnx

### Collecting Data via the app
- Connect the DHT11 and SDS11 sensors to the esp32. All connections are standard, can be understood via code provided. However Note that SDS11 tx,rx would be inverted to the tx,rx on esp32 i.e tx(sds) - rx(esp) , rx(sds) - tx(esp). Quite a common mistake
- Configure the ssid and password in esp32 to connect to the same local network that the mobile/QIDK is on.
- Note down the ip of the esp32. Most mobiles show it in the hotspot connected section, but incase they dont you can see the ip on the serial monitor as well
- Open the App on the device and click on `Collect` Button. Here you will see a small bar on the bottom.
- Click on the bar and type the ip of the esp32, and wait for a few seconds
- The device will automatically connect and query the esp32
- Note that if the app crashes it means that something in the esp32 is not working correctly
- To debug this particular case, you can visit the http://ip/ and then you will find some nan values, easy to fix it from there on.
- **Obtaining the data**
- To do this you can simply open the app data files in android studio and copy inference.csv. Do note that this also stores images, which you can also download from a subfolder beside the csv file.
- **Note** This has been designed for the QIDK. To run on normal mobile comment out the line postRotate(270f) in `CollectorAcivity.kt`

### Thingspeak?
Just set the api key in collectoractivity and configure 7 fields in a channel, first 6 ints, and 7th one as float
Thats it, your data will be uploaded live to thingspeak.
