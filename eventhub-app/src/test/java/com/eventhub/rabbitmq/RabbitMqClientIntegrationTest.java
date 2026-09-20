package com.eventhub.rabbitmq;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.LongString;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RabbitMqClientIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void opensAmqpConnectionAndReadsRabbitMqServerMetadata() {
        assertThat(rabbitTemplate).isNotNull();
        assertThat(connectionFactory).isInstanceOf(CachingConnectionFactory.class);
        assertThat(rabbitTemplate.getConnectionFactory()).isSameAs(connectionFactory);

        ServerMetadata serverMetadata = rabbitTemplate.execute(channel -> {
            assertThat(channel.isOpen()).isTrue();

            Connection connection = channel.getConnection();
            assertThat(connection).isNotNull();
            assertThat(connection.isOpen()).isTrue();

            Map<String, Object> serverProperties = connection.getServerProperties();
            assertThat(serverProperties).containsKeys("product", "version");

            return new ServerMetadata(
                    readLongString(serverProperties, "product"),
                    readLongString(serverProperties, "version"));
        });

        assertThat(serverMetadata).isNotNull();
        assertThat(serverMetadata.product()).isEqualTo("RabbitMQ");
        assertThat(serverMetadata.version()).isEqualTo("4.3.5");
    }

    private static String readLongString(Map<String, Object> serverProperties, String propertyName) {
        Object propertyValue = serverProperties.get(propertyName);
        assertThat(propertyValue).isInstanceOf(LongString.class);
        return new String(((LongString) propertyValue).getBytes(), StandardCharsets.UTF_8);
    }

    private record ServerMetadata(String product, String version) {
    }
}
