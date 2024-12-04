#include <WiFi.h>
  #include <SDS011.h>
  #include <DHT.h>
  #include <WebServer.h>  // Web server library
  #include <math.h>

  #define SECRET_SSID "aqimobile"   // Your WiFi network name
  #define SECRET_PASS "h2345671"   // Your WiFi password

  const int SDS_TX = 17;  // SDS011 RX2
  const int SDS_RX = 16;  // SDS011 TX2
  const int DHT_DATA_PIN = 23;

  DHT dht11(DHT_DATA_PIN, DHT11);
  SDS011 mySDS;
  WiFiClient client;
  WebServer server(80);  // Create a web server on port 80

  float temp, humidity;
  float pm25, pm10;  // Store current PM2.5 and PM10 readings

  unsigned long lastReadingTime = 0;  // Store the last sensor reading time
  const unsigned long readingInterval = 100;  // 0.1-second interval between readings

  int time_read = 0;
  float tempReadings[20] = {0};
  float tempAvg=0;
  float humidityReadings[20] = {0};
  float humidityAvg=0;
  int two_s = 0;
  // PM2.5 Sub-Index Calculation
float calculate_PM25_subindex(float pm25) {
    if (pm25 <= 30) return pm25 * 50 / 30;
    else if (pm25 <= 60) return 50 + (pm25 - 30) * 50 / 30;
    else if (pm25 <= 90) return 100 + (pm25 - 60) * 100 / 30;
    else if (pm25 <= 120) return 200 + (pm25 - 90) * 100 / 30;
    else if (pm25 <= 250) return 300 + (pm25 - 120) * 100 / 130;
    else return 400 + (pm25 - 250) * 100 / 130;
}

// PM10 Sub-Index Calculation
float calculate_PM10_subindex(float pm10) {
    if (pm10 <= 50) return pm10;
    else if (pm10 <= 100) return pm10;
    else if (pm10 <= 250) return 100 + (pm10 - 100) * 100 / 150;
    else if (pm10 <= 350) return 200 + (pm10 - 250);
    else if (pm10 <= 430) return 300 + (pm10 - 350) * 100 / 80;
    else return 400 + (pm10 - 430) * 100 / 80;
}

int num(float aqi){
  if(aqi<=50)return 0;
  if(aqi<=100)return 1;
  if(aqi<=200)return 2;
  if(aqi<=300)return 3;
  return 4;
}

  void setup() {
    Serial.begin(115200);  // Initialize serial monitor
    mySDS.begin(SDS_RX, SDS_TX);  // Initialize SDS011 sensor
    dht11.begin();
    // Connect to WiFi
    WiFi.mode(WIFI_STA);
    WiFi.begin(SECRET_SSID, SECRET_PASS);
    while (WiFi.status() != WL_CONNECTED) {
      Serial.print(".");
      delay(1000);
    }
    Serial.println("\nConnected to WiFi.");

    // Display the IP address
    Serial.print("IP Address: ");
    Serial.println(WiFi.localIP());

    // Initialize ThingSpeak
    // ThingSpeak.begin(client);

    // Start the web server
    server.on("/", handleRoot);  // Define the root path handler
    server.begin();
    Serial.println("Web server started.");
  }

  void loop() {
    // Check for client requests to the web server
    server.handleClient(); 
    // Check if it's time to take a new sensor reading
    if (millis() - lastReadingTime >= readingInterval) {
      float preHumid = humidityReadings[time_read],preTemp = tempReadings[time_read];
      humidityReadings[time_read] = (float)dht11.readHumidity();
      tempReadings[time_read] = (float)dht11.readTemperature();
      if(two_s){
        tempAvg  = (tempAvg*20 - preTemp + tempReadings[time_read])/(float)20;
        humidityAvg = (humidityAvg * 20 - preHumid + humidityReadings[time_read])/(float)20;
        // Serial.println("Updated avg\n");
      }else{
        if(time_read == 19){
          two_s = 1;
          for(int i = 0;i<20;i++){
            tempAvg += tempReadings[i];
            humidityAvg += humidityReadings[i];
          }
          tempAvg = tempAvg / (float)20;
          humidityAvg = humidityAvg / (float)20;
        }
      }
      time_read = (time_read+1)%20;
      lastReadingTime = millis();  // Update the last reading time
    }
  }

  // Handle root path (web server) request
  void handleRoot() {

    int error = mySDS.read(&pm25, &pm10);
    if (error == 0) {
      Serial.println("New data received from SDS011:");
      Serial.print("PM2.5: ");
      Serial.print(pm25);
      Serial.print(" µg/m3, PM10: ");
      Serial.print(pm10);
      Serial.println(" µg/m3");

    } else {
      Serial.println("Error reading from SDS011 sensor.");
    }

    float absoluteHumidity = (6.112 * exp((17.67 * tempAvg) / (tempAvg + 243.5)) * humidityAvg * 2.1674) / (273.15 + tempAvg);
    float aqi = max(calculate_PM25_subindex(pm25),calculate_PM10_subindex(pm10));
    Serial.println("Here is the aqi:");
    Serial.println(aqi);
    // Create a simple HTML response
    // String response = "<html><body>";
    String response = String(pm25) + ",";
    response += String(pm10) + ",";
    response += String(num(aqi)) + ",";
    response += String(tempAvg) + ",";
    response += String(absoluteHumidity)+",";
    response += String(aqi);
    // response += "</body></html>";

    // Send the response to the client
    server.send(200, "text/html", response);
  }
