FROM ubuntu:22.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive
ENV OPENCV_VERSION=4.10.0
ENV TESSERACT_VERSION=5.5.0
ENV LEPTONICA_VERSION=1.85.0

RUN apt-get update && apt-get install -y \
    build-essential cmake git pkg-config ant default-jdk \
    libgtk-3-dev libavcodec-dev libavformat-dev libswscale-dev \
    libtbb-dev libjpeg-dev libpng-dev libtiff-dev libwebp-dev \
    libeigen3-dev libopenexr-dev libgstreamer-plugins-base1.0-dev \
    libgstreamer1.0-dev autoconf automake libtool gettext \
    zlib1g-dev curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Build Leptonica
RUN curl -L "https://github.com/DanBloomberg/leptonica/releases/download/${LEPTONICA_VERSION}/leptonica-${LEPTONICA_VERSION}.tar.gz" | tar xz && \
    cd leptonica-${LEPTONICA_VERSION} && mkdir build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
             -DBUILD_SHARED_LIBS=ON -DBUILD_PROG=OFF && \
    cmake --build . -j$(nproc) && cmake --install . && ldconfig

# Build Tesseract
RUN curl -L "https://github.com/tesseract-ocr/tesseract/archive/${TESSERACT_VERSION}.tar.gz" | tar xz && \
    cd tesseract-${TESSERACT_VERSION} && mkdir build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
             -DBUILD_SHARED_LIBS=ON -DBUILD_TRAINING_TOOLS=OFF && \
    cmake --build . -j$(nproc) && cmake --install . && ldconfig

# Build OpenCV with Java
RUN curl -L "https://github.com/opencv/opencv/archive/${OPENCV_VERSION}.tar.gz" | tar xz && \
    curl -L "https://github.com/opencv/opencv_contrib/archive/${OPENCV_VERSION}.tar.gz" | tar xz && \
    cd opencv-${OPENCV_VERSION} && mkdir build && cd build && \
    cmake .. \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
        -DBUILD_opencv_java=ON -DBUILD_FAT_JAVA_LIBS=ON \
        -DOPENCV_EXTRA_MODULES_PATH=/build/opencv_contrib-${OPENCV_VERSION}/modules \
        -DBUILD_opencv_python3=OFF -DBUILD_opencv_python2=OFF \
        -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF \
        -DBUILD_DOCS=OFF -DBUILD_opencv_apps=OFF \
        -DBUILD_opencv_highgui=OFF -DBUILD_opencv_text=ON \
        -DWITH_TESSERACT=ON -DWITH_FFMPEG=OFF -DWITH_GTK=OFF \
        -DWITH_EIGEN=ON -DWITH_TBB=ON && \
    cmake --build . -j$(nproc) && cmake --install . && ldconfig

# ============================================================
# Runtime image
# ============================================================
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && apt-get install -y \
    libtbb12 libgomp1 libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 && \
    rm -rf /var/lib/apt/lists/*

# Copy native libs from builder
COPY --from=builder /usr/local/lib/libopencv_java*.so /app/native-lib/
COPY --from=builder /usr/local/lib/libtesseract.so* /app/native-lib/
COPY --from=builder /usr/local/lib/liblept.so* /app/native-lib/

# Copy OpenCV JAR
COPY --from=builder /usr/local/share/java/opencv4/*.jar /app/libs/

ENV native.lib.path=/app/native-lib

WORKDIR /app
ENTRYPOINT ["java"]
