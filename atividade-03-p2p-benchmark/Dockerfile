FROM maven:3.8.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/p2p-benchmark-1.0-SNAPSHOT.jar app.jar
CMD ["java", "-cp", "app.jar", "com.benchmark.p2p.ExecutadorBenchmark"]