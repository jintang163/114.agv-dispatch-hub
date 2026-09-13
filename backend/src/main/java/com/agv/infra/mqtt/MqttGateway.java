package com.agv.infra.mqtt;

import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.handler.annotation.Header;

/** MQTT 出站网关 */
@MessagingGateway(defaultRequestChannel = "mqttOutboundChannel")
public interface MqttGateway {

    void send(@Header(MqttHeaders.TOPIC) String topic,
              @Header(MqttHeaders.RETAINED) boolean retained,
              String payload);

    default void send(String topic, String payload) {
        send(topic, false, payload);
    }
}
