FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f
ARG JAR_SHA256
LABEL campuslife.base.jar-sha256=$JAR_SHA256
WORKDIR /opt/campuslife
RUN groupadd --gid 10001 campuslife && useradd --uid 10001 --gid 10001 --no-create-home campuslife \
    && mkdir -p /var/lib/campuslife/files \
    && chown -R 10001:10001 /var/lib/campuslife \
    && command -v curl
COPY target/campuslife-0.0.1-SNAPSHOT.jar /opt/campuslife/app.jar
COPY deploy/base/app-entrypoint.sh deploy/base/app-health.sh /opt/campuslife/
RUN chmod 0555 /opt/campuslife/*.sh && chmod 0444 /opt/campuslife/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/campuslife/app-entrypoint.sh"]
CMD ["app"]
