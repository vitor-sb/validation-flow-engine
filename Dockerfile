# --- Stage 1: Build ---
FROM maven:3.9.6-eclipse-temurin-17-focal AS build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src

RUN mvn package -DskipTests


# --- Stage 2: Runtime ---
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=build /app/target/*.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
