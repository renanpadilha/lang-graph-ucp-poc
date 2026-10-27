package com.example.ucpagent.ucp;

import java.io.Serializable;

public final class UcpModels {
    private UcpModels() {}

    /** amount em unidade menor da moeda (2500 BRL = R$ 25,00). storeId identifica de qual loja veio. */
    public record Product(String storeId, String storeName, String productId, String title, String variantId,
                          long amount, String currency, String image, String url)
            implements Serializable {}

    public record SearchFilter(String query, Long maxAmount) implements Serializable {}

    public record Selection(String variantId, String reason) implements Serializable {}

    public record ShippingOption(String id, String title, long amount, String currency)
            implements Serializable {}
}
