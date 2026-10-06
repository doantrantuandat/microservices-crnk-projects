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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

// @Getter/@Setter only, no @Data: entities shouldn't get generated equals/hashCode/toString (Hibernate
// lazy-proxy / recursive-relation pitfalls) - doubly important here since Order and OrderLine reference
// each other.
//
// @Table(name = "orders") is required, not cosmetic: "order" is a reserved SQL keyword (ORDER BY) and
// Hibernate's default table name (the simple class name "Order") fails on both H2 and Postgres without
// quoting - confirmed against crnk-framework's own interop-demo/order-service, which hits and documents
// this identical pitfall.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "orders")
@JsonApiResource(type = "order")
public class Order extends Auditable {

    @JsonApiId
    @Id
    private Long id;

    @JsonProperty
    @NotBlank
    private String orderNumber;

    // Forward cross-service relation to accounts-service's "account" - not a local JPA association
    // (accounts-service owns that table), so the Java-object side is @Transient; accountId is the real
    // persisted column. Stems match ("account"/"accountId") so no explicit idField is needed - confirmed
    // by this project's own @SpringBootTest actually starting the Spring context (a mismatched idField
    // throws InvalidResourceException at context-startup, not just a wrong-shaped response).
    @JsonApiRelationId
    private Long accountId;

    @Transient
    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private Account account;

    // Local (same-service) real JPA one-to-many, nothing cross-service about it. mappedBy = "order" points
    // at OrderLine's own @ManyToOne navigational field (see OrderLine.java) - standard bidirectional
    // association, not a crnk-specific construct.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private List<OrderLine> lines;
}
