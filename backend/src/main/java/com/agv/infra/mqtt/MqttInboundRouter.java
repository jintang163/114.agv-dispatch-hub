package com.agv.infra.mqtt;

import com.agv.scheduler.DispatchEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MQTT 入站路由：
 *   agv/{code}/status → DispatchEngine.handleStatus
 *   agv/{code}/event  → DispatchEngine.handleRobotEvent（payload.type 区分事件）
 */
@Slf4j
@Component
public class MqttInboundRouter implements MessageHandler {

    private final DispatchEngine engine;
    private final ObjectMapper om;

    public MqttInboundRouter(DispatchEngine engine, ObjectMapper om) {
        this.engine = engine;
        this.om = om;
    }

    @Override
    @ServiceActivator(inputChannel = "mqttInputChannel")
    @SuppressWarnings("unchecked")
    public void handleMessage(Message<?> message) {
        try {
            String topic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));
            String payload = String.valueOf(message.getPayload());
            if (payload.isBlank()) {
                return;
            }
            Map<String, Object> data = om.readValue(payload, Map.class);
            String[] parts = topic.split("/");
            if (parts.length < 3 || !"agv".equals(parts[0])) {
                return;
            }
            String code = parts[1];
            String kind = parts[2];
            if ("status".equals(kind)) {
                engine.handleStatus(code, data);
            } else if ("event".equals(kind)) {
                String type = String.valueOf(data.getOrDefault("type", ""));
                engine.handleRobotEvent(code, type, data);
            }
        } catch (Exception e) {
            log.warn("MQTT 入站消息处理失败: {}", e.getMessage());
        }
    }
}
