package com.matera.digitaltwin.api.model;

public record ConversionResultDto(
        long   conversionId,
        String fromCurrency,
        String toCurrency,
        double fromAmount,
        double toAmount,
        double rate
) {}
