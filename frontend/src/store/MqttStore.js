import { defineStore } from 'pinia';
import mqtt from 'mqtt';
import { computed, ref } from 'vue';
import { ensureSessionId } from '@/utils/public-session'

const env = import.meta.env;

export const simulateMqttStore = defineStore('simulate-mqtt', () => {
  let client = null;
  const handlers = new Set();
  let hasInitialized = false;

  const connectionStatus = ref('offline'); // 'online' | 'reconnecting' | 'offline'

  const isConnected = computed(() => connectionStatus.value === 'online');

  function subscribe(handler) {
    handlers.add(handler);
    return () => {
      handlers.delete(handler);
    };
  }

  function dispatchMessage(message) {
    handlers.forEach((handler) => {
      try {
        handler(message);
      } catch (err) {
        console.error('[SimulateMQTT] Handler error:', err);
      }
    });
  }

  function ensureConnected() {
    if (hasInitialized) return;

    const sessionId = ensureSessionId();

    hasInitialized = true;
    connectionStatus.value = 'offline';

    const options = {
      clientId: `web-client-${sessionId}`,
      username: env.VITE_MQTT_USERNAME || undefined,
      password: env.VITE_MQTT_PASSWORD || undefined,
      reconnectPeriod: 5000,
      connectTimeout: 30 * 1000,
      keepalive: 60,
      clean: true,
    };

    const mqttUrl = env.VITE_MQTT_URL;
    if (!mqttUrl) {
      console.warn('[SimulateMQTT] VITE_MQTT_URL 未配置');
      hasInitialized = false;
      return;
    }
    client = mqtt.connect(mqttUrl, options);

    const topic = `/simulate/result/${sessionId}`;

    client.on('connect', () => {
      connectionStatus.value = 'online';
      client.subscribe(topic, { qos: 2 }, (err) => {
        if (err) {
          console.error(`[SimulateMQTT] Subscribe failed: ${topic}`, err);
        }
      });
    });

    client.on('message', (receivedTopic, payload) => {
      if (receivedTopic === topic) {
        let message;
        try {
          message = payload.toString();
          dispatchMessage(message);
        } catch (e) {
          console.error('[SimulateMQTT] Failed to parse message payload', e);
        }
      }
    });

    client.on('error', (err) => {
      console.error('[SimulateMQTT] Connection error', err);
      if (hasInitialized) {
        connectionStatus.value = 'offline';
      }
    });

    client.on('reconnect', () => {
      connectionStatus.value = 'reconnecting';
    });

    client.on('close', () => {
      if (hasInitialized) {
        connectionStatus.value = 'offline';
      }
    });

    const handleBeforeUnload = () => {
      if (client) {
        client.end(true);
      }
    };

    if (typeof window !== 'undefined') {
      window.addEventListener('beforeunload', handleBeforeUnload);
    }
  }

  function reconnect() {
    if (client) {
      client.end(true);
      client = null;
    }
    hasInitialized = false;
    ensureConnected();
  }

  function close() {
    hasInitialized = false;
    handlers.clear();
    if (client) {
      client.end(true);
      client = null;
    }
    connectionStatus.value = 'offline';
  }

  return {
    isConnected,
    connectionStatus,
    subscribe,
    ensureConnected,
    close,
    reconnect,
  };
});
