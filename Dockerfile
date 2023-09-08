ARG JENKINS_VERSION=2.401.3

FROM jenkins/jenkins:${JENKINS_VERSION} as jenkins_cli
USER root
RUN bash -c "jenkins.sh &" && sleep 45 && \
    curl http://localhost:8080/jnlpJars/jenkins-cli.jar -o /usr/share/jenkins/jenkins-cli.jar

FROM jenkins/jenkins:${JENKINS_VERSION} as jenkins
ARG NEWRELIC_PLUGIN_VERSION=1.0.5
COPY plugins.txt /tmp/plugins.txt
USER root
RUN jenkins-plugin-cli --plugin-file /tmp/plugins.txt && bash -c "jenkins.sh &"

# As we just use the artefacts a smaller image can be used as final target
FROM alpine:latest
COPY --from=jenkins /usr/share/jenkins/ref/plugins /usr/share/jenkins/ref/plugins
COPY --from=jenkins /usr/share/jenkins/jenkins.war /usr/share/jenkins/jenkins.war
COPY --from=jenkins_cli /usr/share/jenkins/jenkins-cli.jar /usr/share/jenkins/jenkins-cli.jar
