# Runtime image only: the JAR is built and tested by CI (./mvnw verify) before this step.
# Distroless Java 21 on Debian 13, non-root user, no shell or package manager in the final image.
FROM gcr.io/distroless/java21-debian13:nonroot

WORKDIR /app
COPY target/payments-intake-*.jar /app/app.jar

USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
