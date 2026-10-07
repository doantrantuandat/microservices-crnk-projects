package io.github.doantrantuandat.example.gateway.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiRelation;
import io.crnk.core.resource.annotations.JsonApiRelationId;
import io.crnk.core.resource.annotations.JsonApiResource;
import io.crnk.core.resource.annotations.SerializeType;
import lombok.Data;

/**
 * Local stand-in for ordering-service's real "orderLine" resource.
 * <p>
 * This originally had no orderId/order field pair (avoiding InvalidResourceException for an unpaired
 * relationId, and because this gateway only ever navigated Order -> OrderLine, never the reverse). A
 * live-integration round proved that's no longer sufficient: ordering-service's Order.lines is a
 * one-to-many "shared join column" JPA mapping where OrderLine.order is insertable=false/updatable=false;
 * the real, writable FK column is only reachable through OrderLine.orderId. Pushing relationship-by-id
 * data through Order's own create() call (its "lines" field) does NOT persist - confirmed live: POST
 * /order with lines:[...] returns 201, but every subsequent read shows an empty lines array. Setting
 * OrderLine.orderId directly when creating the OrderLine itself DOES persist (same writable scalar
 * column, an ordinary insert - see StorefrontController.createOrder, and EVALUATION.md section 2 for the
 * live curl output proving it). So this gateway now does navigate OrderLine -> Order (to
 * create that link going forward), and the field pair belongs back here.
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

    @JsonApiRelationId
    private Long orderId;

    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Order order;
}
