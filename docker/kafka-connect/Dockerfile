# Debezium images do not ship the Confluent Avro converter since 2.0; add it as a Connect plugin.
ARG DEBEZIUM_VERSION=3.6.3.Final
ARG CONFLUENT_VERSION=8.3.3

FROM alpine:3.22 AS avro-converter
ARG CONFLUENT_VERSION
RUN wget -q -O /tmp/converter.zip \
      "https://hub-downloads.confluent.io/api/plugins/confluentinc/kafka-connect-avro-converter/versions/${CONFLUENT_VERSION}/confluentinc-kafka-connect-avro-converter-${CONFLUENT_VERSION}.zip" \
    && unzip -q /tmp/converter.zip -d /tmp \
    && mv /tmp/confluentinc-kafka-connect-avro-converter-${CONFLUENT_VERSION}/lib /avro-converter

FROM quay.io/debezium/connect:${DEBEZIUM_VERSION}
COPY --from=avro-converter /avro-converter /kafka/connect/confluent-avro-converter
