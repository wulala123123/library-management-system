FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="library-team"

WORKDIR /app

RUN apk add --no-cache fontconfig ttf-dejavu \
    && addgroup -S library && adduser -S -G library -u 10001 library \
    && mkdir -p /app/uploads /app/logs && chown -R library:library /app
COPY --chown=library:library target/library-backend.jar app.jar

ENV JAVA_OPTS="-Xms128m -Xmx384m -Djava.awt.headless=true"
ENV SPRING_PROFILES_ACTIVE=prod
ENV MYSQL_HOST=localhost
ENV MYSQL_PORT=3306
ENV MYSQL_DB=library_db
ENV MYSQL_USER=
ENV MYSQL_PASSWORD=

USER library

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}"]
