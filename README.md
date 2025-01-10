# Automate Service for Docker

Automate Service for Baremetal é um conjunto de serviços REST com o objetivo 
de registrar eventos recebidos via automação residencial, via Node-RED no Home Assistant por exemplo,
através de dispositivos sonoff ou compatíveis.

* Desenvolvido em Spring sob o java 21
* Depende de uma infraestrutura com Kafka e MongoDB já operando
* Os eventos são alimentados em um tópico no Kafka para posteriormente serem consumidos.

**Método de uso:**

* URI: /logging
* Method: POST

  Body:
  ```
  msg.headers = {
      "content-type": "application/json"
  }

  msg.payload = {
     "id": <Message ID>,
     "entityId": <HA Entity ID>,
     "eventType": <HA Event Type>,
     "timeFired": <HA TimeStamp on fired>,
     "device": <HA Device>
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