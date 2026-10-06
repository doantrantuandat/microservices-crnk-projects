package io.github.doantrantuandat.example.gateway.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.crnk.core.resource.annotations.JsonApiId;
import io.crnk.core.resource.annotations.JsonApiResource;
import lombok.Data;

/**
 * Local stand-in for accounts-service's real "account" resource - same pattern used by every other
 * shadow class in this workspace (see ordering-service's Account.java): matched by {@code type}, not by
 * sharing a compiled class with accounts-service.
 */
@Data
@JsonApiResource(type = "account")
public class Account {
    @JsonApiId private Long id;
    @JsonProperty private String name;
    @JsonProperty private String email;
    @JsonProperty private String plan;
}
