FilterApp

A Java-based image/video filtering application supporting both sequential and parallel processing.

---

Table of Contents

* Features
* Prerequisites
* Installation

  * Undockerized
  * Dockerized
* Usage

  * Undockerized
  * Dockerized
* Troubleshooting
* Contributing
* License

---

Features

* Apply various filters (e.g., Gaussian blur, edge detection)
* Compare sequential vs. parallel performance
* Simple Swing-based GUI for selecting and previewing images/videos

---

Prerequisites

* Java 8+ (JDK installed and `java` on your PATH)
* Maven (if building from source)
* Docker (optional, only for Dockerized workflow)
* X11 server

  * Linux/macOS: XQuartz or native X11
  * Windows: e.g., Xming or VcXsrv, with `DISPLAY` environment variable set
* Libraries & Sample Images
  The required libraries (JAR dependencies) and sample images are hosted on Google Drive.
  To request access or if you have any issues, please contact: [alali.413121@gmail.com](mailto:your.email@domain.com)

---

Installation

Undockerized

1. Clone the repo
   git clone
   cd filterapp
2. Build
   mvn clean package
   This produces `target/filterapp.jar`.

Dockerized

1. Clone the repo
   git clone
   cd filterapp
2. Build Docker image
   docker build -t myteam/filterapp\:latest .

---

Usage

Undockerized

1. Ensure your X11 server is running

   * On Windows, start Xming/VcXsrv and allow connections.
   * On Linux/macOS, ensure `DISPLAY` is set:
     export DISPLAY=:0
     xhost +  (if needed)
2. Run the JAR
   java -jar target/filterapp.jar
3. Select an image or video via the GUI and choose sequential or parallel processing.

Dockerized

Linux / macOS

docker run --rm&#x20;
-e DISPLAY=\$DISPLAY&#x20;
-v /tmp/.X11-unix:/tmp/.X11-unix&#x20;
-v "\$(pwd)":/project&#x20;
myteam/filterapp\:latest

Windows (PowerShell)

1. Start your X server (e.g., Xming).

2. In PowerShell:

   docker run --rm `  --add-host=host.docker.internal:host-gateway`
   -e DISPLAY=\$Env\:DISPLAY `  -v "C:/path/to/filterapp":/project`
   myteam/filterapp\:latest

3. The GUI should open through your X server.

---

Troubleshooting

* Can’t connect to X11 window server

  * Ensure your X server is running and listening on the correct display.
  * On Windows, verify `DISPLAY` matches your X server setting (usually `host.docker.internal:0`).
  * On Linux/macOS, you may need `xhost +` to allow connections.

* Invalid Docker reference format

  * Check that your volume paths use forward slashes or proper quoting.
  * Example Windows path: `-v "C:/path/to/project":/project`

---

Contributing

1. Fork the repo
2. Create a feature branch (`git checkout -b feat/YourFeature`)
3. Commit your changes
4. Push to your fork (`git push origin feat/YourFeature`)
5. Open a Pull Request

Please adhere to the existing code style and include tests where appropriate.

---

License

This project is licensed under the MIT License. See LICENSE for details.
