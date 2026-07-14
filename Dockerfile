# Build Stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/binocheck-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# Set environment defaults
ENV GITHUB_TOKEN=""
ENV GEMINI_API_KEY=""
ENTRYPOINT ["java", "-jar", "app.jar"]
