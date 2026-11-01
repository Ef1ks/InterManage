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
# Kopia zapasowa (moduł backup): pg_dump nie starszy niż serwer bazy (produkcja: PostgreSQL 18) z repozytorium
# PGDG i gpg do szyfrowania zrzutu.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
        https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && . /etc/os-release \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-18 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /app/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
