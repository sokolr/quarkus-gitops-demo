FROM eclipse-temurin:21-jre

WORKDIR /work
COPY target/quarkus-app/ /work/

ENV QUARKUS_HTTP_HOST=0.0.0.0
EXPOSE 8080
USER 1001

ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
