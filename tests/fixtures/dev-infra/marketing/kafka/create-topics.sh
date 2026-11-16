#!/usr/bin/env bash
set -Eeuo pipefail

create_topic() {
  KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" \
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 ${3:-}
}

create_topic mk.profile.change.v1 6
create_topic mk.profile.change.v1.dlq 3
create_topic mk.audience.membership.v1 6 "--config cleanup.policy=compact,delete"
create_topic mk.audience.membership.v1.dlq 3
create_topic mk.audience.membership.v1.dlt 3
create_topic mk.journey.signal.v1 6
create_topic mk.journey.signal.v1.dlq 3
create_topic mk.journey.output.v1 6
create_topic mk.journey.output.v1.dlq 3
create_topic mk.journey.output.v1.dlt 3
create_topic mk.engagement.command.v1 6
create_topic mk.engagement.command.v1.dlq 3
create_topic mk.engagement.command.v1.dlt 3
create_topic mk.benefit.command.v1 6
create_topic mk.benefit.command.v1.dlq 3
create_topic mk.benefit.command.v1.dlt 3
create_topic mk.engagement.event.v1 6
create_topic mk.engagement.event.v1.dlq 3
create_topic mk.benefit.event.v1 6
create_topic mk.benefit.event.v1.dlq 3
create_topic marketing.award-expected.v1 6
create_topic marketing.award-expected.v1.dlq 3
create_topic mk.marketing.fact.v1 12
create_topic mk.marketing.fact.v1.dlq 3
create_topic mk.measurement.projection.v1 6 "--config cleanup.policy=compact,delete"
create_topic mk.measurement.projection.v1.dlq 3
create_topic mk.measurement.projection.v1.dlt 3
create_topic mk.platform.outbox.v1 6
create_topic mk.platform.events.v1 6
create_topic mk.platform.events.v1.dlq 3
create_topic mk.release.activation.v1 6
create_topic mk.release.activation.v1.dlt 3
create_topic mk.release.kill-switch.v1 6
create_topic mk.release.kill-switch.v1.dlt 3
