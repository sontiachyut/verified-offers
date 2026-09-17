# Verified registry manifest; update deliberately and re-scan all target platforms.
FROM eclipse-temurin:21-jre-jammy@sha256:bce52ea7da1f72e6bf5bec505e63b6eb55ba79ad1226903579f77eab1a80139a
WORKDIR /app
COPY --chown=10001:10001 target/verified-offers-0.1.0-SNAPSHOT.jar /app/offers.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2 -Xms64m -Xmx384m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/offers.jar"]
