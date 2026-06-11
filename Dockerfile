# Multi-stage build shared by all services; pass SERVICE as build arg.
#   docker build --build-arg SERVICE=order-service -t exchange/order-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common-events/pom.xml common-events/
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY order-service/pom.xml order-service/
COPY matching-engine-service/pom.xml matching-engine-service/
COPY portfolio-service/pom.xml portfolio-service/
COPY market-data-service/pom.xml market-data-service/
RUN mvn -q -B dependency:go-offline -DskipTests || true
COPY . .
ARG SERVICE
RUN mvn -q -B -pl ${SERVICE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/target/*.jar app.jar
# Container-aware JVM; tune heap for the engine via JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -jar app.jar"]
