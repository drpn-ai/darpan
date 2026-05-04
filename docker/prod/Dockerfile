FROM 600735190428.dkr.ecr.us-east-1.amazonaws.com/maarg-base-os:OS-22.04-latest

ARG GIT_USERNAME=
ARG GIT_PASSWORD=
ARG MOQUI_FRAMEWORK_REF=main
ARG MOQUI_RUNTIME_REF=main
ARG MOQUI_ATOMIKOS_REF=build-gradle-file
ARG MOQUI_SFTP_REF=main
ARG DARPAN_REF=v2.0.1
ARG DARPAN_HOTWAX_REF=v0.1.0
ARG SHOPIFY_DARPAN_REF=v0.1.0
ARG NETSUITE_DARPAN_REF=v0.0.1

RUN echo "machine github.com" > /root/.netrc
RUN echo "login $GIT_USERNAME" >> /root/.netrc
RUN echo "password $GIT_PASSWORD" >> /root/.netrc

WORKDIR /

RUN git clone --depth 1 -b "$MOQUI_FRAMEWORK_REF" https://github.com/hotwax/moqui-framework.git

WORKDIR /moqui-framework
RUN git clone --depth 1 -b "$MOQUI_RUNTIME_REF" https://github.com/hotwax/moqui-runtime.git runtime

WORKDIR /moqui-framework/runtime/component
RUN git clone --depth 1 -b "$MOQUI_ATOMIKOS_REF" https://github.com/dixitdeepak/moqui-atomikos.git
RUN git clone --depth 1 -b "$MOQUI_SFTP_REF" https://github.com/hotwax/moqui-sftp.git
RUN git clone --depth 1 -b "$DARPAN_REF" https://github.com/hotwax/darpan.git
RUN git clone --depth 1 -b "$DARPAN_HOTWAX_REF" https://github.com/toaditi/darpan-hotwax.git
RUN git clone --depth 1 -b "$SHOPIFY_DARPAN_REF" https://github.com/toaditi/shopify-darpan.git
RUN git clone --depth 1 -b "$NETSUITE_DARPAN_REF" https://github.com/toaditi/netsuite-darpan.git

RUN rm -rf /root/.netrc

WORKDIR /moqui-framework

# Copy production configuration file.
RUN cp -rf runtime/component/darpan/docker/MoquiProductionConf.xml runtime/conf/
RUN sed -i 's/__SAME_SITE_LAX__/__SAME_SITE_NONE__/g' framework/src/main/webapp/WEB-INF/web.xml
RUN git apply runtime/component/darpan/docker/buildGradle.patch

# Create WAR file.
RUN ./gradlew addRuntime

# Extract WAR file.
WORKDIR /moqui-deploy
RUN cp /moqui-framework/runtime/component/darpan/docker/prod/entrypoint.sh /moqui-deploy/

RUN chmod +x entrypoint.sh
RUN jar -xvf /moqui-framework/moqui-plus-runtime.war

ENV DB_LOAD="./gradlew loadProduction -Ptypes=seed,seed-initial,install,ext-seed,ext -Praw,darpan-seed-initial,darpan-seed"
ENV JAVA_OPTS="-Xms512m -Xmx4096m"
ENV CONF_FILE="/moqui-framework/runtime/conf/MoquiProductionConf.xml"
ENV SLEEP="sleep 2"
ENV JWT_KEY=
ENV MOQUI_HOST=
ENV Moqui_DB_HOST=
ENV Moqui_DB_USER=
ENV Moqui_DB_PASSWORD=
ENV Moqui_DB_NAME=
ENV WEBAPP_ALLOW_ORIGINS=
ENV ELASTICSEARCH_HOST=
ENV TIME_ZONE=

EXPOSE 8080

ENTRYPOINT ./entrypoint.sh && bash
