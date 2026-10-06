package io.github.doantrantuandat.example.ordering;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiResource;
import lombok.Data;
import java.math.BigDecimal;

/**
 * Local stand-in for catalog-service's real "product" resource - see Account.java's javadoc for why this
 * is a separate, non-shared class and why the Jackson annotation import is unaffected by the Jackson 2/3 split.
 */
@Data
@JsonApiResource(type = "product")
public class Product {
    @JsonApiId private Long id;
    @JsonProperty private String sku;
    @JsonProperty private String name;
    @JsonProperty private BigDecimal price;
}
