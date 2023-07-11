ARG JENKINS_VERSION=2.387.1

FROM jenkins/jenkins:${JENKINS_VERSION} as jenkins
ARG NEWRELIC_PLUGIN_VERSION=1.0.5
COPY plugins.txt /tmp/plugins.txt
USER root
RUN curl -sSL https://github.com/newrelic/nr-jenkins-plugin/releases/download/v${NEWRELIC_PLUGIN_VERSION}/nr-jenkins-${NEWRELIC_PLUGIN_VERSION}.zip -o /tmp/newrelic.zip && \
    cd /tmp && unzip /tmp/newrelic.zip && \
    ls -l /tmp/ && \
    jenkins-plugin-cli --plugin-file /tmp/plugins.txt && bash -c "jenkins.sh &" && \
    retries=0 && \
    until [ $retries -ge 20 ] || { curl --output /dev/null --silent --fail http://localhost:8080/jnlpJars/jenkins-cli.jar && ! grep -q "Please wait while Jenkins is getting ready to work" /usr/share/jenkins/jenkins-cli.jar; }; do \
        retries=$((retries + 1)); \
        echo "Retrying to get the correct jenkins-cli.jar... Attempt $retries"; \
        sleep 10; \
    done && \
    if [ $retries -ge 20 ]; then \
        echo "Maximum number of retries exceeded. Failed to obtain the correct jenkins-cli.jar file."; \
        exit 1; \
    fi && \
    curl http://localhost:8080/jnlpJars/jenkins-cli.jar -o /usr/share/jenkins/jenkins-cli.jar && \
    cp /tmp/nr-jenkins-plugin/new-relic.hpi /usr/share/jenkins/ref/plugins/

# As we just use the artefacts a smaller image can be used as final target
FROM alpine:latest 
COPY --from=jenkins /usr/share/jenkins/ref/plugins /usr/share/jenkins/ref/plugins
COPY --from=jenkins /usr/share/jenkins/jenkins.war /usr/share/jenkins/jenkins.war
COPY --from=jenkins /usr/share/jenkins/jenkins-cli.jar /usr/share/jenkins/jenkins-cli.jar
