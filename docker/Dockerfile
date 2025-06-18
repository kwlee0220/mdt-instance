FROM eclipse-temurin:21.0.7_6-jdk

ARG LOCAL_HOST

RUN mkdir -p /mdt/mdt-instance
RUN mkdir -p /mdt/mdt-share
COPY ./mdt-instance-all.jar /

ENV MDT_HOME=/mdt
ENV LOCAL_HOST=${LOCAL_HOST}

WORKDIR /mdt/mdt-instance
ENTRYPOINT [ \
    "java", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "/mdt-instance-all.jar", \
    "--type", "external" \
]
