package br.com.home.automateservice.service;

/** Desfecho de uma tentativa de publicar um evento no Kafka. */
public enum PublishOutcome {
    /** O broker confirmou o recebimento. */
    PUBLISHED,
    /** A publicação falhou; o evento foi devolvido à fila de fallback (ou à DLQ). */
    FELL_BACK
}
