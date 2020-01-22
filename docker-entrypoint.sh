#!/bin/sh
set -e

chown -R runner /data
exec su-exec runner \
  java -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    -jar /httpseed-all.jar --dir=/data --log-to-console \
    "$@"
