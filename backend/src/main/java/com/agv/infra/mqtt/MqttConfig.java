package com.agv.infra.mqtt;

import com.agv.infra.config.AsyncConfig;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.concurrent.Executor;

@Configuration
@IntegrationComponentScan("com.agv.infra.mqtt")
public class MqttConfig {

    @Bean
    public MqttPahoClientFactory mqttClientFactory(MqttProperties props) {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{props.getBroker()});
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            options.setUserName(props.getUsername());
            options.setPassword(props.getPassword().toCharArray());
        }
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setKeepAliveInterval(10);
        options.setConnectionTimeout(10);
        factory.setConnectionOptions(options);
        return factory;
    }

    // ---------------- 入站 ----------------

    @Bean
    public MessageChannel mqttInputChannel(@Qualifier("mqttExecutor") Executor executor) {
        return new ExecutorChannel(executor);
    }

    @Bean
    public MessageProducer mqttInbound(MqttPahoClientFactory factory,
                                       MqttProperties props,
                                       @Qualifier("mqttInputChannel") MessageChannel channel) {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                props.getClientIdPrefix() + "-in", factory,
                Topics.STATUS_WILDCARD, Topics.EVENT_WILDCARD);
        adapter.setCompletionTimeout(5000);
        adapter.setQos(props.getQos());
        // Spring Integration 6.x 默认按字符集把负载转为 String，入站路由直接 String.valueOf 兜底
        adapter.setOutputChannel(channel);
        return adapter;
    }

    // ---------------- 出站 ----------------

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutbound(MqttPahoClientFactory factory, MqttProperties props) {
        MqttPahoMessageHandler handler =
                new MqttPahoMessageHandler(props.getClientIdPrefix() + "-out", factory);
        handler.setAsync(true);
        handler.setDefaultQos(props.getQos());
        return handler;
    }
}
