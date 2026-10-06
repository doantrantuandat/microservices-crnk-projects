package io.github.doantrantuandat.example.catalog;

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

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

// @Getter/@Setter only, no @Data: entities shouldn't get generated equals/hashCode/toString
// (Hibernate lazy-proxy / recursive-relation pitfalls).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@JsonApiResource(type = "product")
public class Product extends Auditable {

    @JsonApiId
    @Id
    private Long id;

    @JsonProperty
    @NotBlank
    private String sku;

    @JsonProperty
    @NotBlank
    private String name;

    @JsonProperty
    @NotNull
    @Positive
    private BigDecimal price;

    // Forward FK this service actually owns (which accounts-service account owns this product) - a real
    // persisted column, unlike accounts-service's reverse orderIds/orders pair which has no local column.
    @JsonApiRelationId
    private Long accountId;

    // idField explicit here: crnk's default convention would look for a sibling field named "ownerId"
    // (relation-field-name + "Id"), not "accountId" - confirmed against
    // ResourceInformationProviderBase#buildResourceField in the crnk-core source checkout. Without this,
    // the app fails to start (InvalidResourceException: "accountId" ... no matching relationship found).
    @Transient
    @JsonApiRelation(serialize = SerializeType.ONLY_ID, idField = "accountId")
    private Account owner;
}
