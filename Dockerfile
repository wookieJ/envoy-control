FROM gradle:5.6.2-jdk11 AS build

COPY --chown=gradle:gradle settings.gradle build.gradle /home/gradle/src/
COPY --chown=gradle:gradle envoy-control-core/ /home/gradle/src/envoy-control-core/
COPY --chown=gradle:gradle envoy-control-runner/ /home/gradle/src/envoy-control-runner/
COPY --chown=gradle:gradle envoy-control-services/ /home/gradle/src/envoy-control-services/
COPY --chown=gradle:gradle envoy-control-source-consul/ /home/gradle/src/envoy-control-source-consul/

WORKDIR /home/gradle/src

RUN gradle :envoy-control-runner:assemble --parallel --no-daemon
