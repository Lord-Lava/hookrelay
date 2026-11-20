package com.hookrelay.endpoint.application;

public class EndpointCapacityException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public EndpointCapacityException() {
    super("Project endpoint limit of 1000 reached");
  }
}
