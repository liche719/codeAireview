FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS build

WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml ./
COPY src ./src

RUN mvn -DskipTests package

# 运行阶段刻意复用构建阶段的同一个基础镜像，而不是 eclipse-temurin。
#
# 原因：服务器上 Docker Hub 必须走镜像加速器，而加速器会随缘 502
# （实测 docker.m.daocloud.io 返回 502，导致 `docker compose up --build` 直接失败）。
# mcr.microsoft.com 可直连，且这个镜像在构建阶段已经拉过 —— 运行阶段直接命中缓存，
# 整个构建就不再依赖 Docker Hub。
#
# 代价：JDK 镜像比 JRE 大约 200MB（磁盘 26GB 空闲，可接受）。
FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu

WORKDIR /app

ENV JAVA_OPTS=""

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system codepilot \
    && useradd --system --gid codepilot --home-dir /app --shell /usr/sbin/nologin codepilot

COPY --from=build /workspace/target/codepilot-ai-review-0.0.1-SNAPSHOT.jar /app/app.jar
RUN chown -R codepilot:codepilot /app

USER codepilot

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
