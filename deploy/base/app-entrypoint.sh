#!/bin/sh
set -eu
umask 077
case "${1:-app}" in
  app)
    [ "$#" -le 1 ] || exit 64
    if [ "${FILE_S3_ENABLED:-false}" = true ]; then
      exec java -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError \
        -Djavax.net.ssl.trustStore=/run/secrets/S3_JAVA_TRUSTSTORE -Djavax.net.ssl.trustStoreType=JKS \
        -jar /opt/campuslife/app.jar
    fi
    exec java -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -jar /opt/campuslife/app.jar
    ;;
  admin-init)
    [ "$#" -eq 1 ] || exit 64
    export SPRING_CONFIG_IMPORT='configtree:/run/secrets/,configtree:/run/admin/'
    exec java -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError \
      -Dloader.main=com.campuslife.admin.bootstrap.AdminBootstrap \
      -cp /opt/campuslife/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  coupon-maintenance)
    [ "$#" -eq 1 ] || exit 64
    exec java -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError \
      -Dloader.main=com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap \
      -cp /opt/campuslife/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  message-maintenance)
    [ "$#" -eq 1 ] || exit 64
    # This existing plain JDBC main reads System.getenv, not Spring configtree.
    # Export only to this maintenance child; no shell tracing or credential argv.
    DB_PASSWORD=$(cat /run/secrets/DB_PASSWORD)
    export DB_PASSWORD
    exec java -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError \
      -Dloader.main=com.campuslife.messaging.bootstrap.MessageMaintenance \
      -cp /opt/campuslife/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  *) echo 'Supported entrypoints: app, admin-init, coupon-maintenance, message-maintenance' >&2; exit 64 ;;
esac
