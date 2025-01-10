package br.com.home.automateservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public class ProducerException extends RuntimeException {
    public ProblemDetail toProblemDetail() {
        var pb = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        pb.setTitle("Producer internal server error");

        return pb;
    }
}
