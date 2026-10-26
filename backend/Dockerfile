# Backend Spring Boot: budowanie Mavenem, uruchamianie na samym JRE.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
# Zależności w osobnej warstwie: zmiana kodu nie pobiera ich od nowa.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
# Testy idą osobno w workflow (Testcontainers potrzebuje Dockera).
RUN mvn -B -q -DskipTests package && cp target/backend-*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /app/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
