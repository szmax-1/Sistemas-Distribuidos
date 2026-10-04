FROM maven:3.8.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/rabbitmq-imagens-1.0-SNAPSHOT.jar app.jar

# Define a base para podermos executar qualquer uma das classes no docker-compose
ENTRYPOINT ["java", "-cp", "app.jar"]