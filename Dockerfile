# Multi-stage build shared by both deployables.
# Select which one to produce with:  --build-arg MODULE=eventrelay-api|eventrelay-dispatcher

# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy only POMs first so dependency resolution is cached across source changes.
COPY pom.xml ./
COPY eventrelay-common/pom.xml eventrelay-common/
COPY eventrelay-core/pom.xml eventrelay-core/
COPY eventrelay-api/pom.xml eventrelay-api/
COPY eventrelay-dispatcher/pom.xml eventrelay-dispatcher/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY . .
RUN mvn -B -DskipTests package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime
ARG MODULE
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -m eventrelay

COPY --from=build /build/${MODULE}/target/${MODULE}.jar /app/app.jar
RUN chown -R eventrelay:eventrelay /app
USER eventrelay

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
