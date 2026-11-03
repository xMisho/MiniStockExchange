FROM node:24-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run typecheck && npm run lint && npm test && npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src/ ./src/
COPY --from=frontend /build/frontend/dist ./frontend/dist/
COPY frontend/THIRD_PARTY_NOTICES.md ./frontend/THIRD_PARTY_NOTICES.md
RUN test -s frontend/dist/index.html && mvn -B -ntp -Pproduction -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --system verdex && useradd --system --gid verdex --home-dir /app verdex
COPY --from=backend --chown=verdex:verdex /build/target/mini-stock-exchange-1.0.0-SNAPSHOT-boot.jar /app/app.jar
USER verdex
ENV SPRING_PROFILES_ACTIVE=production
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
