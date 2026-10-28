FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests -Dspotless.check.skip=true package \
    && java -Djarmode=tools -jar target/ativacao-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre
# O pebble da imagem base não é usado pela aplicação e traz CVEs barradas pelo Trivy.
RUN rm -f /usr/bin/pebble
WORKDIR /app
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
