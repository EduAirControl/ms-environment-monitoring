FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copiar primero solo archivos Maven
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Descargar dependencias
RUN ./mvnw dependency:go-offline

# Ahora copiar código
COPY src src

# Compilar
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre

# curl para el HEALTHCHECK y ejecutar como usuario no-root.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
RUN chown -R app:app /app

USER app

EXPOSE 3003

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:3003/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
