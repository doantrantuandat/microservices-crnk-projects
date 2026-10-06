package io.github.doantrantuandat.example.gateway.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiRelation;
import io.crnk.core.resource.annotations.JsonApiRelationId;
import io.crnk.core.resource.annotations.JsonApiResource;
import io.crnk.core.resource.annotations.SerializeType;
import lombok.Data;

/**
 * Local stand-in for ordering-service's real "orderLine" resource. Deliberately NO orderId/order field:
 * a @JsonApiRelationId field with no matching relation field of the same base name throws
 * InvalidResourceException at context startup (Task 2 hit this exact failure mode on a mismatched-name
 * case), and this gateway never navigates OrderLine -> Order (lines are always reached starting from an
 * Order, via Order.lines), so there's nothing to pair it for here.
 */
@Data
@JsonApiResource(type = "orderLine")
public class OrderLine {
    @JsonApiId private Long id;
    @JsonProperty private Integer qty;

    @JsonApiRelationId
    private Long productId;

    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Product product;
}
