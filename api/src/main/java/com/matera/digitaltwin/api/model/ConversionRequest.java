package com.matera.digitaltwin.api.model;

public record ConversionRequest(String fromCurrencyCode, String toCurrencyCode, double fromAmount) {}
