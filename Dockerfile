FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS build

WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml ./
COPY src ./src

RUN mvn -DskipTests package

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu

WORKDIR /app

ENV JAVA_OPTS=""

COPY --from=build /workspace/target/codepilot-ai-review-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
