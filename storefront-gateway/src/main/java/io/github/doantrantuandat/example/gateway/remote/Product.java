package io.github.doantrantuandat.example.gateway.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiResource;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Local stand-in for catalog-service's real "product" resource - see Account.java's javadoc for why this
 * is a separate, non-shared class.
 */
@Data
@JsonApiResource(type = "product")
public class Product {
    @JsonApiId private Long id;
    @JsonProperty private String sku;
    @JsonProperty private String name;
    @JsonProperty private BigDecimal price;
}
