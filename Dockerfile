FROM mirror.gcr.io/library/ubuntu:22.04 AS cpp-builder
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    zlib1g-dev \
    libxxhash-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build/native
COPY native/ .
RUN mkdir build && cd build && cmake -DCMAKE_BUILD_TYPE=Release .. && make

FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS java-builder
WORKDIR /build/backend
COPY backend/pom.xml .
RUN mvn dependency:go-offline

COPY backend/src ./src
RUN mvn clean package -DskipTests

FROM mirror.gcr.io/library/eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y zlib1g && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=cpp-builder /build/native/build/libdna_hasher.so /usr/lib/libdna_hasher.so

COPY --from=java-builder /build/backend/target/*.jar app.jar

EXPOSE 8080

RUN groupadd -r exec && useradd -r -g exec user
USER user

ENTRYPOINT ["java", "-jar", "app.jar"]