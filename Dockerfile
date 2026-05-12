# syntax=docker/dockerfile:1.7

# ----------------------------------------------------------------------------
# Stage 1: build the fat jar with Maven.
# ----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy poms first for dependency-layer caching. Listing each module's pom
# separately means a change to one module's source doesn't bust the cache for
# the dependency download step.
COPY pom.xml ./
COPY cvector-core/pom.xml cvector-core/
COPY cvector-neo4j/pom.xml cvector-neo4j/
COPY cvector-rules/pom.xml cvector-rules/
COPY cvector-watcher/pom.xml cvector-watcher/
COPY cvector-cli/pom.xml cvector-cli/
COPY cvector-rest/pom.xml cvector-rest/
COPY cvector-mcp/pom.xml cvector-mcp/
COPY cvector-app/pom.xml cvector-app/
COPY cvector-parser-bicep/pom.xml cvector-parser-bicep/
COPY cvector-parser-c/pom.xml cvector-parser-c/
COPY cvector-parser-config/pom.xml cvector-parser-config/
COPY cvector-parser-cpp/pom.xml cvector-parser-cpp/
COPY cvector-parser-csharp/pom.xml cvector-parser-csharp/
COPY cvector-parser-cypher/pom.xml cvector-parser-cypher/
COPY cvector-parser-docker/pom.xml cvector-parser-docker/
COPY cvector-parser-go/pom.xml cvector-parser-go/
COPY cvector-parser-graphql/pom.xml cvector-parser-graphql/
COPY cvector-parser-hive/pom.xml cvector-parser-hive/
COPY cvector-parser-java/pom.xml cvector-parser-java/
COPY cvector-parser-javascript/pom.xml cvector-parser-javascript/
COPY cvector-parser-kotlin/pom.xml cvector-parser-kotlin/
COPY cvector-parser-php/pom.xml cvector-parser-php/
COPY cvector-parser-plsql/pom.xml cvector-parser-plsql/
COPY cvector-parser-protobuf/pom.xml cvector-parser-protobuf/
COPY cvector-parser-python/pom.xml cvector-parser-python/
COPY cvector-parser-rust/pom.xml cvector-parser-rust/
COPY cvector-parser-solidity/pom.xml cvector-parser-solidity/
COPY cvector-parser-sparql/pom.xml cvector-parser-sparql/
COPY cvector-parser-sql/pom.xml cvector-parser-sql/
COPY cvector-parser-style/pom.xml cvector-parser-style/
COPY cvector-parser-terraform/pom.xml cvector-parser-terraform/
COPY cvector-parser-ts/pom.xml cvector-parser-ts/
COPY cvector-parser-tsql/pom.xml cvector-parser-tsql/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -DskipTests dependency:go-offline

# Now copy sources and build.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -DskipTests package

# ----------------------------------------------------------------------------
# Stage 2: slim JRE runtime.
# JDK 21 enables virtual threads in MCP and dashboard modes.
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as non-root.
RUN groupadd --system --gid 1001 cvector \
    && useradd  --system --uid 1001 --gid cvector --home /home/cvector --shell /sbin/nologin cvector \
    && mkdir -p /home/cvector /workspace \
    && chown -R cvector:cvector /home/cvector /workspace

WORKDIR /workspace
USER cvector

COPY --from=build --chown=cvector:cvector /src/cvector-app/target/cvector.jar /app/cvector.jar

# Sensible defaults — override via `docker run` / docker-compose `command:`.
# The `serve` and `dashboard` modes both honour cvector_role; pass it through env.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.net.preferIPv4Stack=true" \
    CVECTOR_HOME=/workspace

# Dashboard listens on 2969; serve uses stdio (no port).
EXPOSE 2969

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/cvector.jar \"$@\"", "--"]
CMD ["doctor"]
