package com.example.ucpagent.ucp;

import java.io.Serializable;

public final class UcpModels {
    private UcpModels() {}

    /** amount em unidade menor da moeda (2500 BRL = R$ 25,00). */
    public record Product(String productId, String title, String variantId, long amount, String currency)
            implements Serializable {}

    public record SearchFilter(String query, Long maxAmount) implements Serializable {}

    public record Selection(String variantId, String reason) implements Serializable {}

    public record ShippingOption(String id, String title, long amount, String currency)
            implements Serializable {}
}
