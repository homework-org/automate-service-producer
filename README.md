# Automate Service for Docker

Automate Service for Baremetal é um conjunto de serviços REST com o objetivo 
de registrar eventos recebidos via automação residencial, via Node-RED no Home Assistant por exemplo,
através de dispositivos sonoff ou compatíveis.

* Desenvolvido em Spring sob o java 21
* Depende de uma infraestrutura com Redis, Kafka e MongoDB já operando
* Os eventos são alimentados em um tópico no Kafka para posteriormente serem consumidos.
* Caso o evento não possa ser enviado ao Kafka, ele é enfileirado no Redis (fila `home-assistant-events`)
  e reenviado em lote pelo `RedisEventsTask` a cada 10s. A fila é limitada por `app.redis.fallback`:
    * `max-queue-size` (default 100000): teto da fila; ao atingir, novos eventos vão para a dead-letter queue
    * `max-attempts` (default 10): reenvios por evento antes da dead-letter queue
    * `retention` (default 24h): TTL da key da fila e idade máxima de um evento desde a 1ª falha
    * `dlq-max-size` (default 10000): teto da dead-letter queue `home-assistant-events:dlq`
* O drain aguarda as confirmações do Kafka de cada lote (backpressure) e abre um circuito
  após `app.redis.fallback.drain.trip-after-failed-cycles` (default 3) ciclos seguidos
  totalmente falhos, pulando `cooldown-cycles` (default 6) ciclos antes de retomar.
  Se o Redis estiver indisponível na devolução de um evento, ele é retido em memória
  (até 1000) e reinserido quando o Redis volta.
* Métricas expostas via Actuator/Micrometer: `fallback.enqueued`, `fallback.dead_letter`,
  `fallback.enqueue_failed`, `fallback.queue.depth`, `fallback.dlq.depth`, `fallback.local_buffer.depth`

**Método de uso:**

* URI: /logging
* Method: POST

  Body:
  ```
  {
     "id": "<Message ID>",
     "entityId": "<HA Entity ID>",
     "eventType": "<HA Event Type>",
     "timeFired": "<HA TimeStamp on fired>",
     "device": "<HA Device>"
  }
  ```

  **Exemplo:**
  ```
  {
    "id": "123547",
    "timeFired": "2025-01-02T03:00:00.123Z",
    "device": "corridorLights",
    "eventType": "corridor-lights-on",
    "entityId": "sonoff-corridor-lights-01"
  }
  ```

**O básico...**

  Altere o seu hub do docker em docker.springBootApplication.images no build.gradle.

  Build...

  ```bash
  ./gradlew clean build
  ```
... e para gerar a imagem só altere o repo no build.gradle e:

  ```bash
  ./gradlew dockerBuildImage
  ```
