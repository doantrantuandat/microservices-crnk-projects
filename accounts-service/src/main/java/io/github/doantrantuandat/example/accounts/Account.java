package io.github.doantrantuandat.example.accounts;

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

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Transient;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import java.util.List;

// @Getter/@Setter only, no @Data: entities shouldn't get generated equals/hashCode/toString
// (Hibernate lazy-proxy / recursive-relation pitfalls).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@JsonApiResource(type = "account")
public class Account extends Auditable {

    @JsonApiId
    @Id
    private Long id;

    @JsonProperty
    @NotBlank
    private String name;

    @JsonProperty
    @NotBlank
    @Email
    private String email;

    @JsonProperty
    @NotBlank
    private String plan;

    // Reverse, transient, to-many hop into ordering-service's Order.account relationship (see
    // OrderLinkerModule/AccountRepositoryDecorator) - not a JPA association, ordering-service owns the
    // order data.
    @Transient
    @JsonApiRelationId
    private List<Long> orderIds;

    @Transient
    @JsonApiRelation(serialize = SerializeType.ONLY_ID)
    private List<Order> orders;
}
