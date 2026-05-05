FROM 600735190428.dkr.ecr.us-east-1.amazonaws.com/maarg-base-os:OS-22.04-latest
ARG GIT_USERNAME=
ARG GIT_PASSWORD=

RUN echo "machine github.com" > /root/.netrc
RUN echo "login $GIT_USERNAME" >> /root/.netrc
RUN echo "password $GIT_PASSWORD" >> /root/.netrc

WORKDIR /

RUN git clone --depth 1 -b "main" https://github.com/hotwax/moqui-framework.git

WORKDIR /moqui-framework
RUN git clone --depth 1 -b "main" https://github.com/hotwax/moqui-runtime.git runtime

WORKDIR /moqui-framework/runtime/component
RUN git clone --depth 1 -b "build-gradle-file" https://github.com/dixitdeepak/moqui-atomikos.git
RUN git clone --depth 1 -b "main" https://github.com/hotwax/moqui-sftp.git
RUN git clone --depth 1 -b "main" https://github.com/hotwax/darpan.git
RUN git clone --depth 1 -b "main" https://github.com/toaditi/darpan-hotwax.git
RUN git clone --depth 1 -b "main" https://github.com/toaditi/shopify-darpan.git
RUN git clone --depth 1 -b "main" https://github.com/toaditi/netsuite-darpan.git

RUN rm -rf /root/.netrc

WORKDIR /moqui-framework

#Copy production configuration file.
RUN cp -rf runtime/component/darpan/docker/MoquiProductionConf.xml runtime/conf/
RUN sed -i 's/__SAME_SITE_LAX__/__SAME_SITE_NONE__/g' framework/src/main/webapp/WEB-INF/web.xml
RUN git apply runtime/component/darpan/docker/buildGradle.patch

#Create war file.
RUN ./gradlew addRuntime

#Extract war file.
WORKDIR /moqui-deploy
RUN cp /moqui-framework/runtime/component/darpan/docker/entrypoint.sh /moqui-deploy/

RUN chmod +x entrypoint.sh
RUN jar -xvf /moqui-framework/moqui-plus-runtime.war

ENV DB_LOAD="./gradlew loadProduction -Ptypes=seed,seed-initial,install,ext-seed,ext -Praw"
ENV JAVA_OPTS="-Xms512m -Xmx4096m"
ENV CONF_FILE="/moqui-framework/runtime/conf/MoquiProductionConf.xml"
ENV SLEEP="sleep 2"
ENV JWT_KEY=
ENV MOQUI_HOST=
ENV Moqui_DB_HOST=
ENV Moqui_DB_USER=
ENV Moqui_DB_PASSWORD=
ENV Moqui_DB_NAME=
ENV Moqui_WEBAPP_ALLOW_ORIGINS="darpan-app-uat.hotwax.io,darpan-app.hotwax.io,darpan-uat.hotwax.io,darpan.hotwax.io,hotwax-darpan-dev.web.app,hotwax-darpan-dev.firebaseapp.com,hc-darpan-uat.web.app,hc-darpan-uat.firebaseapp.com,hc-darpan.web.app,hc-darpan.firebaseapp.com"
ENV WEBAPP_ALLOW_ORIGINS=
ENV ELASTICSEARCH_HOST=
ENV TIME_ZONE=

EXPOSE 8080

ENTRYPOINT ./entrypoint.sh && bash
