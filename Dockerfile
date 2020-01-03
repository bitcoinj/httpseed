FROM openjdk:8-alpine
ENV HTTP_ADDRESS localhost
ENV HTTP_PORT 8080
ENV HOSTNAME example.com
COPY build/libs/httpseed-all.jar /
VOLUME /data
WORKDIR /
CMD java \
    -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    -jar httpseed-all.jar \
    --dir=/data \
    --net=main \
    --http-address=$HTTP_ADDRESS \
    --http-port=$HTTP_PORT \
    --hostname=$HOSTNAME \
    --log-to-console \
    --crawls-per-sec=10
