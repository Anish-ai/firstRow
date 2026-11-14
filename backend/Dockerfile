# ---- Stage 1: build the jar with Maven (JDK) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy only the build files first so the dependency download is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -B package -DskipTests \
    # Split the fat jar into layers: libraries (change rarely) and our classes (change every commit).
    && java -Djarmode=tools -jar target/firstrow-backend-*.jar extract --layers --destination extracted

# ---- Stage 2: run on a slim JRE (no compiler, no Maven) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Libraries first (lib/*.jar, ~65 MB, change only when pom.xml does), then our own code
# (a ~130 KB jar whose manifest points at lib/). A code change only rebuilds/pushes the small last layer.
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/application/ ./

# Class Data Sharing (CDS) "training run": start the app once at build time, stop right after the
# Spring context is ready, and save the loaded classes to app.jsa. At runtime the JVM maps that file
# instead of finding, reading and verifying thousands of classes again -> faster cold starts.
# There is no database during the build, so everything that would connect is switched off for this
# run only (Flyway, schema validation, Hibernate's JDBC metadata lookup; so we name the dialect instead).
# It must run in THIS image: the archive only works with the exact same JVM and classpath.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off \
        -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -jar firstrow-backend-0.1.0.jar

# Run as a non-root user: if the app were compromised, the attacker isn't root in the container.
RUN addgroup -S app && adduser -S app -G app
USER app

# - Heap = 75% of the container's memory (Render free tier: 512 MB).
# - ExitOnOutOfMemoryError: if memory still runs out, exit so the platform restarts a clean container.
# - TieredStopAtLevel=1: only the quick C1 JIT compiler. Render's free tier has 0.1 CPU; measured on a
#   simulated 0.1 CPU / 512 MB container, this halved startup (329 s -> 168 s). Peak speed is a bit lower.
# - SharedArchiveFile: use the CDS archive made above.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:SharedArchiveFile=app.jsa"
EXPOSE 8080

# Used by docker compose; Render uses its own health check on /actuator/health.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/firstrow-backend-0.1.0.jar"]
