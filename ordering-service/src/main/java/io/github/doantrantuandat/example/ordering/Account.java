package io.github.doantrantuandat.example.ordering;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiResource;
import lombok.Data;

/**
 * Local stand-in for accounts-service's real "account" resource, needed only to give crnk-client a Java
 * type to deserialize remote JSON:API "account" documents into - matched by {@code type}, not by sharing
 * a compiled class with accounts-service. Order.account is resolved purely by id (no filter paths against
 * this relation from here), so this doesn't need to mirror every field accounts-service's real entity has -
 * just enough to be a useful "account" stand-in. Jackson annotation classes (this import included) live
 * under com.fasterxml.jackson.annotation regardless of Jackson 2 vs. Jackson 3 - confirmed against this
 * exact crnk-client-jackson3/crnk-core-jackson3 generation's own working example
 * (crnk-integration-examples/spring-boot4-example's Task.java/Project.java use the identical import).
 */
@Data
@JsonApiResource(type = "account")
public class Account {
    @JsonApiId private Long id;
    @JsonProperty private String name;
    @JsonProperty private String email;
    @JsonProperty private String plan;
}
