package cn.com.medoxcq.module.warning.module.rule.emq;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

/**
 *
 * @author zc
 * @since 2025/12/12
 */
@Slf4j
@Component
public class MqttMessagePublisher {

  private final MqttClient mqttClient;

  public MqttMessagePublisher(MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  /**
   *
   */
  public void publishSimulationResult(String topic, String payload) {
    if (mqttClient == null || !mqttClient.isConnected()) {
      log.warn("MQTT 客户端未连接，无法发送消息到主题: {}", topic);
      return;
    }

    try {
      MqttMessage message = new MqttMessage(payload.getBytes());
      message.setQos(2);
      message.setRetained(false);

      mqttClient.publish(topic, message);
      log.debug("MQTT 消息已发送 -> topic: {}, payload length: {}", topic, payload.length());
    } catch (MqttException e) {
      log.error("发送 MQTT 消息失败 -> topic: {}", topic, e);
    }
  }
  public void publish(String topic, byte[] payload, int qos, boolean retained) {
    if (mqttClient == null || !mqttClient.isConnected()) {
      log.warn("MQTT 客户端未连接，无法发送消息到主题: {}", topic);
      return;
    }

    try {
      MqttMessage message = new MqttMessage(payload);
      message.setQos(qos);
      message.setRetained(retained);
      mqttClient.publish(topic, message);
      log.debug("MQTT 消息已发送 -> topic: {}, qos: {}, retained: {}", topic, qos, retained);
    } catch (MqttException e) {
      log.error("发送 MQTT 消息失败 -> topic: {}", topic, e);
    }
  }

}
