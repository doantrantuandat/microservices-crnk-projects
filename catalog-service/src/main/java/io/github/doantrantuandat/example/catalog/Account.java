package io.github.doantrantuandat.example.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiResource;
import lombok.Data;

/**
 * Local stand-in for accounts-service's real "account" resource, needed only to give crnk-client a Java
 * type to deserialize remote JSON:API "account" documents into - matched by {@code type}, not by sharing
 * a compiled class with accounts-service. Product.owner is resolved purely by id (no filter paths against
 * this relation), so unlike OrderLinkerModule's shadow class in accounts-service, this one doesn't need to
 * mirror every field accounts-service's real entity has - just enough to be a useful "account" stand-in.
 */
@Data
@JsonApiResource(type = "account")
public class Account {
    @JsonApiId
    private Long id;

    @JsonProperty
    private String name;

    @JsonProperty
    private String email;

    @JsonProperty
    private String plan;
}
