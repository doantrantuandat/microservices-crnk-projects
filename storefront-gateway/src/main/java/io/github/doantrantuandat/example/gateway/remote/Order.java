package io.github.doantrantuandat.example.gateway.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiRelation;
import io.crnk.core.resource.annotations.JsonApiRelationId;
import io.crnk.core.resource.annotations.JsonApiResource;
import io.crnk.core.resource.annotations.SerializeType;
import lombok.Data;

import java.util.List;

/**
 * Local stand-in for ordering-service's real "order" resource. Unlike the other 3 services' shadow
 * Orders (which only ever read accountId for filtering), this gateway also needs the "lines" relation
 * resolved, since StorefrontController's summary/create endpoints walk Order -> OrderLine -> Product.
 */
@Data
@JsonApiResource(type = "order")
public class Order {
    @JsonApiId
    private Long id;

    @JsonProperty
    private String orderNumber;

    @JsonApiRelationId
    private Long accountId;

    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Account account;

    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private List<OrderLine> lines;
}
