# vegeta/Dockerfile
FROM alpine:3.18

# bash for our script, openssl for rand, wget/tar for Vegeta binary
RUN apk add --no-cache bash openssl wget tar \
 && mkdir /app

ARG VEGETA_VERSION=12.12.0
RUN wget -qO /tmp/vegeta.tar.gz \
     https://github.com/tsenart/vegeta/releases/download/v${VEGETA_VERSION}/vegeta_${VEGETA_VERSION}_linux_amd64.tar.gz \
 && tar -C /usr/local/bin -xzf /tmp/vegeta.tar.gz vegeta \
 && chmod +x /usr/local/bin/vegeta \
 && rm /tmp/vegeta.tar.gz

COPY load-gen-leak.sh /usr/local/bin/load-gen-leak.sh
COPY targets.http /usr/local/targets.http

ENTRYPOINT ["/usr/local/bin/load-gen-leak.sh"]