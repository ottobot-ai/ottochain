# OttoChain Full Stack - Multi-stage Docker Build
#
# Builds ALL 5 layers: GL0, GL1, ML0, CL1, DL1
# Single image ensures tessellation/metakit/metagraph compatibility.
#
# Build order (dependency chain):
#   1. Tessellation (patched) → sdk/publishLocal
#   2. Metakit (uses local tessellation) → publishLocal
#   3. OttoChain (uses local metakit + tessellation) → assembly
#
# Layers: gl0 (9000), gl1 (9100), ml0 (9200), cl1 (9300), dl1 (9400)

ARG TESSELLATION_VERSION=v4.0.0-rc.2
ARG METAKIT_VERSION=main

# ============ Stage 1: Build Tessellation ============
FROM eclipse-temurin:21-jdk AS tess-builder

ARG TESSELLATION_VERSION

RUN apt-get update && apt-get install -y curl gnupg git && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /tessellation

# Clone tessellation
RUN git clone --depth 1 --branch ${TESSELLATION_VERSION} https://github.com/Constellation-Labs/tessellation.git .

# Apply patches (type inference fix)
RUN FILE="modules/node-shared/src/main/scala/org/tessellation/node/shared/infrastructure/snapshot/GlobalSnapshotStateChannelEventsProcessor.scala" && \
    if [ -f "$FILE" ] && grep -q "def make\[F\[_\]\]" "$FILE"; then \
      sed -i 's/def make\[F\[_\]\]/def make[F[_]]: GlobalSnapshotStateChannelEventsProcessor[F]/g' "$FILE"; \
    fi

# Build and publish tessellation SDK locally + build JARs
RUN sbt sdk/publishLocal dagL0/assembly dagL1/assembly

# ============ Stage 2: Build Metakit ============
FROM eclipse-temurin:21-jdk AS metakit-builder

ARG METAKIT_VERSION

RUN apt-get update && apt-get install -y curl gnupg git && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

# Copy tessellation SDK from previous stage
COPY --from=tess-builder /root/.ivy2 /root/.ivy2
COPY --from=tess-builder /root/.sbt /root/.sbt
COPY --from=tess-builder /root/.cache /root/.cache

WORKDIR /metakit

# Clone metakit
RUN git clone --depth 1 --branch ${METAKIT_VERSION} https://github.com/Constellation-Labs/metakit.git .

# Build and publish metakit locally (uses local tessellation SDK)
RUN sbt publishLocal

# ============ Stage 3: Build OttoChain ============
FROM eclipse-temurin:21-jdk AS otto-builder

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /ottochain

# Copy ivy cache with tessellation SDK + metakit
COPY --from=metakit-builder /root/.ivy2 /root/.ivy2
COPY --from=metakit-builder /root/.sbt /root/.sbt
COPY --from=metakit-builder /root/.cache /root/.cache

# Copy build files first (better caching)
COPY build.sbt .
COPY project/ project/

# Download dependencies
RUN sbt update

# Copy source and build metagraph JARs (ML0, CL1, DL1)
COPY . .
RUN sbt "currencyL0/assembly" "currencyL1/assembly" "dataL1/assembly"

# ============ Stage 4: Runtime ============
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl jq && rm -rf /var/lib/apt/lists/*

WORKDIR /ottochain

# Create directories
RUN mkdir -p /ottochain/jars /ottochain/data /ottochain/keys /ottochain/genesis

# Copy tessellation JARs (GL0, GL1)
COPY --from=tess-builder /tessellation/modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly-*.jar /ottochain/jars/gl0.jar
COPY --from=tess-builder /tessellation/modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly-*.jar /ottochain/jars/gl1.jar

# Copy ottochain JARs (ML0, CL1, DL1)
COPY --from=otto-builder /ottochain/modules/l0/target/scala-2.13/ottochain-currency-l0-assembly-*.jar /ottochain/jars/ml0.jar
COPY --from=otto-builder /ottochain/modules/l1/target/scala-2.13/ottochain-currency-l1-assembly-*.jar /ottochain/jars/cl1.jar
COPY --from=otto-builder /ottochain/modules/data_l1/target/scala-2.13/ottochain-data-l1-assembly-*.jar /ottochain/jars/dl1.jar

# Entrypoint script
COPY docker-entrypoint.sh /ottochain/entrypoint.sh
RUN chmod +x /ottochain/entrypoint.sh

# Verify all JARs exist
RUN ls -la /ottochain/jars/ && \
    test -f /ottochain/jars/gl0.jar && \
    test -f /ottochain/jars/gl1.jar && \
    test -f /ottochain/jars/ml0.jar && \
    test -f /ottochain/jars/cl1.jar && \
    test -f /ottochain/jars/dl1.jar

# Environment defaults
ENV LAYER=ml0
ENV CL_KEYSTORE=/ottochain/keys/key.p12
ENV CL_KEYALIAS=alias
ENV CL_PASSWORD=password
ENV CL_APP_ENV=testnet
ENV JAVA_OPTS="-Xmx4g -Xms2g"

# Ports: GL0=9000, GL1=9100, ML0=9200, CL1=9300, DL1=9400
EXPOSE 9000 9100 9200 9300 9400

ENTRYPOINT ["/ottochain/entrypoint.sh"]
