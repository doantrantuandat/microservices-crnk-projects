package io.github.doantrantuandat.example.ordering;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiRelation;
import io.crnk.core.resource.annotations.JsonApiRelationId;
import io.crnk.core.resource.annotations.JsonApiResource;
import io.crnk.core.resource.annotations.SerializeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// @Getter/@Setter only, no @Data - same Hibernate lazy-proxy / recursive-relation reasoning as Order.
//
// Deliberately NOT gated by MockAuth (see brief/plan): OrderLine create/delete requires no X-Mock-Role at
// all, unlike Order - a proportionality call already made upstream, not an oversight.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@JsonApiResource(type = "orderLine")
public class OrderLine {

    @JsonApiId
    @Id
    private Long id;

    @JsonProperty
    @NotNull
    @Positive
    private Integer qty;

    // Local @ManyToOne back to Order: a normal bidirectional JPA association sharing one FK column
    // ("order_id") between two fields - orderId is the real writable column, order is a read-only
    // (insertable = false, updatable = false) navigational view of the same column. This is the standard
    // "shared join column" pattern for exposing both a plain scalar FK and a lazy to-one association
    // without Hibernate trying to write the same column twice. (The brief's own snippet put @ManyToOne on
    // both fields, including the Long-typed orderId - not valid JPA on a scalar field; adjusted per the
    // brief's own invitation to fix the exact mapping annotations for a clean bidirectional association.)
    @Column(name = "order_id")
    @JsonApiRelationId
    private Long orderId;

    @ManyToOne
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Order order;

    // Forward cross-service relation to catalog-service's "product" - same shape as Order.account: not a
    // local JPA association (catalog-service owns that table), so @Transient on the Java-object side;
    // productId is the real persisted column. Stems match ("product"/"productId").
    @JsonApiRelationId
    private Long productId;

    @Transient
    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Product product;
}
