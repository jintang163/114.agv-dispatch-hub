package com.agv.infra.mqtt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {
    /** tcp://emqx:1883 */
    private String broker = "tcp://localhost:1883";
    private String clientIdPrefix = "scheduler";
    private String username = "";
    private String password = "";
    private int qos = 1;
}
