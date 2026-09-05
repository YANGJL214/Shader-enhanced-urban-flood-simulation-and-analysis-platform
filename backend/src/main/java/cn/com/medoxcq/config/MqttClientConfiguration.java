package cn.com.medoxcq.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Slf4j
@Configuration
public class MqttClientConfiguration {

    @Bean(destroyMethod = "close")
    public MqttClient mqttClient(
            @Value("${mqtt.client.server-uri}") String serverUri,
            @Value("${mqtt.client.username:}") String username,
            @Value("${mqtt.client.password:}") String password) throws Exception {
        MqttClient client = new MqttClient(serverUri,
                "urbanflood-backend-" + UUID.randomUUID().toString().substring(0, 8),
                new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        if (!username.trim().isEmpty()) options.setUserName(username);
        if (!password.isEmpty()) options.setPassword(password.toCharArray());
        try {
            client.connect(options);
            log.info("Connected to local MQTT broker: {}", serverUri);
        } catch (Exception error) {
            log.warn("MQTT broker is unavailable; the backend will start without progress publishing: {}",
                    error.getMessage());
        }
        return client;
    }
}
