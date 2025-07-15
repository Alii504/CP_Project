# File: Final_Project/Dockerfile

# 1) Use a slim Java 21 base
FROM openjdk:21-slim

# 2) Install the X11 libs AWT/Swing needs
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxrandr2 \
    libfontconfig1 \
 && rm -rf /var/lib/apt/lists/*

# 3) Create our working dir
WORKDIR /app

# 4) Copy in your runnable JAR
COPY out/FilterApp.jar    ./FilterApp.jar

# 5) Copy in the Eclipse-exported lib folder
COPY out/FilterApp_lib/   ./FilterApp_lib/

# 6) Tell Swing where to find your host X server
ENV DISPLAY=host.docker.internal:0.0

# 7) Launch your app (Manifest’s Class-Path already points at FilterApp_lib/*.jar)
ENTRYPOINT ["java","-jar","FilterApp.jar"]
