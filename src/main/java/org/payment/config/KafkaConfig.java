package org.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** The two topics, and what happens when a message can't be processed. */
@Configuration
public class KafkaConfig {

    /** Created on startup if missing. 3 partitions: events for one payment always share one. */
    @Bean
    public NewTopic paymentEventsTopic(@Value("${cutover.kafka.topic:payment-events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /** The dead-letter topic: where broken messages end up instead of blocking everything behind them. */
    @Bean
    public NewTopic deadLetterTopic(@Value("${cutover.kafka.topic:payment-events}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
    }

    /**
     * If the listener throws: try again twice, 200 ms apart (3 attempts in total).
     * Still failing? Publish the message to "<topic>.DLT" and move on to the next one.
     * Spring Boot plugs this bean into every @KafkaListener automatically.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));   // -1: let Kafka pick
        return new DefaultErrorHandler(toDeadLetters, new FixedBackOff(200L, 2L));
    }
}
