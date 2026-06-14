FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ARG MODULE
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT-exec.jar /app/service.jar
USER 10001
ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-jar", "/app/service.jar"]
