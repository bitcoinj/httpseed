#
# Copyright by the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM openjdk:8-alpine AS build-httpseed
RUN apk add --no-cache gradle && \
    adduser --system --shell /bin/false builder
USER builder
COPY --chown=builder:nogroup / /home/builder
RUN gradle --version > /dev/null && \
    gradle --build-file /home/builder/build.gradle --no-daemon --no-build-cache --no-parallel clean shadowJar

FROM openjdk:8-alpine AS run-httpseed
VOLUME /data
RUN apk add --no-cache su-exec && \
    adduser --no-create-home --system --shell /bin/false runner
COPY docker-entrypoint.sh /entrypoint.sh
COPY --from=build-httpseed /home/builder/build/libs/httpseed-all.jar /httpseed-all.jar
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
CMD ["--help"]
