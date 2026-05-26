FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /work
COPY ./ /work/
RUN mvn clean package

###
FROM eclipse-temurin:17-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /usr/local/qortium /qortium && \
    chown -R 1000:100 /usr/local/qortium /qortium

COPY --from=builder /work/log4j2.properties /usr/local/qortium/
COPY --from=builder /work/target/qortium*.jar /usr/local/qortium/qortium.jar
COPY ./docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY ./docker-start.sh /usr/local/bin/docker-start.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-start.sh

USER 1000:100

EXPOSE 14891 14892 14894
HEALTHCHECK --start-period=5m CMD curl -sf http://127.0.0.1:14891/admin/info || exit 1

WORKDIR /qortium
VOLUME /qortium

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD []
