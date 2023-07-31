ARG JENKINS_VERSION=2.401.2

FROM jenkins/jenkins:${JENKINS_VERSION} as jenkins_cli
USER root
RUN bash -c "jenkins.sh &" && sleep 30 && \
    curl http://localhost:8080/jnlpJars/jenkins-cli.jar -o /usr/share/jenkins/jenkins-cli.jar

FROM jenkins/jenkins:${JENKINS_VERSION} as jenkins
ARG NEWRELIC_PLUGIN_VERSION=1.0.5
COPY plugins.txt /tmp/plugins.txt
USER root
RUN curl -sSL https://github.com/newrelic/nr-jenkins-plugin/releases/download/v${NEWRELIC_PLUGIN_VERSION}/nr-jenkins-${NEWRELIC_PLUGIN_VERSION}.zip -o /tmp/newrelic.zip && \
    cd /tmp && unzip /tmp/newrelic.zip && \
    ls -l /tmp/ && \
    jenkins-plugin-cli --plugin-file /tmp/plugins.txt && bash -c "jenkins.sh &" && \
    cp /tmp/nr-jenkins-plugin/new-relic.hpi /usr/share/jenkins/ref/plugins/

COPY --from=jenkins_cli /usr/share/jenkins/jenkins-cli.jar /usr/share/jenkins/jenkins-cli.jar
