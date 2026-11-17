# ==========================================================================
# STAGE 1 - BUILDER: scarica Android SDK e compila APK + JAR desktop.
# Gira sul TUO server, dove dl.google.com e raggiungibile (in cloud no).
# ==========================================================================
FROM eclipse-temurin:17-jdk AS builder

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV DEBIAN_FRONTEND=noninteractive

# strumenti base
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git && \
    rm -rf /var/lib/apt/lists/*

# Android command line tools
ARG CMDTOOLS_VERSION=11076708
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -fsSL -o /tmp/cmdtools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDTOOLS_VERSION}_latest.zip" && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"

# licenze + componenti (versioni allineate a android/build.gradle.kts)
RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" >/dev/null

WORKDIR /src
COPY . .

# compila APK debug e JAR desktop in un colpo solo
ARG APP_VERSION=0.1.0
RUN chmod +x gradlew && \
    EXTRA="" && \
    if [ -f docker/keystore/release.p12 ]; then \
      EXTRA="-PksPath=/src/docker/keystore/release.p12 -PksPass=$(cat docker/keystore/ks.pass)"; \
    fi && \
    ./gradlew --no-daemon -PappVersion=${APP_VERSION} ${EXTRA} :android:assembleDebug :desktop:fatJar

# ==========================================================================
# STAGE 2 - WEB: nginx serve la pagina; i file compilati arrivano dal volume releases.
# ==========================================================================
FROM nginx:1.27-alpine AS web

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/web/ /usr/share/nginx/html/
COPY INSTALLA.md /usr/share/nginx/html/INSTALLA.md

# /usr/share/nginx/html/downloads arriva da un volume (docker/releases):
# APK e JAR li pubblica docker/rilascia.sh, non finiscono nell'immagine.

EXPOSE 80
