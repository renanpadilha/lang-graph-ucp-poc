# O jar é construído fora (CI/./mvnw package); aqui só empacotamos no JRE arm64.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/ucp-agent-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
